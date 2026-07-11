package com.discord.musicbot;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.lyrics.KaraokeManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NowPlayingApiServer {
    private static final Logger logger = LoggerFactory.getLogger(NowPlayingApiServer.class);
    private final HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NowPlayingApiServer(int port) throws IOException {
        // Bind to 127.0.0.1 (local only)
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/nowplaying", new NowPlayingHandler());
        this.server.setExecutor(null); // default executor
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private class NowPlayingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }

                // Parse query parameters
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
                String guildIdStr = params.get("guildId");
                if (guildIdStr == null || guildIdStr.trim().isEmpty()) {
                    sendResponse(exchange, 400, "Missing guildId query parameter");
                    return;
                }

                long guildId;
                try {
                    guildId = Long.parseLong(guildIdStr.trim());
                } catch (NumberFormatException e) {
                    sendResponse(exchange, 400, "Invalid guildId format");
                    return;
                }

                MusicManager mm = PlayerManager.getInstance().getMusicManager(guildId);
                if (mm == null) {
                    ObjectNode responseJson = objectMapper.createObjectNode();
                    responseJson.put("playing", false);
                    sendJsonResponse(exchange, 200, responseJson);
                    return;
                }

                AudioTrack current = mm.getPlayer().getPlayingTrack();
                if (current == null) {
                    ObjectNode responseJson = objectMapper.createObjectNode();
                    responseJson.put("playing", false);
                    sendJsonResponse(exchange, 200, responseJson);
                    return;
                }

                // Ensure lyrics are fetched
                mm.ensureLyricsFetched(current);

                // Build JSON response
                ObjectNode responseJson = objectMapper.createObjectNode();
                responseJson.put("playing", true);
                responseJson.put("title", current.getInfo().title);
                responseJson.put("artist", current.getInfo().author);
                responseJson.put("durationMs", current.getDuration());
                responseJson.put("positionMs", current.getPosition());
                responseJson.put("paused", mm.getPlayer().isPaused());
                responseJson.put("fetchingLyrics", mm.isFetchingLyrics());

                ArrayNode lyricsArray = responseJson.putArray("lyrics");
                List<KaraokeManager.LrcLine> lines = mm.getKaraokeLines();
                if (lines != null) {
                    for (KaraokeManager.LrcLine line : lines) {
                        ObjectNode lineNode = objectMapper.createObjectNode();
                        lineNode.put("timeMs", line.timestampMs);
                        lineNode.put("text", line.text);
                        lyricsArray.add(lineNode);
                    }
                }

                sendJsonResponse(exchange, 200, responseJson);
            } catch (Exception e) {
                logger.error("Error handling /nowplaying request", e);
                sendResponse(exchange, 400, "Bad Request: " + e.getMessage());
            }
        }

        private Map<String, String> parseQueryParams(String query) {
            Map<String, String> result = new HashMap<>();
            if (query == null || query.isEmpty()) {
                return result;
            }
            for (String param : query.split("&")) {
                String[] entry = param.split("=");
                if (entry.length > 1) {
                    result.put(entry[0], entry[1]);
                } else if (entry.length == 1) {
                    result.put(entry[0], "");
                }
            }
            return result;
        }

        private void sendJsonResponse(HttpExchange exchange, int statusCode, ObjectNode jsonNode) throws IOException {
            byte[] responseBytes = objectMapper.writeValueAsBytes(jsonNode);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
            byte[] responseBytes = responseText.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
