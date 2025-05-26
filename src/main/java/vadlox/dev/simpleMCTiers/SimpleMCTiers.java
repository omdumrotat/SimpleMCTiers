package vadlox.dev.simpleMCTiers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SimpleMCTiers extends JavaPlugin {
    private static final String MOJANG_API_URL   = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MCTIERS_API_URL  = "https://mctiers.com/api/search_profile/";
    private static final String PREFIX           = ChatColor.translateAlternateColorCodes('&', "&e&lTiers&8 » ");
    private static final List<String> GAMEMODES  = Arrays.asList(
            "axe", "nethop", "uhc", "mace", "smp", "pot", "vanilla", "sword"
    );
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("SimpleMCTiers has been enabled");
        getCommand("tier").setExecutor(this);
        getCommand("tier").setTabCompleter(new TierTabCompleter());

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            if (Bukkit.getVersion().contains("1.8")) {
                getLogger().warning("PlaceholderAPI support may be limited in 1.8.");
            } else {
                new TierPlaceholderExpansion(this).register();
            }
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholders will not be available.");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("SimpleMCTiers has been disabled");
        cache.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("tier")) return false;
        if (args.length != 2) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /tier <player> <gamemode>");
            return false;
        }

        String playerName = args[0];
        String mode       = args[1].toLowerCase(Locale.ROOT);
        if (!GAMEMODES.contains(mode)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Invalid gamemode. Available: " + String.join(", ", GAMEMODES));
            return false;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // Check valid username
                if (!isValidMinecraftUsername(playerName)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "That Minecraft username doesn't exist.");
                    return;
                }
                String json = fetchTierData(playerName);
                JsonObject data = JsonParser.parseString(json).getAsJsonObject();
                if (!data.has("name")) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Player isn't registered with McTiers.");
                    return;
                }
                JsonObject rankings = data.getAsJsonObject("rankings");
                if (!rankings.has(mode)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "That player is unranked in " + capitalize(mode) + ".");
                    return;
                }
                // Display tier, capitalize player name first char
                sender.sendMessage(formatSingleTier(json, capitalize(playerName), mode));
            } catch (IOException e) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Player isn't registered with McTiers.");
            }
        });
        return true;
    }

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
        URL url = new URL(MCTIERS_API_URL + playerName);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code == 404) {
            // user not registered
            return new JsonObject().toString();
        } else if (code != 200) {
            return new JsonObject().toString();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String data = sb.toString();
            cache.put(playerName, data);
            return data;
        }
    }

    private String formatSingleTier(String jsonResponse, String playerName, String gamemode) {
        JsonObject json = JsonParser.parseString(jsonResponse).getAsJsonObject();
        StringBuilder out = new StringBuilder(PREFIX);
        JsonObject ranks = json.getAsJsonObject("rankings");
        JsonObject rank = ranks.getAsJsonObject(gamemode);
        int tier = rank.get("tier").getAsInt();
        int pos  = rank.get("pos").getAsInt();
        String ts = (pos == 0 ? "HT" : "LT") + tier;
        out.append(ChatColor.GREEN)
                .append(playerName).append("’s ")
                .append(capitalize(gamemode)).append(" Tier: ")
                .append(ChatColor.AQUA).append(ts);
        if (pos > 1) out.append(ChatColor.GRAY).append(" (Position ").append(pos).append(")");
        return out.toString();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    public class TierTabCompleter implements TabCompleter {
        @Override
        public @Nullable List<String> onTabComplete(CommandSender sender,
                                                    Command command,
                                                    String alias,
                                                    String[] args) {
            if (args.length == 1) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .toList();
            }
            if (args.length == 2) {
                return GAMEMODES.stream()
                        .filter(m -> m.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            return Collections.emptyList();
        }
    }

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
            if (player == null) return "";
            String mode = params.toLowerCase(Locale.ROOT);
            if (!GAMEMODES.contains(mode)) return ChatColor.RED + "N/A";
            try {
                String json = plugin.fetchTierData(player.getName());
                JsonObject ranks = JsonParser.parseString(json)
                        .getAsJsonObject()
                        .getAsJsonObject("rankings");
                if (ranks != null && ranks.has(mode)) {
                    JsonObject r = ranks.getAsJsonObject(mode);
                    int tier = r.get("tier").getAsInt();
                    int pos  = r.get("pos").getAsInt();
                    return (pos == 0 ? "HT" : "LT") + tier;
                }
            } catch (IOException e) {
                // silent fail
            }
            return ChatColor.RED + "N/A";
        }
    }
}
