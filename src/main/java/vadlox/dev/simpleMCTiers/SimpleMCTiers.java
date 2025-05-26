package vadlox.dev.simpleMCTiers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SimpleMCTiers extends JavaPlugin implements TabExecutor {
    // ------------------------------------------------------------------------
    // Constants & Fields
    // ------------------------------------------------------------------------
    private static final String MOJANG_API_URL  = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MCTIERS_API_URL = "https://mctiers.com/api/search_profile/";
    private static final String PREFIX          = ChatColor.translateAlternateColorCodes('&', "&e&lTiers&8 » ");
    private static final List<String> GAMEMODES = Arrays.asList(
            "axe", "nethop", "uhc", "mace", "smp", "pot", "vanilla", "sword"
    );
    private static final List<String> RANKS = Arrays.asList("I","II","III","IV","V","X","S");

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private Connection connection;

    // ------------------------------------------------------------------------
    // Plugin Lifecycle
    // ------------------------------------------------------------------------
    @Override
    public void onEnable() {
        getLogger().info("SimpleMCTiers has been enabled");
        // ensure data folder
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        setupDatabase();

        // register /tier
        getCommand("tier").setExecutor(this);
        getCommand("tier").setTabCompleter(this);
        // register /simplemctiers
        getCommand("simplemctiers").setExecutor(this);
        getCommand("simplemctiers").setTabCompleter(this);

        // PlaceholderAPI hook
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            if (Bukkit.getVersion().contains("1.8")) {
                getLogger().warning("PlaceholderAPI support may be limited in 1.8.");
            } else {
                new TierPlaceholderExpansion(this).register();
                new CombatRankPlaceholderExpansion(this).register();
            }
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholders will not be available.");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("SimpleMCTiers has been disabled");
        cache.clear();
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
        }
    }

    // ------------------------------------------------------------------------
    // Database Setup
    // ------------------------------------------------------------------------
    private void setupDatabase() {
        try {
            File dbFile = new File(getDataFolder(), "overrides.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS overrides (" +
                        "username TEXT NOT NULL," +
                        "gamemode TEXT," +
                        "tier INTEGER," +
                        "combatrank TEXT," +
                        "points INTEGER," +
                        "PRIMARY KEY(username, gamemode)" +
                        ")");
            }
        } catch (SQLException e) {
            getLogger().severe("Could not set up database: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Command Handling (both /tier and /simplemctiers)
    // ------------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("tier")) {
            return handleTierCommand(sender, args);
        } else if (name.equals("simplemctiers")) {
            return handleAdminCommand(sender, args);
        }
        return false;
    }

    private boolean handleTierCommand(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /tier <player> <gamemode>");
            return false;
        }
        String player = capitalize(args[0]);
        String mode   = args[1].toLowerCase(Locale.ROOT);
        if (!GAMEMODES.contains(mode)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Unknown gamemode.");
            return false;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String json = fetchTierData(player);
                sender.sendMessage(formatSingleTier(json, player, mode));
            } catch (IOException e) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Error fetching tier data.");
            }
        });
        return true;
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simplemctiers.admin")) {
            sender.sendMessage(ChatColor.RED + "You lack permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /simplemctiers <settier|setcombatrank|setpoints|reset> ...");
            return false;
        }
        String sub = args[0].toLowerCase();
        String user = capitalize(args[1]);
        try {
            switch (sub) {
                case "settier" -> {
                    if (args.length != 4) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Usage: settier <player> <gamemode> <tier#>");
                        return false;
                    }
                    String mode = args[2].toLowerCase();
                    int tier = Integer.parseInt(args[3]);
                    upsertOverride(user, mode, tier, null, null);
                    sender.sendMessage(PREFIX + "Override set: " + user + " " + mode + " → tier " + tier);
                }
                case "setcombatrank" -> {
                    if (args.length != 3) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Usage: setcombatrank <player> <rank>");
                        return false;
                    }
                    String rank = args[2].toUpperCase();
                    if (!RANKS.contains(rank)) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Invalid rank.");
                        return false;
                    }
                    upsertOverride(user, null, null, rank, null);
                    sender.sendMessage(PREFIX + "Override set: " + user + " → combatrank " + rank);
                }
                case "setpoints" -> {
                    if (args.length != 3) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Usage: setpoints <player> <points#>");
                        return false;
                    }
                    int pts = Integer.parseInt(args[2]);
                    upsertOverride(user, null, null, null, pts);
                    sender.sendMessage(PREFIX + "Override set: " + user + " → points " + pts);
                }
                case "reset" -> {
                    if (args.length != 2) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Usage: reset <player>");
                        return false;
                    }
                    deleteOverride(user);
                    sender.sendMessage(PREFIX + "Overrides reset for " + user);
                }
                default -> sender.sendMessage(PREFIX + ChatColor.RED + "Unknown subcommand.");
            }
        } catch (Exception e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Error: " + e.getMessage());
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Tab Completion for both commands
    // ------------------------------------------------------------------------
    @Override
    public @Nullable List<String> onTabComplete(CommandSender sender,
                                                Command cmd,
                                                String alias,
                                                String[] args) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("tier")) {
            if (args.length == 1) {
                return suggestPlayers(args[0]);
            } else if (args.length == 2) {
                return suggestList(GAMEMODES, args[1]);
            }
        } else if (name.equals("simplemctiers")) {
            if (args.length == 1) {
                return suggestList(Arrays.asList("settier","setcombatrank","setpoints","reset"), args[0]);
            }
            if (args.length == 2) {
                return suggestPlayers(args[1]);
            }
            if (args.length == 3) {
                switch (args[0].toLowerCase()) {
                    case "settier"    ->  { return suggestList(GAMEMODES, args[2]); }
                    case "setcombatrank" -> { return suggestList(RANKS, args[2]); }
                    default -> {}
                }
            }
        }
        return Collections.emptyList();
    }

    private List<String> suggestPlayers(String prefix) {
        String lower = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(lower)) out.add(p.getName());
        }
        return out;
    }
    private List<String> suggestList(List<String> list, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(lower)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------------
    // Database Helpers
    // ------------------------------------------------------------------------
    private void upsertOverride(String user, String mode, Integer tier,
                                String combatrank, Integer points) throws SQLException {
        // read existing
        try (PreparedStatement sel = connection.prepareStatement(
                "SELECT tier, combatrank, points FROM overrides WHERE username=? AND gamemode IS ?"
        )) {
            sel.setString(1, user);
            sel.setString(2, mode);
            ResultSet rs = sel.executeQuery();
            Integer curTier = null;
            String curRank = null;
            Integer curPts = null;
            if (rs.next()) {
                curTier = rs.getInt("tier");
                curRank = rs.getString("combatrank");
                curPts  = rs.getInt("points");
            }
            curTier = (tier   != null) ? tier   : curTier;
            curRank = (combatrank != null) ? combatrank : curRank;
            curPts  = (points != null) ? points : curPts;

            try (PreparedStatement up = connection.prepareStatement(
                    "REPLACE INTO overrides(username,gamemode,tier,combatrank,points) VALUES(?,?,?,?,?)"
            )) {
                up.setString(1, user);
                up.setString(2, mode);
                if (curTier   != null) up.setInt(3, curTier);   else up.setNull(3, Types.INTEGER);
                if (curRank   != null) up.setString(4, curRank); else up.setNull(4, Types.VARCHAR);
                if (curPts    != null) up.setInt(5, curPts);    else up.setNull(5, Types.INTEGER);
                up.executeUpdate();
            }
        }
    }

    private void deleteOverride(String user) throws SQLException {
        try (PreparedStatement d = connection.prepareStatement(
                "DELETE FROM overrides WHERE username = ?"
        )) {
            d.setString(1, user);
            d.executeUpdate();
        }
    }

    private Integer getOverrideTier(String user, String mode) {
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT tier FROM overrides WHERE username=? AND gamemode=?"
        )) {
            q.setString(1, user);
            q.setString(2, mode);
            ResultSet rs = q.executeQuery();
            if (rs.next()) return rs.getInt("tier");
        } catch (SQLException ignored) {}
        return null;
    }

    private String getOverrideCombatRank(String user) {
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT combatrank FROM overrides WHERE username=? AND gamemode IS NULL"
        )) {
            q.setString(1, user);
            ResultSet rs = q.executeQuery();
            if (rs.next()) return rs.getString("combatrank");
        } catch (SQLException ignored) {}
        return null;
    }

    private Integer getOverridePoints(String user) {
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT points FROM overrides WHERE username=? AND gamemode IS NULL"
        )) {
            q.setString(1, user);
            ResultSet rs = q.executeQuery();
            if (rs.next()) return rs.getInt("points");
        } catch (SQLException ignored) {}
        return null;
    }

    // ------------------------------------------------------------------------
    // Data Fetching & Caching
    // ------------------------------------------------------------------------
    private boolean isValidMinecraftUsername(String playerName) throws IOException {
        URL url = new URL(MOJANG_API_URL + playerName);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestMethod("GET");
        if (conn.getResponseCode() != 200) return false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line = r.readLine();
            return line != null && !line.isEmpty();
        }
    }

    private String fetchTierData(String playerName) throws IOException {
        if (cache.containsKey(playerName)) return cache.get(playerName);
        if (!isValidMinecraftUsername(playerName)) return createErrorResponse(playerName);

        URL url = new URL(MCTIERS_API_URL + playerName);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestMethod("GET");
        if (conn.getResponseCode() == 404) {
            // Treat as unregistered
            return createErrorResponse(playerName);
        }
        if (conn.getResponseCode() != 200) {
            return ChatColor.RED + "Error fetching data";
        }

        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (!json.has("name")) return createErrorResponse(playerName);
            String data = sb.toString();
            cache.put(playerName, data);
            return data;
        }
    }

    private String createErrorResponse(String playerName) {
        JsonObject err = new JsonObject();
        err.addProperty("name", playerName);
        err.addProperty("region", "N/A");
        err.add("rankings", new JsonObject());
        err.addProperty("points", 0);
        return err.toString();
    }

    // ------------------------------------------------------------------------
    // Formatting Responses
    // ------------------------------------------------------------------------
    private String formatSingleTier(String jsonResponse, String playerName, String gamemode) {
        JsonObject json = JsonParser.parseString(jsonResponse).getAsJsonObject();
        StringBuilder out = new StringBuilder(PREFIX);
        Integer override = getOverrideTier(playerName, gamemode);
        if (override != null) {
            out.append(ChatColor.GREEN)
                    .append(playerName).append("'s ")
                    .append(capitalize(gamemode)).append(" Tier: ")
                    .append(ChatColor.AQUA)
                    .append((override == 0 ? "HT" : "LT") + override);
            return out.toString();
        }

        JsonObject ranks = json.getAsJsonObject("rankings");
        if (ranks != null && ranks.has(gamemode)) {
            JsonObject r = ranks.getAsJsonObject(gamemode);
            int tier = r.get("tier").getAsInt();
            int pos  = r.get("pos").getAsInt();
            String ts = (pos == 0 ? "HT" : "LT") + tier;
            out.append(ChatColor.GREEN)
                    .append(playerName).append("'s ")
                    .append(capitalize(gamemode)).append(" Tier: ")
                    .append(ChatColor.AQUA).append(ts);
        } else {
            out.append(ChatColor.RED).append("N/A");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------
    // PlaceholderAPI Expansions
    // ------------------------------------------------------------------------
    public class TierPlaceholderExpansion extends PlaceholderExpansion {
        private final SimpleMCTiers plugin;
        public TierPlaceholderExpansion(SimpleMCTiers plugin) { this.plugin = plugin; }
        @NotNull @Override public String getIdentifier() { return "tier"; }
        @NotNull @Override public String getAuthor()     { return plugin.getDescription().getAuthors().toString(); }
        @NotNull @Override public String getVersion()    { return plugin.getDescription().getVersion(); }
        @Override public boolean persist()               { return true; }
        @Override public boolean canRegister()           { return true; }

        @Nullable @Override
        public String onPlaceholderRequest(Player player, @NotNull String params) {
            return formatSingleTierQuiet(player.getName(), params);
        }
        private String formatSingleTierQuiet(String playerName, String gamemode) {
            String json = "{}";
            try { json = plugin.fetchTierData(playerName); }
            catch (Exception ignored) {}
            return plugin.formatSingleTier(json, playerName, gamemode);
        }
    }

    public class CombatRankPlaceholderExpansion extends PlaceholderExpansion {
        private final SimpleMCTiers plugin;
        public CombatRankPlaceholderExpansion(SimpleMCTiers plugin) { this.plugin = plugin; }
        @NotNull @Override public String getIdentifier() { return "combatrank"; }
        @NotNull @Override public String getAuthor()     { return plugin.getDescription().getAuthors().toString(); }
        @NotNull @Override public String getVersion()    { return plugin.getDescription().getVersion(); }
        @Override public boolean persist()               { return true; }
        @Override public boolean canRegister()           { return true; }

        @Nullable @Override
        public String onPlaceholderRequest(Player player, @NotNull String params) {
            if (!params.equalsIgnoreCase("overall")) return null;
            String user = player.getName();
            // override?
            String orank = getOverrideCombatRank(user);
            if (orank != null) return orank;
            // fetch points
            String json = "{}";
            try { json = plugin.fetchTierData(user); }
            catch (Exception ignored) {}
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            int pts = obj.has("points") ? obj.get("points").getAsInt() : 0;
            Integer opr = getOverridePoints(user);
            if (opr != null) pts = opr;
            // rank thresholds
            if (pts >= 100) return "S";
            if (pts >= 50)  return "X";
            if (pts >= 25)  return "V";
            if (pts >= 15)  return "IV";
            if (pts >= 10)  return "III";
            if (pts >= 5)   return "II";
            if (pts >= 1)   return "I";
            return ChatColor.RED + "N/A";
        }
    }

    // ------------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------------
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
