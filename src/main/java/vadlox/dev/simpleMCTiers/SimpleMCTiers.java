package vadlox.dev.simpleMCTiers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SimpleMCTiers extends JavaPlugin {
    private static final String MOJANG_API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MCTIERS_API_URL = "https://mctiers.com/api/search_profile/";
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap();

    public SimpleMCTiers() {
    }

    public void onEnable() {
        this.getLogger().info("SimpleMCTiers has been enabled");
        this.getCommand("tier").setExecutor(this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            if (Bukkit.getVersion().contains("1.8")) {
                this.getLogger().warning("PlaceholderAPI support may be limited in 1.8.");
            } else {
                (new TierPlaceholderExpansion(this)).register();
            }
        } else {
            this.getLogger().warning("PlaceholderAPI not found. Placeholders will not be available.");
        }

    }

    public void onDisable() {
        this.getLogger().info("SimpleMcTiersPlugin has been disabled");
        this.cache.clear();
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tier")) {
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /tier <player>");
                return false;
            } else {
                String playerName = args[0];
                this.getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        String response = this.fetchTierData(playerName);
                        sender.sendMessage(this.formatResponse(response, playerName));
                    } catch (IOException var4) {
                        IOException e = var4;
                        sender.sendMessage(ChatColor.RED + "An error occurred while fetching the tier data.");
                        this.getLogger().severe("Error fetching tier data: " + e.getMessage());
                    }

                });
                return true;
            }
        } else {
            return false;
        }
    }

    private boolean isValidMinecraftUsername(String playerName) throws IOException {
        String urlString = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("GET");
        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            boolean var8;
            try {
                String response = reader.readLine();
                var8 = response != null && !response.isEmpty();
            } catch (Throwable var10) {
                try {
                    reader.close();
                } catch (Throwable var9) {
                    var10.addSuppressed(var9);
                }

                throw var10;
            }

            reader.close();
            return var8;
        } else {
            return false;
        }
    }

    private String fetchTierData(String playerName) throws IOException {
        if (this.cache.containsKey(playerName)) {
            return (String)this.cache.get(playerName);
        } else if (!this.isValidMinecraftUsername(playerName)) {
            return this.createErrorResponse(playerName);
        } else {
            String urlString = "https://mctiers.com/api/search_profile/" + playerName;
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("GET");
            int responseCode = connection.getResponseCode();
            BufferedReader reader;
            if (responseCode != 200) {
                this.getLogger().severe("HTTP error code: " + responseCode);
                reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));

                try {
                    String errorMessage = reader != null ? (String)reader.lines().reduce("", (acc, linex) -> {
                        return acc + linex;
                    }) : "No error message available";
                    this.getLogger().severe("Error message: " + errorMessage);
                } catch (Throwable var14) {
                    try {
                        reader.close();
                    } catch (Throwable var12) {
                        var14.addSuppressed(var12);
                    }

                    throw var14;
                }

                reader.close();
                return ChatColor.RED + "Error: This User Is Not Registered On McTiers";
            } else {
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

                String jsonResponse;
                label66: {
                    String var11;
                    try {
                        StringBuilder response = new StringBuilder();

                        String line;
                        while((line = reader.readLine()) != null) {
                            response.append(line);
                        }

                        JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                        if (!jsonObject.has("name")) {
                            jsonResponse = ChatColor.RED + "Error: This User Is Not Registered On McTiers";
                            break label66;
                        }

                        jsonResponse = response.toString();
                        this.cache.put(playerName, jsonResponse);
                        var11 = jsonResponse;
                    } catch (Throwable var15) {
                        try {
                            reader.close();
                        } catch (Throwable var13) {
                            var15.addSuppressed(var13);
                        }

                        throw var15;
                    }

                    reader.close();
                    return var11;
                }

                reader.close();
                return jsonResponse;
            }
        }
    }

    private String createErrorResponse(String playerName) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("name", playerName);
        errorResponse.addProperty("region", "N/A");
        errorResponse.add("rankings", new JsonObject());
        return errorResponse.toString();
    }

    private String formatResponse(String jsonResponse, String playerName) {
        JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
        StringBuilder formattedResponse = new StringBuilder();
        formattedResponse.append(ChatColor.YELLOW).append("------------------------------------\n");
        formattedResponse.append(ChatColor.GREEN).append("Player: ").append(ChatColor.WHITE).append(jsonObject.has("name") ? jsonObject.get("name").getAsString() : playerName).append("\n");
        formattedResponse.append(ChatColor.GREEN).append("Region: ").append(ChatColor.WHITE).append(jsonObject.has("region") ? jsonObject.get("region").getAsString() : "N/A").append("\n");
        JsonObject rankings = jsonObject.getAsJsonObject("rankings");
        if (rankings != null && rankings.size() > 0) {
            formattedResponse.append(ChatColor.GREEN).append("Rankings:\n");

            for(Iterator var6 = rankings.keySet().iterator(); var6.hasNext(); formattedResponse.append("\n")) {
                String key = (String)var6.next();
                JsonObject ranking = rankings.getAsJsonObject(key);
                int tier = ranking.get("tier").getAsInt();
                int pos = ranking.get("pos").getAsInt();
                String tierStr = (pos == 0 ? "HT" : "LT") + tier;
                formattedResponse.append(ChatColor.AQUA).append("  - ").append(this.capitalize(key)).append(": ").append(tierStr);
                if (pos != 0 && pos != 1) {
                    formattedResponse.append(", Position ").append(pos);
                }
            }
        } else {
            formattedResponse.append(ChatColor.RED).append("No rankings available.\n");
        }

        formattedResponse.append(ChatColor.YELLOW).append("------------------------------------");
        return formattedResponse.toString();
    }

    private String capitalize(String str) {
        return str != null && !str.isEmpty() ? str.substring(0, 1).toUpperCase(Locale.ROOT) + str.substring(1) : str;
    }

    public class TierPlaceholderExpansion extends PlaceholderExpansion {
        private final SimpleMCTiers plugin;

        public TierPlaceholderExpansion(SimpleMCTiers plugin) {
            this.plugin = plugin;
        }

        public @NotNull String getIdentifier() {
            return "tier";
        }

        public @NotNull String getAuthor() {
            return this.plugin.getDescription().getAuthors().toString();
        }

        public @NotNull String getVersion() {
            return this.plugin.getDescription().getVersion();
        }

        public boolean persist() {
            return true;
        }

        public boolean canRegister() {
            return true;
        }

        public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
            if (player == null) {
                return "";
            } else {
                String mode = params.toLowerCase(Locale.ROOT);
                String playerName = player.getName();

                try {
                    String response = this.plugin.fetchTierData(playerName);
                    if (response == null) {
                        return ChatColor.RED + "N/A";
                    } else {
                        JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
                        JsonObject rankings = jsonObject.getAsJsonObject("rankings");
                        if (rankings != null && rankings.has(mode)) {
                            JsonObject ranking = rankings.getAsJsonObject(mode);
                            int tier = ranking.get("tier").getAsInt();
                            int pos = ranking.get("pos").getAsInt();
                            return (pos == 0 ? "HT" : "LT") + tier;
                        } else {
                            return ChatColor.RED + "N/A";
                        }
                    }
                } catch (IOException var11) {
                    IOException e = var11;
                    this.plugin.getLogger().severe("Error fetching tier data: " + e.getMessage());
                    return ChatColor.RED + "N/A";
                }
            }
        }
    }
}
