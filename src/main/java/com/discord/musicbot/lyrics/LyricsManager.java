package com.discord.musicbot.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class LyricsManager {
    private static final Logger logger = LoggerFactory.getLogger(LyricsManager.class);
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String LRCLIB_URL = "https://lrclib.net/api/search?q=";
    private static final String GENIUS_URL = "https://api.genius.com/search?q=";
    private static final java.util.regex.Pattern LRC_TIMESTAMP_PATTERN = java.util.regex.Pattern.compile("\\[\\d{2}:\\d{2}(\\.\\d{2,3})?\\]\\s*");

    private static String geniusToken;

    static {
        try {
            Dotenv dotenv = Dotenv.load();
            geniusToken = dotenv.get("GENIUS_ACCESS_TOKEN");
            if (geniusToken == null || geniusToken.isBlank()) {
                geniusToken = dotenv.get("GENIUS_API_KEY");
            }
        } catch (Exception e) {
            logger.warn("Could not load Dotenv for Genius API key.");
        }
    }

    public static class LyricsResult {
        public String text;
        public String source;
        public boolean isLive;

        public LyricsResult(String text, String source, boolean isLive) {
            this.text = text;
            this.source = source;
            this.isLive = isLive;
        }
    }

    public static CompletableFuture<LyricsResult> fetchLyrics(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Try LRCLIB for live (synced) lyrics
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest lrcReq = HttpRequest.newBuilder()
                        .uri(URI.create(LRCLIB_URL + encodedQuery))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .header("User-Agent", "DiscordMusicBot (https://github.com/Sharon-ctl/Melora-Java)")
                        .build();

                HttpResponse<String> lrcRes = httpClient.send(lrcReq, HttpResponse.BodyHandlers.ofString());
                if (lrcRes.statusCode() == 200) {
                    JsonNode array = mapper.readTree(lrcRes.body());
                    if (array.isArray() && array.size() > 0) {
                        JsonNode bestMatch = array.get(0);
                        String synced = bestMatch.hasNonNull("syncedLyrics") ? bestMatch.get("syncedLyrics").asText() : null;
                        String plain = bestMatch.hasNonNull("plainLyrics") ? bestMatch.get("plainLyrics").asText() : null;
                        boolean hasSynced = synced != null && !synced.isBlank();

                        if (plain != null && !plain.isBlank()) {
                            return new LyricsResult(plain, "LRCLIB", hasSynced);
                        } else if (hasSynced) {
                            String stripped = LRC_TIMESTAMP_PATTERN.matcher(synced).replaceAll("");
                            return new LyricsResult(stripped, "LRCLIB", true);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error fetching from LRCLIB", e);
            }

            // 2. Fallback to Genius
            try {
                if (geniusToken == null || geniusToken.isBlank()) {
                    return null; // Cannot fetch from Genius without a token
                }

                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest geniusReq = HttpRequest.newBuilder()
                        .uri(URI.create(GENIUS_URL + encodedQuery))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + geniusToken)
                        .header("User-Agent", "DiscordMusicBot")
                        .build();

                HttpResponse<String> geniusRes = httpClient.send(geniusReq, HttpResponse.BodyHandlers.ofString());
                if (geniusRes.statusCode() == 200) {
                    JsonNode root = mapper.readTree(geniusRes.body());
                    JsonNode hits = root.path("response").path("hits");
                    if (hits.isArray() && hits.size() > 0) {
                        String url = hits.get(0).path("result").path("url").asText();
                        String scraped = scrapeGeniusLyrics(url);
                        if (scraped != null && !scraped.isBlank()) {
                            return new LyricsResult(scraped, "Genius", false);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error fetching from Genius", e);
            }
            return null;
        }, com.discord.musicbot.audio.PlayerManager.ioExecutor);
    }

    public static CompletableFuture<String> fetchSyncedLyrics(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest lrcReq = HttpRequest.newBuilder()
                        .uri(URI.create(LRCLIB_URL + encodedQuery))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .header("User-Agent", "DiscordMusicBot")
                        .build();

                HttpResponse<String> lrcRes = httpClient.send(lrcReq, HttpResponse.BodyHandlers.ofString());
                if (lrcRes.statusCode() == 200) {
                    JsonNode array = mapper.readTree(lrcRes.body());
                    if (array.isArray() && array.size() > 0) {
                        JsonNode bestMatch = array.get(0);
                        return bestMatch.hasNonNull("syncedLyrics") ? bestMatch.get("syncedLyrics").asText() : null;
                    }
                }
            } catch (Exception e) {
                logger.error("Error fetching synced lyrics from LRCLIB", e);
            }
            return null;
        }, com.discord.musicbot.audio.PlayerManager.ioExecutor);
    }

    private static String scrapeGeniusLyrics(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .timeout(5000)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .get();

            Elements containers = doc.select("div[data-lyrics-container=true]");
            if (containers.isEmpty()) return null;

            StringBuilder lyrics = new StringBuilder();
            for (Element el : containers) {
                // Convert <br> to newlines before getting text
                el.select("br").append("\\n");
                el.select("p").prepend("\\n\\n");
                String text = el.text().replace("\\n", "\n").replaceAll("\n ", "\n");
                lyrics.append(text).append("\n\n");
            }
            return lyrics.toString().trim();
        } catch (Exception e) {
            logger.error("Error scraping genius HTML", e);
            return null;
        }
    }

}
