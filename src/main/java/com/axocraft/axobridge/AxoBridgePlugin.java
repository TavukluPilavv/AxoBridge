package com.axocraft.axobridge;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class AxoBridgePlugin extends JavaPlugin {
    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;
    private String displayName;
    private int intervalSeconds;
    private volatile String lastResult = "Henüz bağlantı denenmedi.";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (apiKey.isBlank() || apiKey.equals("PASTE_YOUR_PRIVATE_SERVER_KEY_HERE")
                || baseUrl.contains("YOUR-AXOBRIDGE-API")) {
            getLogger().warning("API adresi veya gizli anahtar ayarlanmamis. config.yml dosyasini duzenleyin.");
        } else {
            startHeartbeat();
        }

        getLogger().info("AxoBridge etkin. Web API baglantisi hazirlaniyor.");
    }

    private void loadSettings() {
        baseUrl = getConfig().getString("api.base-url", "").replaceAll("/+$", "");
        apiKey = getConfig().getString("api.api-key", "");
        displayName = getConfig().getString("server.display-name", "Minecraft");
        intervalSeconds = Math.max(30, getConfig().getInt("api.heartbeat-interval-seconds", 60));
    }

    private void startHeartbeat() {
        long ticks = intervalSeconds * 20L;
        // Bukkit bilgileri ana sunucu thread'inde okunur; HTTP istegi ayri thread'de gonderilir.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("serverName", displayName);
            payload.addProperty("minecraftVersion", Bukkit.getMinecraftVersion());
            payload.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
            payload.addProperty("maxPlayers", Bukkit.getMaxPlayers());

            sendHeartbeat(payload);
        }, 20L, ticks);
    }

    private void sendHeartbeat(JsonObject payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/bridge/heartbeat"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            lastResult = "Bagli - son kontrol basarili.";
                        } else {
                            lastResult = "API hata kodu: " + response.statusCode();
                            getLogger().warning("AxoBridge API cevap kodu: " + response.statusCode());
                        }
                    })
                    .exceptionally(error -> {
                        lastResult = "Baglanti hatasi: " + error.getClass().getSimpleName();
                        getLogger().warning("AxoBridge API baglantisi kurulamadi: " + error.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException ex) {
            lastResult = "API adresi gecersiz.";
            getLogger().warning(lastResult);
        }
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
