package emma;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executors;

public class MCxDiscord extends JavaPlugin implements Listener {

    private String webhookUrl;
    private String authToken;
    private HttpServer server;
    private int httpPort;

    private String lastPlayer = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        FileConfiguration config = getConfig();

        webhookUrl = config.getString("webhook", "");
        if (webhookUrl.isEmpty()) {
            getLogger().severe("Webhook URL not set in config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        httpPort = config.getInt("port", 8080);

        authToken = config.getString("token", "");
        if (authToken.isEmpty()) {
            authToken = generateRandomToken(64);
            config.set("token", authToken);
            saveConfig();
            getLogger().info("Generated new auth token: " + authToken);
        }

        getServer().getPluginManager().registerEvents(this, this);

        startHttpServer();

        getLogger().info("MCxDiscord enabled. Webhook: " + webhookUrl + " | HTTP Port: " + httpPort);
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(httpPort), 0);
            // removed /send
            server.createContext("/link", new LinkHandler());   // new /link endpoint
            server.createContext("/chat", new ChatHandler());
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            getLogger().info("HTTP server started on port " + httpPort);
        } catch (IOException e) {
            getLogger().severe("Failed to start HTTP server: " + e.getMessage());
        }
    }

    private class LinkHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr);
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) body.append(line);
            br.close();

            Map<String, String> params = parseForm(body.toString());
            String token = params.get("token");

            if (token == null || !token.equals(authToken)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            exchange.sendResponseHeaders(200, -1);
        }
    }

    private class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr);
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) body.append(line);
            br.close();

            Map<String, String> params = parseForm(body.toString());
            String token = params.get("token");
            String user = params.get("user");
            String msg = params.get("msg");

            if (token == null || !token.equals(authToken)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            if (user == null || msg == null || msg.isEmpty()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            Bukkit.getScheduler().runTask(MCxDiscord.this, () -> {
                Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Discord] " + ChatColor.RESET + user + ": " + msg);
            });

            exchange.sendResponseHeaders(200, -1);
        }
    }

    private Map<String, String> parseForm(String formData) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String playerName = event.getPlayer().getName();
        String msg = event.getMessage();

        event.setCancelled(true);

        Bukkit.getScheduler().runTask(this, () -> {
            String toSend;

            if (playerName.equals(lastPlayer)) {
                toSend = "> " + msg;
            } else {
                toSend = ChatColor.GREEN + "[" + playerName + "]" + ChatColor.RESET + "\n> " + msg;
            }

            lastPlayer = playerName;

            Bukkit.broadcastMessage(toSend);

            sendToDiscord(toSend);
        });
    }

    private void sendToDiscord(String message) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                String cleanMessage = stripColorCodes(message);
                String jsonPayload = "{\"content\":\"" + escapeJson(cleanMessage) + "\"}";

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode != 204) {
                    getLogger().warning("Discord webhook returned HTTP " + responseCode);
                }

                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("Failed to send message to Discord: " + e.getMessage());
            }
        });
    }

    private String stripColorCodes(String text) {
        return text.replaceAll("§.", "");
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
// for some reason the token generator doesnt work
// not like it matters THAT much
    private String generateRandomToken(int length) {
        final String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i=0; i<length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
