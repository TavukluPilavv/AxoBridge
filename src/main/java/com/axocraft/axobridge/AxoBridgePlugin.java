package com.axocraft.axobridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class AxoBridgePlugin extends JavaPlugin {
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;
    private String displayName;
    private int intervalSeconds;
    private volatile String lastResult = "Henüz bağlantı denenmedi.";
    private final AtomicBoolean orderPollInFlight = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        if (apiKey.isBlank() || apiKey.equals("PASTE_YOUR_PRIVATE_SERVER_KEY_HERE")
                || baseUrl.contains("YOUR-AXOBRIDGE-API")) {
            getLogger().warning("API adresi veya gizli anahtar ayarlanmamis. config.yml dosyasini duzenleyin.");
        } else {
            startHeartbeat();
            startDemoOrderPolling();
        }
        getLogger().info("AxoBridge etkin. Web API baglantisi hazirlaniyor.");
    }

    private void loadSettings() {
        baseUrl = getConfig().getString("api.base-url", "").replaceAll("/+$", "");
        apiKey = getConfig().getString("api.api-key", "");
        displayName = getConfig().getString("server.display-name", "Minecraft");
        intervalSeconds = Math.max(30, getConfig().getInt("api.heartbeat-interval-seconds", 60));
    }

    private HttpRequest.Builder authenticatedRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
    }

    private void startHeartbeat() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("serverName", displayName);
            payload.addProperty("minecraftVersion", Bukkit.getMinecraftVersion());
            payload.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
            payload.addProperty("maxPlayers", Bukkit.getMaxPlayers());
            HttpRequest request = authenticatedRequest("/api/bridge/heartbeat")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            lastResult = "Bagli - son kontrol basarili.";
                        } else {
                            lastResult = "API hata kodu: " + response.statusCode();
                            getLogger().warning("AxoBridge API cevap kodu: " + response.statusCode());
                        }
                    }).exceptionally(error -> {
                        lastResult = "Baglanti hatasi: " + error.getClass().getSimpleName();
                        getLogger().warning("AxoBridge API baglantisi kurulamadi: " + error.getMessage());
                        return null;
                    });
        }, 20L, intervalSeconds * 20L);
    }

    private void startDemoOrderPolling() {
        // Poll every 15 seconds. HTTP work stays off the Minecraft main thread.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollDemoOrder, 100L, 300L);
    }

    private void pollDemoOrder() {
        if (!orderPollInFlight.compareAndSet(false, true)) return;
        try {
            HttpRequest request = authenticatedRequest("/api/bridge/orders/next").GET().build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) return;
                        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (!root.has("order") || root.get("order").isJsonNull()) return;
                        JsonObject order = root.getAsJsonObject("order");
                        String id = order.get("id").getAsString();
                        String username = order.get("username").getAsString();
                        String product = order.get("product").getAsString();
                        String duration = order.get("duration").getAsString();
                        if (!PLAYER_NAME.matcher(username).matches()
                                || !"vip+".equals(product) || !"30d".equals(duration)) {
                            acknowledgeOrder(id, false);
                            return;
                        }
                        Bukkit.getScheduler().runTask(this, () -> {
                            // Only a fixed, validated LuckPerms command can be executed.
                            String command = "lp user " + username + " parent addtemp vip+ 30d";
                            boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                            getLogger().info("Demo VIP+ order " + id + " for " + username
                                    + " dispatched=" + dispatched);
                            acknowledgeOrder(id, dispatched);
                        });
                    }).exceptionally(error -> {
                        getLogger().warning("Demo siparis kontrolu basarisiz: " + error.getMessage());
                        return null;
                    }).whenComplete((ignored, error) -> orderPollInFlight.set(false));
        } catch (Exception ex) {
            orderPollInFlight.set(false);
            getLogger().warning("Demo siparis kontrolu baslatilamadi: " + ex.getMessage());
        }
    }

    private void acknowledgeOrder(String id, boolean success) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        HttpRequest request = authenticatedRequest("/api/bridge/orders/" + id + "/ack")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        getLogger().warning("Demo siparis sonucu API'ye bildirilemedi: " + response.statusCode());
                    }
                }).exceptionally(error -> {
                    getLogger().warning("Demo siparis sonucu gonderilemedi: " + error.getMessage());
                    return null;
                });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§bAxoBridge §7» §f" + lastResult);
            sender.sendMessage("§7API: §f" + (baseUrl.isBlank() ? "Ayarlanmamis" : baseUrl));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            sender.sendMessage("§bAxoBridge §7» §aAyarlar yeniden yuklendi. Plugin'i yeniden baslatman gerekebilir.");
            return true;
        }
        sender.sendMessage("§bKullanim: §f/axobridge status");
        return true;
    }

    @Override
    public void onDisable() {
        getLogger().info("AxoBridge kapatildi.");
    }
}
