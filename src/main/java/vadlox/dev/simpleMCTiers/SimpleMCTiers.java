package vadlox.dev.simpleMCTiers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.PlaceholderAPI;
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
    private static final String MOJANG_API_URL   = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MCTIERS_API_URL  = "https://mctiers.com/api/search_profile/";
    // VanillaList live root (single page contains all tables)
    private static final String VANILLALIST_URL  = "https://vanillalist.xyz/";
    private static final String PREFIX           = ChatColor.translateAlternateColorCodes('&', "&e&lTiers&8 » ");
    private static final List<String> GAMEMODES  = Arrays.asList(
            "axe","nethop","uhc","mace","smp","pot","vanilla","sword"
    );
    private static final List<String> COMBATRANKS= Arrays.asList("I","II","III","IV","V","X","S");

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    // VanillaList per-player cache (mode -> tier code, e.g. HT3)
    private final ConcurrentHashMap<String, Map<String,String>> vanillaCache = new ConcurrentHashMap<>();
    private Connection connection;

    // ------------------------------------------------------------------------
    // Plugin Lifecycle
    // ------------------------------------------------------------------------
    @Override
    public void onEnable() {
        getLogger().info("SimpleMCTiers has been enabled");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        setupDatabase();

        // /tier
        getCommand("tier").setExecutor(this);
        getCommand("tier").setTabCompleter(this);
        // /simplemctiers
        getCommand("simplemctiers").setExecutor(this);
        getCommand("simplemctiers").setTabCompleter(this);

        // PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new TierPlaceholderExpansion(this).register();
            new CombatRankPlaceholderExpansion(this).register();
            new EloTierPlaceholderExpansion(this).register();
            // VanillaList specific placeholder: %vntier_<gamemode>%
            new VanillaListPlaceholderExpansion(this).register();
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("SimpleMCTiers has been disabled");
        cache.clear();
        vanillaCache.clear();
        if (connection != null) try { connection.close(); } catch (SQLException ignored) {}
    }

    // ------------------------------------------------------------------------
    // Database Setup
    // ------------------------------------------------------------------------
    private void setupDatabase() {
        try {
            File db = new File(getDataFolder(), "overrides.db");
            String url = "jdbc:sqlite:" + db.getAbsolutePath();
            connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS overrides (
                        username TEXT NOT NULL,
                        gamemode TEXT,
                        tier INTEGER,
                        combatrank TEXT,
                        points INTEGER,
                        PRIMARY KEY(username, gamemode)
                    )
                """);
            }
        } catch (SQLException e) {
            getLogger().severe("DB setup failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("tier")) {
            return handleTier(sender, args);
        } else {
            return handleAdmin(sender, args);
        }
    }

    private boolean handleTier(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /tier <player> <gamemode>");
            return false;
        }
        String user = capitalize(args[0]);
        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!GAMEMODES.contains(mode)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Unknown gamemode.");
            return false;
        }
        // Use Folia-compatible scheduler with fallback
        runAsyncTask(() -> {
            try {
                String json = fetchTierData(user);
                sender.sendMessage(formatSingleTier(json, user, mode));
            } catch (IOException e) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Error fetching tier data.");
            }
        });
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
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
                    if (args.length != 4) throw new IllegalArgumentException("Usage: settier <player> <gamemode> <HT/LT#>");
                    String mode = args[2].toLowerCase(Locale.ROOT);
                    String code = args[3].toUpperCase();
                    if (!GAMEMODES.contains(mode)) throw new IllegalArgumentException("Unknown gamemode.");
                    if (!code.matches("H[Tt]\\d+|L[Tt]\\d+")) throw new IllegalArgumentException("Tier must be HT# or LT#");
                    int tierVal = Integer.parseInt(code.substring(2));
                    // store tier
                    upsertOverride(user, mode, tierVal, null, null);
                    // recalc and store combatrank based on current points
                    recalcCombatRank(user);
                    sender.sendMessage(PREFIX + "Set tier override: " + user + " #" + code + " in " + mode);
                }
                case "setcombatrank" -> {
                    if (args.length != 3) throw new IllegalArgumentException("Usage: setcombatrank <player> <rank>");
                    String rank = args[2].toUpperCase(Locale.ROOT);
                    if (!COMBATRANKS.contains(rank)) throw new IllegalArgumentException("Invalid rank.");
                    upsertOverride(user, null, null, rank, null);
                    sender.sendMessage(PREFIX + "Set Combat Rank override: " + user + " → " + rank);
                }
                case "setpoints" -> {
                    if (args.length != 3) throw new IllegalArgumentException("Usage: setpoints <player> <points>");
                    int pts = Integer.parseInt(args[2]);
                    upsertOverride(user, null, null, null, pts);
                    recalcCombatRank(user);
                    sender.sendMessage(PREFIX + "Set points override: " + user + " → " + pts);
                }
                case "reset" -> {
                    if (args.length != 2) throw new IllegalArgumentException("Usage: reset <player>");
                    deleteOverride(user);
                    sender.sendMessage(PREFIX + "Reset all overrides for " + user);
                }
                default -> throw new IllegalArgumentException("Unknown subcommand.");
            }
        } catch (Exception e) {
            sender.sendMessage(PREFIX + ChatColor.RED + e.getMessage());
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Tab Completion
    // ------------------------------------------------------------------------
    @Override
    public @Nullable List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("tier")) {
            if (args.length == 1) return suggestPlayers(args[0]);
            if (args.length == 2) return suggestList(GAMEMODES, args[1]);
        } else {
            if (args.length == 1) return suggestList(
                    List.of("settier","setcombatrank","setpoints","reset"), args[0]
            );
            if (args.length == 2) return suggestPlayers(args[1]);
            if (args.length == 3) {
                if (args[0].equalsIgnoreCase("settier"))
                    return suggestList(GAMEMODES, args[2]);
                if (args[0].equalsIgnoreCase("setcombatrank"))
                    return suggestList(COMBATRANKS, args[2]);
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("settier")) {
                // HT1..HT10 + LT1..LT10
                List<String> codes = new ArrayList<>();
                for (int i = 1; i <= 10; i++) {
                    codes.add("HT"+i);
                    codes.add("LT"+i);
                }
                return suggestList(codes, args[3]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> suggestPlayers(String prefix) {
        String low = prefix.toLowerCase(Locale.ROOT);
        var out = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getName().toLowerCase().startsWith(low)) out.add(p.getName());
        return out;
    }
    private List<String> suggestList(List<String> list, String prefix) {
        String low = prefix.toLowerCase(Locale.ROOT);
        var out = new ArrayList<String>();
        for (String s : list)
            if (s.toLowerCase().startsWith(low)) out.add(s);
        return out;
    }

    // ------------------------------------------------------------------------
    // Overrides DB Helpers
    // ------------------------------------------------------------------------
    private void upsertOverride(String user, String mode, Integer tier,
                                String combatrank, Integer points) throws SQLException {
        // read existing
        Integer curTier = null; String curRank = null; Integer curPts = null;
        try (PreparedStatement sel = connection.prepareStatement(
                "SELECT tier, combatrank, points FROM overrides WHERE username=? AND gamemode IS ?"
        )) {
            sel.setString(1, user);
            sel.setString(2, mode);
            ResultSet rs = sel.executeQuery();
            if (rs.next()) {
                curTier = rs.getInt("tier");
                curRank= rs.getString("combatrank");
                curPts  = rs.getInt("points");
            }
        }
        curTier = (tier   != null)? tier   : curTier;
        curRank = (combatrank!=null)? combatrank:curRank;
        curPts  = (points != null)? points : curPts;

        try (PreparedStatement up = connection.prepareStatement(
                "REPLACE INTO overrides(username,gamemode,tier,combatrank,points) VALUES(?,?,?,?,?)"
        )) {
            up.setString(1, user);
            up.setString(2, mode);
            if (curTier   != null) up.setInt(3, curTier);   else up.setNull(3,Types.INTEGER);
            if (curRank   != null) up.setString(4, curRank); else up.setNull(4,Types.VARCHAR);
            if (curPts    != null) up.setInt(5, curPts);    else up.setNull(5,Types.INTEGER);
            up.executeUpdate();
        }
    }

    private void deleteOverride(String user) throws SQLException {
        try (PreparedStatement d = connection.prepareStatement(
                "DELETE FROM overrides WHERE username=?"
        )) {
            d.setString(1, user);
            d.executeUpdate();
        }
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

    // ------------------------------------------------------------------------
    // Combat Rank Recalc Helper
    // ------------------------------------------------------------------------
    private void recalcCombatRank(String user) throws Exception {
        // get points override or from API
        Integer ptsO = getOverridePoints(user);
        int pts = (ptsO != null) ? ptsO : JsonParser
                .parseString(fetchTierData(user))
                .getAsJsonObject()
                .get("points").getAsInt();

        String rank;
        if (pts >= 100) rank="S";
        else if (pts >= 50) rank="X";
        else if (pts >= 25) rank="V";
        else if (pts >= 15) rank="IV";
        else if (pts >= 10) rank="III";
        else if (pts >= 5)  rank="II";
        else if (pts >= 1)  rank="I";
        else rank = null;

        upsertOverride(user, null, null, rank, ptsO);
    }

    // ------------------------------------------------------------------------
    // Data Fetching & Formatting
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
        if (conn.getResponseCode() == 404) return createErrorResponse(playerName);
        if (conn.getResponseCode() != 200) return ChatColor.RED+"Error";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (!json.has("name")) return createErrorResponse(playerName);
            cache.put(playerName, sb.toString());
            return sb.toString();
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

    private String formatSingleTier(String jsonResponse, String playerName, String gamemode) {
        JsonObject json = JsonParser.parseString(jsonResponse).getAsJsonObject();
        StringBuilder out = new StringBuilder(PREFIX);
        Integer oT = null;
        // override tier?
        try {
            PreparedStatement q = connection.prepareStatement(
                    "SELECT tier FROM overrides WHERE username=? AND gamemode=?"
            );
            q.setString(1, playerName);
            q.setString(2, gamemode);
            ResultSet rs = q.executeQuery();
            if (rs.next()) oT = rs.getInt("tier");
        } catch (SQLException ignored) {}

        if (oT != null) {
            out.append(ChatColor.GREEN).append(playerName).append("'s ")
                    .append(capitalize(gamemode)).append(" Tier: ")
                    .append(ChatColor.AQUA).append(oT);
            return out.toString();
        }

        JsonObject ranks = json.getAsJsonObject("rankings");
        if (ranks != null && ranks.has(gamemode)) {
            JsonObject r = ranks.getAsJsonObject(gamemode);
            int tier = r.get("tier").getAsInt();
            int pos  = r.get("pos").getAsInt();
            String ts = (pos==0?"HT":"LT")+tier;
            out.append(ChatColor.GREEN).append(playerName).append("'s ")
                    .append(capitalize(gamemode)).append(" Tier: ")
                    .append(ChatColor.AQUA).append(ts);
        } else {
            // NEW: VanillaList fallback
            String vanillaTier = getVanillaListTier(playerName, gamemode);
            if (vanillaTier != null) {
                out.append(ChatColor.GREEN).append(playerName).append("'s ")
                        .append(capitalize(gamemode)).append(" Tier (VNL): ")
                        .append(ChatColor.AQUA).append(vanillaTier);
            } else {
                // No tier found in mctiers.com or VanillaList, try ELO-based fallback
                String eloTier = getEloBasedTier(playerName);
                if (eloTier != null) {
                    out.append(ChatColor.GREEN).append(playerName).append("'s ")
                            .append(capitalize(gamemode)).append(" Tier (ELO): ")
                            .append(eloTier);
                } else {
                    out.append(ChatColor.RED).append("N/A");
                }
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------
    // PlaceholderAPI (original expansions restored)
    // ------------------------------------------------------------------------
    public class TierPlaceholderExpansion extends PlaceholderExpansion {
        private final SimpleMCTiers plugin;
        public TierPlaceholderExpansion(SimpleMCTiers plugin){this.plugin=plugin;}
        @NotNull @Override public String getIdentifier(){return "tier";}
        @NotNull @Override public String getAuthor(){return plugin.getDescription().getAuthors().toString();}
        @NotNull @Override public String getVersion(){return plugin.getDescription().getVersion();}
        @Override public boolean persist(){return true;}
        @Override public boolean canRegister(){return true;}
        @Nullable @Override
        public String onPlaceholderRequest(Player p,@NotNull String params){
            return formatSingleTierQuiet(p.getName(),params);
        }
        private String formatSingleTierQuiet(String u,String m){
            try {return plugin.formatSingleTier(plugin.fetchTierData(u),u,m);} catch(Exception e){return ChatColor.RED+"N/A";}
        }
    }

    public class CombatRankPlaceholderExpansion extends PlaceholderExpansion {
        private final SimpleMCTiers plugin;
        public CombatRankPlaceholderExpansion(SimpleMCTiers plugin){this.plugin=plugin;}
        @NotNull @Override public String getIdentifier(){return "combatrank";}
        @NotNull @Override public String getAuthor(){return plugin.getDescription().getAuthors().toString();}
        @NotNull @Override public String getVersion(){return plugin.getDescription().getVersion();}
        @Override public boolean persist(){return true;}
        @Override public boolean canRegister(){return true;}
        @Nullable @Override
        public String onPlaceholderRequest(Player p,@NotNull String params){
            if (!params.equalsIgnoreCase("overall")) return null;
            String u = p.getName();
            String or = getOverrideCombatRank(u);
            if (or != null) return or;
            int pts; Integer oP = getOverridePoints(u);
            if (oP!=null) pts=oP; else {
                try { pts = JsonParser.parseString(fetchTierData(u)).getAsJsonObject().get("points").getAsInt(); }
                catch(Exception e){return ChatColor.RED+"N/A";}
            }
            if (pts >= 100) return ChatColor.YELLOW + "S";
            if (pts >= 50)  return ChatColor.RED + "X";
            if (pts >= 25)  return ChatColor.LIGHT_PURPLE + "V";
            if (pts >= 15)  return ChatColor.GRAY + "IV";
            if (pts >= 10)  return ChatColor.GRAY + "III";
            if (pts >= 5)   return ChatColor.GRAY + "II";
            if (pts >= 1)   return ChatColor.DARK_GRAY + "I";
            return ChatColor.RED + "N/A";
        }
    }

    public class EloTierPlaceholderExpansion extends PlaceholderExpansion {
        private final SimpleMCTiers plugin;
        public EloTierPlaceholderExpansion(SimpleMCTiers plugin){this.plugin=plugin;}
        @NotNull @Override public String getIdentifier(){return "tiertag";}
        @NotNull @Override public String getAuthor(){return plugin.getDescription().getAuthors().toString();}
        @NotNull @Override public String getVersion(){return plugin.getDescription().getVersion();}
        @Override public boolean persist(){return true;}
        @Override public boolean canRegister(){return true;}
        @Nullable @Override
        public String onPlaceholderRequest(Player p,@NotNull String params){
            if (!params.equalsIgnoreCase("tier")) return null;
            String eloTier = getEloBasedTier(p.getName());
            return eloTier != null ? eloTier : ChatColor.RED + "N/A";
        }
    }

    // NEW: VanillaList placeholder expansion for %vntier_<gamemode>%
    public class VanillaListPlaceholderExpansion extends PlaceholderExpansion {
        private final SimpleMCTiers plugin;
        public VanillaListPlaceholderExpansion(SimpleMCTiers plugin){this.plugin=plugin;}
        @NotNull @Override public String getIdentifier(){return "vntier";}
        @NotNull @Override public String getAuthor(){return plugin.getDescription().getAuthors().toString();}
        @NotNull @Override public String getVersion(){return plugin.getDescription().getVersion();}
        @Override public boolean persist(){return true;}
        @Override public boolean canRegister(){return true;}
        @Nullable @Override
        public String onPlaceholderRequest(Player p,@NotNull String params){
            String mode = params.toLowerCase(Locale.ROOT);
            if (!GAMEMODES.contains(mode)) return ChatColor.RED + "N/A";
            String tier = getVanillaListTier(p.getName(), mode);
            if (tier != null) return ChatColor.AQUA + tier;
            // attempt live fetch again if not cached
            vanillaCache.remove(p.getName());
            tier = getVanillaListTier(p.getName(), mode);
            return tier != null ? ChatColor.AQUA + tier : ChatColor.RED + "N/A";
        }
    }

    // ------------------------------------------------------------------------
    // VanillaList Parsing / Fallback
    // ------------------------------------------------------------------------
    private String getVanillaListTier(String playerName, String gamemode) {
        try {
            Map<String,String> tiers = vanillaCache.get(playerName);
            if (tiers == null) {
                tiers = fetchAndParseVanillaList(playerName);
                if (tiers != null) vanillaCache.put(playerName, tiers);
            }
            if (tiers == null) return null;
            // normalize gamemode mapping differences
            String key = gamemode.toLowerCase(Locale.ROOT);
            return tiers.getOrDefault(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String,String> fetchAndParseVanillaList(String playerName) throws IOException {
        String html = fetchVanillaListLive();
        if (html == null) html = fetchVanillaListDownloadedFile(); // new intermediate layer
        if (html == null) html = readVanillaListCached();
        if (html == null) return null;
        // Case-insensitive search for player row
        Pattern rowPattern = Pattern.compile("<tr[^>]*>\\s*<td>\\d+</td>\\s*<td[^>]*>.*?data-name=\"" + Pattern.quote(playerName) + "\"[\\s\\S]*?</tr>", Pattern.CASE_INSENSITIVE);
        Matcher rowMatcher = rowPattern.matcher(html);
        if (!rowMatcher.find()) return null;
        String rowHtml = rowMatcher.group();
        // Extract each mode container: src ends with /<mode>.svg and span with tier code HT# or LT#
        Pattern tierPattern = Pattern.compile("<div class=\"tier-mode-container\"[\\s\\S]*?<img[^>]+src=\\\"[^\\\"]*/([a-z0-9]+)\\.svg\\\"[\\s\\S]*?<span class=\\\"player-tier[^>]*>(H[Tt]|L[Tt])\\d+</span>", Pattern.CASE_INSENSITIVE);
        Matcher tierMatcher = tierPattern.matcher(rowHtml);
        Map<String,String> map = new HashMap<>();
        while (tierMatcher.find()) {
            String mode = tierMatcher.group(1).toLowerCase(Locale.ROOT);
            String span = tierMatcher.group();
            // capture the tier code inside last >...
            Matcher codeM = Pattern.compile("(H[Tt]|L[Tt])\\d+").matcher(span);
            if (codeM.find()) {
                String code = codeM.group().toUpperCase(Locale.ROOT);
                // map mode differences
                if (mode.equals("diapot") || mode.equals("pot")) mode = "pot"; // safety
                if (mode.equals("smp") || mode.equals("smpkit")) mode = "smp";
                if (mode.equals("vanilla") || mode.equals("crystal")) mode = "vanilla";
                map.put(mode, code);
            }
        }
        return map;
    }

    private String fetchVanillaListLive() {
        try {
            // try base and anchor variant
            String base = attemptVanillaListFetch(VANILLALIST_URL);
            if (base != null) return base;
            return attemptVanillaListFetch(VANILLALIST_URL + "#crystal");
        } catch (Exception ignored) { return null; }
    }

    private String attemptVanillaListFetch(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (JavaPlugin; +https://example.org)");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            if (conn.getResponseCode() != 200) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (Exception e) { return null; }
    }

    // Try to download and cache a full html file on disk (plugins/SimpleMCTiers/vanillalist_live.html)
    private String fetchVanillaListDownloadedFile() {
        File f = new File(getDataFolder(), "vanillalist_live.html");
        long now = System.currentTimeMillis();
        // reuse if exists and younger than 6h
        if (f.exists() && (now - f.lastModified()) < 6 * 60 * 60 * 1000L) {
            try { return readFileContents(f); } catch (IOException ignored) {}
        }
        // attempt fresh download with more lenient timeouts
        try {
            String content = attemptVanillaListFetch(VANILLALIST_URL + "?ts=" + now);
            if (content == null) content = attemptVanillaListFetch(VANILLALIST_URL + "#crystal");
            if (content != null && content.length() > 500) { // basic sanity
                try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(content); }
                return content;
            }
        } catch (Exception ignored) { }
        // if download failed but old file exists, use it anyway
        if (f.exists()) {
            try { return readFileContents(f); } catch (IOException ignored) {}
        }
        return null;
    }

    private String readFileContents(File f) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f)))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private String readVanillaListCached() {
        try (InputStream is = getResource("vanillalist_cached.html")) {
            if (is == null) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                StringBuilder sb = new StringBuilder();
                String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // NEW: ELO based fallback (simple mapping from points -> rank colours)
    // ------------------------------------------------------------------------
    private String getEloBasedTier(String playerName) {
        try {
            Integer ptsO = getOverridePoints(playerName);
            int pts = (ptsO != null) ? ptsO : JsonParser.parseString(fetchTierData(playerName))
                    .getAsJsonObject().get("points").getAsInt();
            if (pts >= 100) return ChatColor.YELLOW + "S";
            if (pts >= 50)  return ChatColor.RED + "X";
            if (pts >= 25)  return ChatColor.LIGHT_PURPLE + "V";
            if (pts >= 15)  return ChatColor.GRAY + "IV";
            if (pts >= 10)  return ChatColor.GRAY + "III";
            if (pts >= 5)   return ChatColor.GRAY + "II";
            if (pts >= 1)   return ChatColor.DARK_GRAY + "I";
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Folia Compatibility Helper
    // ------------------------------------------------------------------------
    private void runAsyncTask(Runnable task) {
        try {
            // Try to use Folia/Paper async scheduler first
            Object asyncScheduler = getServer().getClass().getMethod("getAsyncScheduler").invoke(getServer());
            asyncScheduler.getClass().getMethod("runNow", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class)
                    .invoke(asyncScheduler, this, (java.util.function.Consumer<Object>) (scheduledTask) -> task.run());
        } catch (Exception e) {
            // Fall back to Bukkit scheduler for Spigot/older Paper
            Bukkit.getScheduler().runTaskAsynchronously(this, task);
        }
    }

    // ------------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------------
    private String capitalize(String s) {
        if (s == null|| s.isEmpty()) return s;
        return s.substring(0,1).toUpperCase(Locale.ROOT)+s.substring(1);
    }
}
