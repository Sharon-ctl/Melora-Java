package com.discord.musicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.discord.musicbot.commands.framework.EmbedHelper;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * PlayerManager - Singleton managing audio playback across all guilds.
 */
public class PlayerManager {

    private static final Logger logger = LoggerFactory.getLogger(PlayerManager.class);
    private static PlayerManager INSTANCE;
    private static final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    private final DefaultAudioPlayerManager playerManager;
    private final Map<Long, MusicManager> musicManagers;

    public static final java.util.concurrent.ExecutorService ioExecutor = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2), r -> {
        Thread t = new Thread(r, "PlayerManager-IO");
        t.setDaemon(true);
        return t;
    });
    public static final java.util.concurrent.ScheduledExecutorService scheduledExecutor = java.util.concurrent.Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "PlayerManager-Scheduled");
        t.setDaemon(true);
        return t;
    });
    public static volatile boolean isShuttingDown = false;

    private PlayerManager() {
        this.musicManagers = new ConcurrentHashMap<>();
        this.playerManager = new DefaultAudioPlayerManager();

        playerManager.getConfiguration()
                .setOutputFormat(com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats.DISCORD_PCM_S16_BE);

        playerManager.getConfiguration().setResamplingQuality(
                com.sedmelluq.discord.lavaplayer.player.AudioConfiguration.ResamplingQuality.HIGH);
        playerManager.getConfiguration().setOpusEncodingQuality(10);
        playerManager.getConfiguration().setFilterHotSwapEnabled(true);
        playerManager.setFrameBufferDuration(1000);

        // --- Register YouTube Source (v2) with all available resilient clients ---
        YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(true,
                new dev.lavalink.youtube.clients.Tv(),
                new dev.lavalink.youtube.clients.AndroidVr(),
                new dev.lavalink.youtube.clients.Music(),
                new dev.lavalink.youtube.clients.Ios(),
                new dev.lavalink.youtube.clients.Android(),
                new dev.lavalink.youtube.clients.MWeb(),
                new dev.lavalink.youtube.clients.Web(),
                new dev.lavalink.youtube.clients.AndroidMusic(),
                new dev.lavalink.youtube.clients.TvHtml5Simply(),
                new dev.lavalink.youtube.clients.WebEmbedded());
        
        try {
            io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.load();
            String oauthToken = dotenv.get("YOUTUBE_OAUTH2_TOKEN");
            
            if (oauthToken != null && !oauthToken.isEmpty()) {
                youtube.useOauth2(oauthToken, true);
                logger.info("YouTube OAuth2 token loaded! You should not experience any 403 errors.");
            } else {
                logger.warn("No YOUTUBE_OAUTH2_TOKEN found. The bot will use OAuth2 device authorization.");
                logger.warn("CHECK THE CONSOLE BELOW for a Google Device Login code to authorize the bot!");
                youtube.useOauth2(null, false);
            }

            String poToken = dotenv.get("YOUTUBE_PO_TOKEN");
            String visitorData = dotenv.get("YOUTUBE_VISITOR_DATA");
            if (poToken != null && !poToken.isEmpty() && visitorData != null && !visitorData.isEmpty()) {
                dev.lavalink.youtube.clients.Web.setPoTokenAndVisitorData(poToken, visitorData);
                logger.info("YouTube PO Token and Visitor Data injected successfully. Bypassing age restrictions!");
            } else {
                logger.info("No PO Token provided. Age-restricted and kid-focused videos may fail.");
            }

            String ipv6Block = dotenv.get("IPV6_BLOCK");
            if (ipv6Block != null && !ipv6Block.isEmpty()) {
                try {
                    com.sedmelluq.lava.extensions.youtuberotator.planner.NanoIpRoutePlanner planner = 
                        new com.sedmelluq.lava.extensions.youtuberotator.planner.NanoIpRoutePlanner(
                            java.util.Collections.singletonList(new com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv6Block(ipv6Block)), true);
                    
                    youtube.getHttpInterfaceManager().configureBuilder(b -> b.setRoutePlanner(planner));
                    logger.info("IPv6 Rotation successfully configured with block: {}", ipv6Block);
                } catch (Exception ex) {
                    logger.warn("Failed to configure IPv6 Rotation. Ensure IPV6_BLOCK is a valid CIDR.", ex);
                }
            } else {
                logger.info("No IPV6_BLOCK provided. IPv6 rotation is disabled.");
            }

        } catch (Exception e) {
            logger.warn("Could not configure YouTube OAuth2/PO Token/IPv6. Error: {}", e.getMessage());
        }

        playerManager.registerSourceManager(youtube);
        logger.info("YouTube source (v2) registered successfully");

        @SuppressWarnings({"deprecation", "unchecked"})
        Class<? extends com.sedmelluq.discord.lavaplayer.source.AudioSourceManager> deprecatedYoutubeClass =
                (Class<? extends com.sedmelluq.discord.lavaplayer.source.AudioSourceManager>)
                (Class<?>) com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class;
        com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers.registerRemoteSources(playerManager, deprecatedYoutubeClass);
        com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers.registerLocalSource(playerManager);

        refreshSpotifyTokenAsync();
        logger.info("PlayerManager initialized (Spotify Credentials removed, using URL fetcher fallback)");
    }

    public static synchronized PlayerManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PlayerManager();
        }
        return INSTANCE;
    }

    public DefaultAudioPlayerManager getPlayerManager() {
        return playerManager;
    }

    public MusicManager getMusicManager(Guild guild) {
        return musicManagers.computeIfAbsent(guild.getIdLong(), (guildId) -> {
            MusicManager musicManager = new MusicManager(playerManager, guild);
            guild.getAudioManager().setSendingHandler(musicManager.getSendHandler());
            return musicManager;
        });
    }

    public MusicManager getMusicManager(long guildId) {
        return musicManagers.get(guildId);
    }

    public void removeMusicManager(long guildId) {
        musicManagers.remove(guildId);
    }

    public void shutdown() {
        shutdown(null);
    }

    public void shutdown(net.dv8tion.jda.api.JDA jda) {
        isShuttingDown = true;
        logger.info("PlayerManager initiating robust shutdown across {} active sessions...", musicManagers.size());

        // 1. Force save all session snapshots immediately before any destruction or connection closing
        for (MusicManager manager : musicManagers.values()) {
            try {
                com.discord.musicbot.data.SessionManager.getInstance().updateSnapshot(manager.getGuild().getId(), manager.toSessionSnapshot());
            } catch (Exception e) {
                logger.warn("Failed to update snapshot for guild {} during pre-shutdown: {}", manager.getGuild().getId(), e.getMessage());
            }
        }
        try {
            com.discord.musicbot.data.SessionManager.getInstance().saveAllNow();
            logger.info("All session activity and queues flushed to disk successfully.");
        } catch (Exception e) {
            logger.error("Failed to flush SessionManager during shutdown", e);
        }

        // 2. Perform cleanup and voice disconnection for each manager
        java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (MusicManager manager : musicManagers.values()) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    manager.cleanup();
                } catch (Exception e) {
                    logger.error("Error during MusicManager cleanup for guild {}", manager.getGuild().getId(), e);
                }
            }, ioExecutor));
        }

        try {
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Shutdown cleanup timed out or was interrupted", e);
        }

        // 3. Guarantee bot disconnects from all voice channels across every single guild
        if (jda != null) {
            logger.info("Disconnecting bot from all voice channels across every guild...");
            for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
                try {
                    if (guild.getAudioManager().isConnected() || (guild.getSelfMember().getVoiceState() != null && guild.getSelfMember().getVoiceState().inAudioChannel())) {
                        guild.getAudioManager().closeAudioConnection();
                        guild.getJDA().getDirectAudioController().disconnect(guild);
                        var vs = guild.getSelfMember().getVoiceState();
                        if (vs != null && vs.getChannel() instanceof net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel vc) {
                            try { vc.modifyStatus("").queue(null, e -> {}); } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        musicManagers.clear();
        playerManager.shutdown();
        ioExecutor.shutdown();
        scheduledExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
            if (!scheduledExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            scheduledExecutor.shutdownNow();
        }
    }

    public int getActivePlayers() {
        return (int) musicManagers.values().stream()
                .filter(mm -> mm.getPlayer().getPlayingTrack() != null)
                .count();
    }

    private String escapeMarkdown(String text) {
        return text.replaceAll("([*_`~>|])", "\\\\$1");
    }

    private String formatTime(long duration) {
        long hours = duration / 3600000;
        long minutes = (duration / 60000) % 60;
        long seconds = (duration / 1000) % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    public record SpotifyMetadata(String query, String title, String artist, String artworkUrl, long duration, String spotifyUrl) {}
    private record SpotifyPlaylistResult(String name, List<SpotifyMetadata> tracks) {}

    private volatile String cachedSpotifyToken = null;
    private volatile long spotifyTokenExpiry = 0;

    private void refreshSpotifyTokenAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                String token = getAnonymousSpotifyToken("4cOdK2wGLETKBW3PvgPWqT", "track");
                if (token != null) {
                    cachedSpotifyToken = token;
                    spotifyTokenExpiry = System.currentTimeMillis() + (20 * 60 * 1000L);
                    logger.info("Successfully refreshed anonymous Spotify token in background.");
                }
            } catch (Exception e) {
                logger.warn("Background Spotify token refresh failed: {}", e.getMessage());
            }
        }, ioExecutor);
    }

    private synchronized String getCachedSpotifyToken() {
        if (cachedSpotifyToken == null || System.currentTimeMillis() > spotifyTokenExpiry) {
            String token = getAnonymousSpotifyToken("4cOdK2wGLETKBW3PvgPWqT", "track");
            if (token != null) {
                cachedSpotifyToken = token;
                spotifyTokenExpiry = System.currentTimeMillis() + (20 * 60 * 1000L);
            }
        }
        return cachedSpotifyToken;
    }

    public CompletableFuture<List<SpotifyMetadata>> searchSpotify(String query) {
        return CompletableFuture.supplyAsync(() -> {
            List<SpotifyMetadata> results = new ArrayList<>();
            try {
                java.net.http.HttpClient client = httpClient;
                String token = getCachedSpotifyToken();
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (token != null) {
                        try {
                            String apiUrl = "https://api.spotify.com/v1/search?q="
                                    + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                                    + "&type=track&limit=25";
                            java.net.http.HttpRequest apiReq = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(apiUrl))
                                    .header("Authorization", "Bearer " + token)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                    .timeout(java.time.Duration.ofMillis(2000))
                                    .GET()
                                    .build();
                            java.net.http.HttpResponse<String> apiResp = client.send(apiReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                            if (apiResp.statusCode() == 200) {
                                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(apiResp.body());
                                com.fasterxml.jackson.databind.JsonNode items = root.path("tracks").path("items");
                                if (items.isArray()) {
                                    String lowerQuery = query.toLowerCase();
                                    boolean wantsCover = lowerQuery.contains("cover") || lowerQuery.contains("tribute") || lowerQuery.contains("karaoke") || lowerQuery.contains("remix") || lowerQuery.contains("instrumental") || lowerQuery.contains("8-bit") || lowerQuery.contains("lullaby") || lowerQuery.contains("kidz bop");
                                    for (com.fasterxml.jackson.databind.JsonNode item : items) {
                                        String id = item.path("id").asText("");
                                        String title = item.path("name").asText("");
                                        StringBuilder artists = new StringBuilder();
                                        com.fasterxml.jackson.databind.JsonNode arr = item.path("artists");
                                        if (arr.isArray()) {
                                            for (int i = 0; i < arr.size(); i++) {
                                                if (i > 0) artists.append(", ");
                                                artists.append(arr.get(i).path("name").asText(""));
                                            }
                                        }
                                        String combinedLower = (title + " " + artists).toLowerCase();
                                        if (!wantsCover && (combinedLower.contains("cover") || combinedLower.contains("tribute") || combinedLower.contains("karaoke") || combinedLower.contains("kidz bop") || combinedLower.contains("8-bit") || combinedLower.contains("lullaby") || combinedLower.contains("instrumental"))) {
                                            continue;
                                        }
                                        long duration = item.path("duration_ms").asLong(0);
                                        String artwork = null;
                                        com.fasterxml.jackson.databind.JsonNode imgs = item.path("album").path("images");
                                        if (imgs.isArray() && imgs.size() > 0) {
                                            artwork = imgs.get(0).path("url").asText(null);
                                        }
                                        String spotifyUrl = !id.isEmpty() ? "https://open.spotify.com/track/" + id : null;
                                        if (!title.isEmpty() && duration > 0) {
                                            results.add(new SpotifyMetadata("ytmsearch:" + cleanTrackTitle(title) + " " + cleanTrackTitle(artists.toString()), cleanTrackTitle(title), cleanTrackTitle(artists.toString()), artwork, duration, spotifyUrl));
                                        }
                                    }
                                }
                                break;
                            } else if (apiResp.statusCode() == 401 || apiResp.statusCode() == 429) {
                                cachedSpotifyToken = null;
                                spotifyTokenExpiry = 0;
                                if (attempt == 0) {
                                    token = getCachedSpotifyToken();
                                    continue;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    break;
                }

                if (results.isEmpty()) {
                    String url = "https://itunes.apple.com/search?term="
                            + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                            + "&entity=song&limit=25";
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .timeout(java.time.Duration.ofMillis(2000))
                            .GET()
                            .build();
                    java.net.http.HttpResponse<String> response = client.send(request,
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    String body = response.body();

                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
                    com.fasterxml.jackson.databind.JsonNode items = root.path("results");
                    if (items.isArray()) {
                        String lowerQuery = query.toLowerCase();
                        boolean wantsCover = lowerQuery.contains("cover") || lowerQuery.contains("tribute") || lowerQuery.contains("karaoke") || lowerQuery.contains("remix") || lowerQuery.contains("instrumental") || lowerQuery.contains("8-bit") || lowerQuery.contains("lullaby") || lowerQuery.contains("kidz bop");
                        for (com.fasterxml.jackson.databind.JsonNode item : items) {
                            String title = item.path("trackName").asText("");
                            String artist = item.path("artistName").asText("");
                            String combinedLower = (title + " " + artist).toLowerCase();
                            if (!wantsCover && (combinedLower.contains("cover") || combinedLower.contains("tribute") || combinedLower.contains("karaoke") || combinedLower.contains("kidz bop") || combinedLower.contains("8-bit") || combinedLower.contains("lullaby") || combinedLower.contains("instrumental"))) {
                                continue;
                            }
                            long duration = item.path("trackTimeMillis").asLong(0);
                            String artwork = item.path("artworkUrl100").asText(null);
                            if (artwork != null) {
                                artwork = artwork.replace("100x100bb.jpg", "600x600bb.jpg");
                            }
                            if (!title.isEmpty() && duration > 0 && results.size() < 25) {
                                String cleanTitle = cleanTrackTitle(title);
                                String cleanArtist = cleanTrackTitle(artist);
                                String fallbackSpotifyUrl = "https://open.spotify.com/search/" + java.net.URLEncoder.encode(cleanTitle + " " + cleanArtist, java.nio.charset.StandardCharsets.UTF_8);
                                results.add(new SpotifyMetadata("ytmsearch:" + cleanTitle + " " + cleanArtist, cleanTitle, cleanArtist, artwork, duration, fallbackSpotifyUrl));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("searchSpotify fallback error: " + e.getMessage());
            }
            return results;
        }, ioExecutor);
    }

    public static String cleanTrackTitle(String title) {
        if (title == null || title.isBlank()) return "";
        String cleaned = title.replaceAll("(?i)<yts>|\\(yts\\)|\\[yts\\]|\\byts\\b", "")
                .replaceAll("(?i)\\b(official\\s*music\\s*video|official\\s*video|official\\s*audio|lyric\\s*video|lyrics|audio|video|hq|hd|4k|live|remastered|visualizer)\\b", "")
                .replaceAll("[\\[\\]()<>{}]+", "")
                .replaceAll("\\s+-\\s+$", "")
                .replaceAll("(?i)\\s*-\\s*topic$", "")
                .replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    public static String extractSpotifySearchQuery(String url) {
        try {
            if (url.contains("/search/")) {
                String encoded = url.substring(url.indexOf("/search/") + 8);
                if (encoded.contains("?")) {
                    encoded = encoded.substring(0, encoded.indexOf("?"));
                }
                return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8).trim();
            } else if (url.contains("/search")) {
                java.net.URI uri = java.net.URI.create(url);
                String query = uri.getQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if (pair.length > 1 && (pair[0].equals("q") || pair[0].equals("query"))) {
                            return java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8).trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public CompletableFuture<List<AudioTrack>> searchYouTube(String query) {
        CompletableFuture<List<AudioTrack>> future = new CompletableFuture<>();
        String searchQuery = query.startsWith("ytmsearch:") ? query : "ytmsearch:" + query;
        CompletableFuture.runAsync(() -> {
            try {
                playerManager.loadItemOrdered(this, searchQuery, new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        future.complete(List.of(track));
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        future.complete(playlist.getTracks());
                    }

                    @Override
                    public void noMatches() {
                        future.complete(new ArrayList<>());
                    }

                    @Override
                    public void loadFailed(FriendlyException exception) {
                        future.complete(new ArrayList<>());
                    }
                });
            } catch (Exception e) {
                future.complete(new ArrayList<>());
            }
        }, ioExecutor);
        return future;
    }

    public static com.sedmelluq.discord.lavaplayer.track.AudioTrack matchSpotifyToYoutube(
            List<com.sedmelluq.discord.lavaplayer.track.AudioTrack> candidates, String targetTitle, String targetArtist, long targetDurationMs) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (targetDurationMs <= 0 || targetTitle == null || targetTitle.isEmpty()) {
            return candidates.get(0);
        }

        com.sedmelluq.discord.lavaplayer.track.AudioTrack bestTrack = candidates.get(0);
        int bestScore = Integer.MIN_VALUE;

        String lowerTitle = targetTitle.toLowerCase();
        String lowerArtist = targetArtist != null ? targetArtist.toLowerCase() : "";

        for (com.sedmelluq.discord.lavaplayer.track.AudioTrack track : candidates) {
            int score = 0;
            long candDuration = track.getDuration();
            long diff = Math.abs(candDuration - targetDurationMs);

            if (diff <= 3000) score += 50;
            else if (diff <= 7000) score += 30;
            else if (diff <= 15000) score += 10;
            else if (diff > 25000) score -= 100;

            String candTitle = cleanTrackTitle(track.getInfo().title).toLowerCase();
            String candAuthor = cleanTrackTitle(track.getInfo().author).toLowerCase();

            if (candTitle.contains(lowerTitle) || lowerTitle.contains(candTitle)) {
                score += 40;
            } else {
                for (String word : lowerTitle.split("\\s+")) {
                    if (word.length() > 2 && candTitle.contains(word)) {
                        score += 10;
                    }
                }
            }
            if (!lowerArtist.isEmpty() && (candAuthor.contains(lowerArtist) || candTitle.contains(lowerArtist))) {
                score += 25;
            }

            String[] negativeWords = {"live", "cover", "instrumental", "loop", "sped up", "slowed", "reverb", "8d"};
            for (String neg : negativeWords) {
                if (candTitle.contains(neg) && !lowerTitle.contains(neg)) {
                    score -= 60;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestTrack = track;
            }
        }
        return bestTrack;
    }

    public void loadSpotifyTrackWithFallback(MusicManager musicManager, SpotifyMetadata meta, com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler handler) {
        String query = meta.query() != null ? meta.query().replace("ytsearch:", "ytmsearch:") : "ytmsearch:" + cleanTrackTitle(meta.title()) + " " + cleanTrackTitle(meta.artist() != null ? meta.artist() : "");
        String uri = (meta.spotifyUrl() != null && (meta.spotifyUrl().contains("spotify.com") || meta.spotifyUrl().startsWith("http")))
                ? meta.spotifyUrl()
                : "https://open.spotify.com/search/" + java.net.URLEncoder.encode(cleanTrackTitle(meta.title()) + " " + cleanTrackTitle(meta.artist() != null ? meta.artist() : ""), java.nio.charset.StandardCharsets.UTF_8);
        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo spotifyInfo = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                cleanTrackTitle(meta.title()), cleanTrackTitle(meta.artist() != null ? meta.artist() : "Spotify"), meta.duration(), "spotify", false, uri);

        loadItemWithFallback(musicManager, query, new com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler() {
            @Override
            public void trackLoaded(com.sedmelluq.discord.lavaplayer.track.AudioTrack track) {
                SpotifyResolvedTrack wrapped = new SpotifyResolvedTrack(spotifyInfo, track, meta.artworkUrl());
                handler.trackLoaded(wrapped);
            }

            @Override
            public void playlistLoaded(com.sedmelluq.discord.lavaplayer.track.AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    handler.noMatches();
                    return;
                }
                com.sedmelluq.discord.lavaplayer.track.AudioTrack matched = matchSpotifyToYoutube(
                        playlist.getTracks(), meta.title(), meta.artist(), meta.duration());
                if (matched == null) matched = playlist.getTracks().get(0);
                SpotifyResolvedTrack wrapped = new SpotifyResolvedTrack(spotifyInfo, matched, meta.artworkUrl());
                if (playlist.isSearchResult()) {
                    com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist fakePlaylist =
                            new com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist(playlist.getName(), java.util.List.of(wrapped), wrapped, true);
                    handler.playlistLoaded(fakePlaylist);
                } else {
                    handler.trackLoaded(wrapped);
                }
            }

            @Override
            public void noMatches() {
                handler.noMatches();
            }

            @Override
            public void loadFailed(com.sedmelluq.discord.lavaplayer.tools.FriendlyException exception) {
                handler.loadFailed(exception);
            }
        });
    }

    private CompletableFuture<SpotifyMetadata> fetchSpotifyMetadata(String url) {
        if (url == null || (!url.contains("/track/") && !url.contains("/episode/"))) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.net.http.HttpClient client = httpClient;
                String trackId = url.split("\\?")[0].replaceAll(".*/track/", "");
                String token = getCachedSpotifyToken();
                if (token != null) {
                    try {
                        java.net.http.HttpRequest apiReq = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create("https://api.spotify.com/v1/tracks/" + trackId))
                                .header("Authorization", "Bearer " + token)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .GET()
                                .build();
                        java.net.http.HttpResponse<String> apiResp = client.send(apiReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                        if (apiResp.statusCode() == 200) {
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(apiResp.body());
                            String tTitle = node.path("name").asText("");
                            StringBuilder artists = new StringBuilder();
                            com.fasterxml.jackson.databind.JsonNode arr = node.path("artists");
                            if (arr.isArray()) {
                                for (int i = 0; i < arr.size(); i++) {
                                    if (i > 0) artists.append(", ");
                                    artists.append(arr.get(i).path("name").asText(""));
                                }
                            }
                            long dur = node.path("duration_ms").asLong(0);
                            String art = null;
                            com.fasterxml.jackson.databind.JsonNode imgs = node.path("album").path("images");
                            if (imgs.isArray() && imgs.size() > 0) {
                                art = imgs.get(0).path("url").asText(null);
                            }
                            if (!tTitle.isEmpty() && dur > 0) {
                                return new SpotifyMetadata("ytmsearch:" + cleanTrackTitle(tTitle) + " " + cleanTrackTitle(artists.toString()), cleanTrackTitle(tTitle), cleanTrackTitle(artists.toString()), art, dur, url);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                String html = response.body();

                String title = "";
                String artworkUrl = null;

                java.util.regex.Matcher mTitle = java.util.regex.Pattern
                        .compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(html);
                if (mTitle.find()) {
                    title = mTitle.group(1);
                }

                java.util.regex.Matcher mDesc = java.util.regex.Pattern
                        .compile("<meta property=\"og:description\" content=\"([^\"]+)\"").matcher(html);
                String artist = "Spotify";
                if (mDesc.find()) {
                    String desc = mDesc.group(1);
                    if (desc.contains("·")) {
                        String[] parts = desc.split("·");
                        if (parts.length > 1) {
                            artist = parts[1].trim();
                        }
                    } else {
                        artist = desc;
                    }
                }

                java.util.regex.Matcher mImage = java.util.regex.Pattern
                        .compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html);
                if (mImage.find()) {
                    artworkUrl = mImage.group(1);
                }

                java.util.regex.Matcher mDuration = java.util.regex.Pattern
                        .compile("\"duration_ms\":(\\d+)").matcher(html);
                long duration = 0;
                if (mDuration.find()) {
                    duration = Long.parseLong(mDuration.group(1));
                }

                if (!title.isEmpty()) {
                    return new SpotifyMetadata("ytmsearch:" + cleanTrackTitle(title) + " " + cleanTrackTitle(artist), cleanTrackTitle(title), cleanTrackTitle(artist), artworkUrl, duration, url);
                }
            } catch (Exception e) {
                logger.error("Failed to fetch Spotify URL: " + url, e);
            }
            return null;
        }, ioExecutor);
    }

    /**
     * Gets an anonymous Spotify access token from the embed page.
     * No premium or API credentials needed — this is the same token
     * Spotify's web embed player uses publicly.
     */
    private String getAnonymousSpotifyToken(String spotifyId, String embedType) {
        try {
            java.net.http.HttpRequest req1 = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://open.spotify.com/get_access_token?reason=transport&productType=embed"))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp1 = httpClient.send(req1, java.net.http.HttpResponse.BodyHandlers.ofString());
            java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(resp1.body());
            if (m1.find()) {
                logger.debug("Spotify: Got anonymous token from get_access_token endpoint");
                return m1.group(1);
            }
        } catch (Exception ignored) {}

        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://open.spotify.com/embed/" + embedType + "/" + spotifyId))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = httpClient
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"accessToken\":\"([^\"]+)\"").matcher(response.body());
            if (m.find()) {
                logger.debug("Spotify: Got anonymous embed token");
                return m.group(1);
            }
        } catch (Exception e) {
            logger.error("Failed to get anonymous Spotify token", e);
        }
        return null;
    }

    @FunctionalInterface
    private interface SpotifyProgressiveCallback {
        void onBatch(List<SpotifyMetadata> batch, int totalCount, String playlistName);
    }

    private CompletableFuture<SpotifyPlaylistResult> fetchSpotifyPlaylist(String url) {
        return fetchSpotifyPlaylist(url, null);
    }

    private CompletableFuture<SpotifyPlaylistResult> fetchSpotifyPlaylist(String url, SpotifyProgressiveCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            List<SpotifyMetadata> tracks = new ArrayList<>();
            String playlistName = "Spotify Playlist";
            boolean isAlbum = url.contains("/album/");

            try {
                java.net.http.HttpClient client = httpClient;
                java.net.http.HttpRequest nameReq = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> nameResp = client.send(nameReq,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                String html = nameResp.body();

                // Extract playlist/album name from og:title
                java.util.regex.Matcher nameMatcher = java.util.regex.Pattern
                        .compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(html);
                if (nameMatcher.find()) {
                    playlistName = nameMatcher.group(1);
                }
                String playlistArtwork = null;
                java.util.regex.Matcher imgMatcher = java.util.regex.Pattern
                        .compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html);
                if (imgMatcher.find()) {
                    playlistArtwork = imgMatcher.group(1);
                }

                // Parse initialState for tracks
                java.util.regex.Matcher stateMatcher = java.util.regex.Pattern
                        .compile("<script id=\"initialState\"[^>]*>([A-Za-z0-9+/=]+)</script>")
                        .matcher(html);

                int totalCount = 0;
                if (stateMatcher.find()) {
                    String base64 = stateMatcher.group(1);
                    String json = new String(java.util.Base64.getDecoder().decode(base64),
                            java.nio.charset.StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

                    com.fasterxml.jackson.databind.JsonNode entities = root.path("entities").path("items");
                    if (!entities.isMissingNode()) {
                        java.util.Iterator<String> fieldNames = entities.fieldNames();
                        if (fieldNames.hasNext()) {
                            String entityKey = fieldNames.next();
                            com.fasterxml.jackson.databind.JsonNode entity = entities.get(entityKey);

                            String entityName = entity.path("name").asText("");
                            if (!entityName.isEmpty()) {
                                playlistName = entityName;
                            }

                            com.fasterxml.jackson.databind.JsonNode itemsNode;
                            if (isAlbum) {
                                itemsNode = entity.path("tracksV2").path("items");
                                totalCount = entity.path("tracksV2").path("totalCount").asInt(0);
                            } else {
                                itemsNode = entity.path("content").path("items");
                                totalCount = entity.path("content").path("totalCount").asInt(0);
                            }

                            if (itemsNode.isArray()) {
                                for (com.fasterxml.jackson.databind.JsonNode item : itemsNode) {
                                    try {
                                        com.fasterxml.jackson.databind.JsonNode trackData;
                                        if (isAlbum) {
                                            trackData = item.path("track");
                                        } else {
                                            trackData = item.path("itemV2").path("data");
                                        }
                                        String name = trackData.path("name").asText("");
                                        if (name.isEmpty()) continue;

                                        StringBuilder artistStr = new StringBuilder();
                                        com.fasterxml.jackson.databind.JsonNode artistItems = trackData.path("artists").path("items");
                                        if (!artistItems.isArray() || artistItems.size() == 0) {
                                            artistItems = trackData.path("artists");
                                        }
                                        if (artistItems.isArray()) {
                                            for (int i = 0; i < artistItems.size(); i++) {
                                                com.fasterxml.jackson.databind.JsonNode aNode = artistItems.get(i);
                                                String aName = aNode.path("profile").path("name").asText("");
                                                if (aName.isEmpty()) aName = aNode.path("name").asText("");
                                                if (aName.isEmpty()) aName = aNode.path("data").path("profile").path("name").asText("");
                                                if (!aName.isEmpty()) {
                                                    if (artistStr.length() > 0) artistStr.append(", ");
                                                    artistStr.append(aName);
                                                }
                                            }
                                        }
                                        if (artistStr.length() == 0) artistStr.append("Spotify");

                                        String trackId = trackData.path("id").asText("");
                                        if (trackId.isEmpty()) {
                                            String uri = trackData.path("uri").asText("");
                                            if (uri.startsWith("spotify:track:")) {
                                                trackId = uri.substring("spotify:track:".length());
                                            }
                                        }
                                        String spotifyUrl = !trackId.isEmpty() ? "https://open.spotify.com/track/" + trackId : null;

                                        String artwork = null;
                                        com.fasterxml.jackson.databind.JsonNode images = trackData.path("album").path("images");
                                        if (!images.isArray() || images.size() == 0) {
                                            images = trackData.path("albumOfTrack").path("coverArt").path("sources");
                                        }
                                        if (!images.isArray() || images.size() == 0) {
                                            images = trackData.path("albumOfTrack").path("images");
                                        }
                                        if (!images.isArray() || images.size() == 0) {
                                            images = trackData.path("coverArt").path("sources");
                                        }
                                        if (images.isArray() && images.size() > 0) {
                                            artwork = images.get(0).path("url").asText(null);
                                        }
                                        if (artwork == null || artwork.isEmpty()) {
                                            artwork = playlistArtwork;
                                        }

                                        long duration = trackData.path("duration").path("totalMilliseconds").asLong(0);
                                        if (duration == 0) {
                                            duration = trackData.path("duration_ms").asLong(0);
                                        }

                                        tracks.add(new SpotifyMetadata("ytmsearch:" + cleanTrackTitle(name) + " " + cleanTrackTitle(artistStr.toString()), cleanTrackTitle(name), cleanTrackTitle(artistStr.toString()), artwork, duration, spotifyUrl));
                                    } catch (Exception e) {
                                        logger.debug("Spotify: Failed to parse track item", e);
                                    }
                                }
                            }
                        }
                    }
                }
                if (totalCount == 0) totalCount = tracks.size();

                // Notify callback immediately with initial batch so playback starts instantly
                if (callback != null && !tracks.isEmpty()) {
                    try {
                        callback.onBatch(new ArrayList<>(tracks), totalCount, playlistName);
                    } catch (Exception e) {
                        logger.error("Error in Spotify progressive callback for initial batch", e);
                    }
                }

                // If there are more tracks than what the scraper got, try anonymous Spotify token
                if (totalCount > tracks.size() && tracks.size() < 100) {
                    String id = url.split("\\?")[0].replaceAll(".*/(playlist|album)/", "");
                    String token = getCachedSpotifyToken();
                    int tokenRetries = 0;
                    if (token != null) {
                        try {
                            int offset = tracks.size();
                            int targetCount = Math.min(totalCount, 100);
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            while (offset < targetCount) {
                                String apiUrl = isAlbum
                                        ? "https://api.spotify.com/v1/albums/" + id + "/tracks?limit=50&offset=" + offset
                                        : "https://api.spotify.com/v1/playlists/" + id + "/tracks?limit=100&offset=" + offset;
                                java.net.http.HttpRequest apiReq = java.net.http.HttpRequest.newBuilder()
                                        .uri(java.net.URI.create(apiUrl))
                                        .header("Authorization", "Bearer " + token)
                                        .GET()
                                        .build();
                                java.net.http.HttpResponse<String> apiResp = client
                                        .send(apiReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                                
                                if (apiResp.statusCode() == 401 && tokenRetries < 2) {
                                    tokenRetries++;
                                    cachedSpotifyToken = null;
                                    spotifyTokenExpiry = 0;
                                    token = getCachedSpotifyToken();
                                    if (token != null) continue;
                                }

                                if (apiResp.statusCode() != 200) {
                                    logger.warn("Spotify API returned {} during progressive load, stopping pagination", apiResp.statusCode());
                                    break;
                                }
                                
                                com.fasterxml.jackson.databind.JsonNode apiRoot = mapper.readTree(apiResp.body());
                                com.fasterxml.jackson.databind.JsonNode items = apiRoot.path("items");
                                List<SpotifyMetadata> pageTracks = new ArrayList<>();
                                for (com.fasterxml.jackson.databind.JsonNode apiItem : items) {
                                    com.fasterxml.jackson.databind.JsonNode td = isAlbum ? apiItem : apiItem.path("track");
                                    if (td.isMissingNode() || td.isNull()) continue;

                                    String trackName = td.path("name").asText("");
                                    if (trackName.isEmpty()) continue;

                                    StringBuilder artists = new StringBuilder();
                                    com.fasterxml.jackson.databind.JsonNode artistArr = td.path("artists");
                                    if (artistArr.isArray()) {
                                        for (int i = 0; i < artistArr.size(); i++) {
                                            if (i > 0) artists.append(", ");
                                            artists.append(artistArr.get(i).path("name").asText(""));
                                        }
                                    }
                                    if (artists.length() == 0) artists.append("Spotify");

                                    String artwork = null;
                                    if (!isAlbum) {
                                        com.fasterxml.jackson.databind.JsonNode images = td.path("album").path("images");
                                        if (images.isArray() && images.size() > 0) {
                                            artwork = images.get(0).path("url").asText(null);
                                        }
                                    }
                                    if (artwork == null || artwork.isEmpty()) {
                                        artwork = playlistArtwork;
                                    }

                                    String trackId = td.path("id").asText("");
                                    String spotifyUrl = !trackId.isEmpty() ? "https://open.spotify.com/track/" + trackId : null;

                                    long duration = td.path("duration_ms").asLong(0);
                                    pageTracks.add(new SpotifyMetadata("ytmsearch:" + cleanTrackTitle(trackName) + " " + cleanTrackTitle(artists.toString()), cleanTrackTitle(trackName), cleanTrackTitle(artists.toString()), artwork, duration, spotifyUrl));
                                }
                                if (pageTracks.isEmpty()) break;
                                tracks.addAll(pageTracks);
                                offset += pageTracks.size();

                                if (callback != null) {
                                    try {
                                        callback.onBatch(pageTracks, totalCount, playlistName);
                                    } catch (Exception e) {
                                        logger.error("Error in Spotify progressive callback for pagination batch", e);
                                    }
                                }
                                if (apiRoot.path("next").asText(null) == null || apiRoot.path("next").isNull()) break;
                            }
                            logger.info("Spotify: Fetched full {} tracks via progressive pagination from '{}'", tracks.size(), playlistName);
                        } catch (Exception e) {
                            logger.error("Spotify API pagination failed", e);
                        }
                    } else {
                        logger.warn("Spotify: '{}' has {} tracks but only {} loaded (could not get anonymous token).",
                                playlistName, totalCount, tracks.size());
                    }
                }

                if (tracks.size() > 100) {
                    tracks = new ArrayList<>(tracks.subList(0, 100));
                }

                logger.info("Spotify: Final result: {} tracks from '{}'", tracks.size(), playlistName);

            } catch (Exception e) {
                logger.error("Failed to fetch Spotify playlist: " + url, e);
            }
            return new SpotifyPlaylistResult(playlistName, tracks);
        }, ioExecutor);
    }

    public void loadItemWithFallback(Object orderingKey, String query, AudioLoadResultHandler handler) {
        if (query.startsWith("ytmsearch:")) {
            playerManager.loadItemOrdered(orderingKey, query, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    handler.trackLoaded(track);
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    if (playlist.getTracks().isEmpty()) {
                        noMatches();
                    } else {
                        handler.playlistLoaded(playlist);
                    }
                }

                @Override
                public void noMatches() {
                    String fallbackQuery = "ytsearch:" + query.substring("ytmsearch:".length());
                    playerManager.loadItemOrdered(orderingKey, fallbackQuery, handler);
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    String fallbackQuery = "ytsearch:" + query.substring("ytmsearch:".length());
                    playerManager.loadItemOrdered(orderingKey, fallbackQuery, handler);
                }
            });
        } else if (query.startsWith("ytsearch:")) {
            playerManager.loadItemOrdered(orderingKey, query, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    handler.trackLoaded(track);
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    if (playlist.getTracks().isEmpty()) {
                        noMatches();
                    } else {
                        handler.playlistLoaded(playlist);
                    }
                }

                @Override
                public void noMatches() {
                    String fallbackQuery = "ytmsearch:" + query.substring("ytsearch:".length());
                    playerManager.loadItemOrdered(orderingKey, fallbackQuery, handler);
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    String fallbackQuery = "ytmsearch:" + query.substring("ytsearch:".length());
                    playerManager.loadItemOrdered(orderingKey, fallbackQuery, handler);
                }
            });
        } else {
            boolean isDirectUrl = query.startsWith("http://") || query.startsWith("https://") || query.contains("://") || query.startsWith("scsearch:") || query.startsWith("ytsearch:") || query.startsWith("ytmsearch:");
            String targetQuery = isDirectUrl ? query : "ytmsearch:" + query;
            if (!isDirectUrl) {
                loadItemWithFallback(orderingKey, targetQuery, handler);
            } else {
                playerManager.loadItemOrdered(orderingKey, query, handler);
            }
        }
    }

    public void loadItemOrdered(Guild guild, String rawTrackUrl, AudioLoadResultHandler handler) {
        final String trackUrl;
        if (rawTrackUrl != null && rawTrackUrl.contains("spotify.com") && rawTrackUrl.contains("/search")) {
            String q = extractSpotifySearchQuery(rawTrackUrl);
            trackUrl = (q != null && !q.isEmpty()) ? q : rawTrackUrl;
        } else {
            trackUrl = rawTrackUrl;
        }
        MusicManager musicManager = getMusicManager(guild);
        
        if (trackUrl.contains("spotify.com") && (trackUrl.contains("/track/") || trackUrl.contains("/playlist/") || trackUrl.contains("/album/") || trackUrl.contains("/episode/"))) {
            if (trackUrl.contains("/playlist/") || trackUrl.contains("/album/")) {
                fetchSpotifyPlaylist(trackUrl).thenAccept(result -> {
                    if (result == null || result.tracks().isEmpty()) {
                        handler.noMatches();
                        return;
                    }
                    List<AudioTrack> fakeTracks = new ArrayList<>();
                    for (SpotifyMetadata meta : result.tracks()) {
                        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                cleanTrackTitle(meta.title()), cleanTrackTitle(meta.artist() != null ? meta.artist() : "Spotify"), meta.duration(), "spotify", false, meta.spotifyUrl() != null ? meta.spotifyUrl() : meta.query());
                        fakeTracks.add(new DeferredTrack(info, meta.query(), meta.artworkUrl()));
                    }
                    com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist fakePlaylist =
                            new com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist(result.name(), fakeTracks, null, false);
                    handler.playlistLoaded(fakePlaylist);
                });
            } else {
                fetchSpotifyMetadata(trackUrl).thenAccept(meta -> {
                    if (meta != null) {
                        loadSpotifyTrackWithFallback(musicManager, meta, handler);
                    } else {
                        handler.noMatches();
                    }
                });
            }
        } else {
            boolean isDirectUrl = trackUrl.startsWith("http://") || trackUrl.startsWith("https://") || trackUrl.contains("://") || trackUrl.startsWith("scsearch:") || trackUrl.startsWith("ytsearch:") || trackUrl.startsWith("ytmsearch:");
            if (!isDirectUrl) {
                loadItemWithFallback(musicManager, "ytmsearch:" + trackUrl, handler);
            } else {
                loadItemWithFallback(musicManager, trackUrl, handler);
            }
        }
    }

    private net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction<net.dv8tion.jda.api.entities.Message> sendHookMessage(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String message) {
        return event.getHook().sendMessageComponents(
                Container.of(
                        TextDisplay.of(message)
                ).withAccentColor(EmbedHelper.COLOR_MAIN)
        ).useComponentsV2();
    }

    public void loadAndPlay(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String trackUrl) {
        loadAndPlay(event, trackUrl, null);
    }

    public void loadAndPlay(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String rawTrackUrl,
            String forcedArtworkUrl) {
        final String trackUrl;
        if (rawTrackUrl != null && rawTrackUrl.contains("spotify.com") && rawTrackUrl.contains("/search")) {
            String q = extractSpotifySearchQuery(rawTrackUrl);
            trackUrl = (q != null && !q.isEmpty()) ? q : rawTrackUrl;
        } else {
            trackUrl = rawTrackUrl;
        }
        MusicManager musicManager = getMusicManager(event.getGuild());

        if (trackUrl.contains("spotify.com") && (trackUrl.contains("/track/") || trackUrl.contains("/playlist/") || trackUrl.contains("/album/") || trackUrl.contains("/episode/"))) {
            if (trackUrl.contains("/playlist/") || trackUrl.contains("/album/")) {
                String type = trackUrl.contains("/album/") ? "Album" : "Playlist";
                String userId = event.getUser().getId();
                // Fetch + queue in background — tracks appear progressively in the queue
                fetchSpotifyPlaylist(trackUrl).thenAccept(result -> {
                    if (result == null || result.tracks().isEmpty()) {
                        sendHookMessage(event, EmbedHelper.MSG_ERROR + " Spotify " + type.toLowerCase()
                                + " is empty or could not be loaded.").queue();
                        return;
                    }
                    List<SpotifyMetadata> metas = result.tracks();
                    if (metas.size() > 100) {
                        metas = metas.subList(0, 100);
                    }
                    for (SpotifyMetadata meta : metas) {
                        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                meta.title(), meta.artist() != null ? meta.artist() : "Spotify", meta.duration(), "spotify", false, meta.spotifyUrl() != null ? meta.spotifyUrl() : meta.query());
                        DeferredTrack track = new DeferredTrack(info, meta.query(), meta.artworkUrl());
                        track.setUserData("{\"requester\":\"" + userId + "\"}");
                        musicManager.getScheduler().queue(track);
                    }
                    musicManager.updateNowPlayingMessage();
                    sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Queued **" + metas.size()
                            + " tracks** from `" + escapeMarkdown(result.name()) + "`").queue();
                });
            } else {
                fetchSpotifyMetadata(trackUrl).thenAccept(meta -> {
                    if (meta != null) {
                        loadSpotifyTrackWithFallback(musicManager, meta, new AudioLoadResultHandler() {
                            @Override
                            public void trackLoaded(AudioTrack track) {
                                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                                musicManager.getScheduler().queue(track);
                                musicManager.updateNowPlayingMessage();
                                String displayTitle = escapeMarkdown(track.getInfo().title);
                                String displayDuration = formatTime(track.getDuration());
                                sendHookMessage(event,
                                        EmbedHelper.MSG_SUCCESS + " Queued **" + displayTitle + "** • `" + displayDuration + "`")
                                        .queue();
                            }
                            @Override
                            public void playlistLoaded(AudioPlaylist playlist) {
                                if (!playlist.getTracks().isEmpty()) {
                                    trackLoaded(playlist.getTracks().get(0));
                                } else {
                                    noMatches();
                                }
                            }
                            @Override
                            public void noMatches() {
                                sendHookMessage(event, "Nothing found for Spotify track.").queue();
                            }
                            @Override
                            public void loadFailed(FriendlyException exception) {
                                sendHookMessage(event, "Error: " + exception.getMessage()).queue();
                            }
                        });
                    } else {
                        executeLoadAndPlay(event, trackUrl, forcedArtworkUrl, musicManager);
                    }
                });
            }
        } else {
            boolean isDirectUrl = trackUrl.startsWith("http://") || trackUrl.startsWith("https://") || trackUrl.contains("://") || trackUrl.startsWith("scsearch:") || trackUrl.startsWith("ytsearch:") || trackUrl.startsWith("ytmsearch:");
            if (!isDirectUrl) {
                executeLoadAndPlay(event, "ytmsearch:" + trackUrl, forcedArtworkUrl, musicManager);
            } else {
                executeLoadAndPlay(event, trackUrl, forcedArtworkUrl, musicManager);
            }
        }
    }

    private void executeLoadAndPlay(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String trackUrl,
            String artworkUrl, MusicManager musicManager) {
        loadItemWithFallback(musicManager, trackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                // Using standard TrackData or custom UserData logic
                // Inject artwork url if needed, though yt provides its own thumbnail
                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                musicManager.getScheduler().queue(track);
                musicManager.updateNowPlayingMessage();
                String displayTitle = escapeMarkdown(track.getInfo().title);
                String displayDuration = formatTime(track.getDuration());
                sendHookMessage(event,
                        EmbedHelper.MSG_SUCCESS + " Queued **" + displayTitle + "** • `" + displayDuration + "`")
                        .queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    AudioTrack track = playlist.getTracks().get(0);
                    track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                    musicManager.getScheduler().queue(track);
                    musicManager.updateNowPlayingMessage();
                    String displayTitle = escapeMarkdown(track.getInfo().title);
                    String displayDuration = formatTime(track.getDuration());
                    sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Queued **" + displayTitle + "** • `"
                            + displayDuration + "`").queue();
                } else {
                    for (AudioTrack track : playlist.getTracks()) {
                        track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                        musicManager.getScheduler().queue(track);
                    }
                    musicManager.updateNowPlayingMessage();
                    String name = escapeMarkdown(playlist.getName());
                    sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Queued **" + name + "** • `"
                            + playlist.getTracks().size() + " tracks`").queue();
                }
            }

            @Override
            public void noMatches() {
                sendHookMessage(event, "Nothing found for: " + trackUrl).queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                sendHookMessage(event, "Error: " + exception.getMessage()).queue();
            }
        });
    }

    public void loadAndPlayInstant(SlashCommandInteractionEvent event, String trackUrl) {
        loadAndPlayInstant(event, trackUrl, null);
    }

    public void loadAndPlayInstant(SlashCommandInteractionEvent event, String rawTrackUrl, String forcedArtworkUrl) {
        final String trackUrl;
        if (rawTrackUrl != null && rawTrackUrl.contains("spotify.com") && rawTrackUrl.contains("/search")) {
            String q = extractSpotifySearchQuery(rawTrackUrl);
            trackUrl = (q != null && !q.isEmpty()) ? q : rawTrackUrl;
        } else {
            trackUrl = rawTrackUrl;
        }
        MusicManager musicManager = getMusicManager(event.getGuild());

        if (trackUrl.contains("spotify.com") && (trackUrl.contains("/track/") || trackUrl.contains("/playlist/") || trackUrl.contains("/album/") || trackUrl.contains("/episode/"))) {
            if (trackUrl.contains("/playlist/") || trackUrl.contains("/album/")) {
                String type = trackUrl.contains("/album/") ? "Album" : "Playlist";
                String userId = event.getUser().getId();
                fetchSpotifyPlaylist(trackUrl).thenAccept(result -> {
                    if (result == null || result.tracks().isEmpty()) {
                        sendHookMessage(event, EmbedHelper.MSG_ERROR + " Spotify " + type.toLowerCase()
                                + " is empty or could not be loaded.").queue();
                        return;
                    }
                    final List<SpotifyMetadata> metas = result.tracks().size() > 100
                            ? result.tracks().subList(0, 100)
                            : result.tracks();
                    SpotifyMetadata firstMeta = metas.get(0);
                    loadSpotifyTrackWithFallback(musicManager, firstMeta, new AudioLoadResultHandler() {
                        @Override
                        public void trackLoaded(AudioTrack track) {
                            track.setUserData("{\"requester\":\"" + userId + "\"}");
                            musicManager.getScheduler().playInstant(track);
                            for (int i = 1; i < metas.size(); i++) {
                                SpotifyMetadata m = metas.get(i);
                                com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                        m.title(), m.artist() != null ? m.artist() : "Spotify", m.duration(), "spotify", false, m.spotifyUrl() != null ? m.spotifyUrl() : m.query());
                                DeferredTrack dt = new DeferredTrack(info, m.query(), m.artworkUrl());
                                dt.setUserData("{\"requester\":\"" + userId + "\"}");
                                musicManager.getScheduler().getQueueRaw().offer(dt);
                            }
                            sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "**" + (metas.size() > 1 ? " • Queued `" + (metas.size() - 1) + "` tracks" : "")).queue();
                        }
                        @Override
                        public void playlistLoaded(AudioPlaylist playlist) {
                            if (!playlist.getTracks().isEmpty()) {
                                trackLoaded(playlist.getTracks().get(0));
                            } else {
                                noMatches();
                            }
                        }
                        @Override
                        public void noMatches() {
                            sendHookMessage(event, "No results found.").queue();
                        }
                        @Override
                        public void loadFailed(FriendlyException exception) {
                            sendHookMessage(event, "Error: " + exception.getMessage()).queue();
                        }
                    });
                });
                return;
            }
            fetchSpotifyMetadata(trackUrl).thenAccept(meta -> {
                if (meta != null) {
                    loadSpotifyTrackWithFallback(musicManager, meta, new AudioLoadResultHandler() {
                        @Override
                        public void trackLoaded(AudioTrack track) {
                            track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                            musicManager.getScheduler().playInstant(track);
                            sendHookMessage(event, com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "**").queue();
                        }
                        @Override
                        public void playlistLoaded(AudioPlaylist playlist) {
                            if (!playlist.getTracks().isEmpty()) {
                                trackLoaded(playlist.getTracks().get(0));
                            } else {
                                noMatches();
                            }
                        }
                        @Override
                        public void noMatches() {
                            executeLoadAndPlayInstant(event, "ytmsearch:" + trackUrl, forcedArtworkUrl, musicManager);
                        }
                        @Override
                        public void loadFailed(FriendlyException exception) {
                            executeLoadAndPlayInstant(event, "ytmsearch:" + trackUrl, forcedArtworkUrl, musicManager);
                        }
                    });
                } else {
                    executeLoadAndPlayInstant(event, trackUrl, forcedArtworkUrl, musicManager);
                }
            }).exceptionally(ex -> {
                logger.warn("searchSpotify failed for instant play command: {}, falling back to ytsearch", trackUrl, ex);
                executeLoadAndPlayInstant(event, "ytmsearch:" + trackUrl, forcedArtworkUrl, musicManager);
                return null;
            });
        } else {
            boolean isDirectUrl = trackUrl.startsWith("http://") || trackUrl.startsWith("https://") || trackUrl.contains("://") || trackUrl.startsWith("scsearch:") || trackUrl.startsWith("ytsearch:") || trackUrl.startsWith("ytmsearch:");
            if (!isDirectUrl) {
                executeLoadAndPlayInstant(event, "ytmsearch:" + trackUrl, forcedArtworkUrl, musicManager);
            } else {
                executeLoadAndPlayInstant(event, trackUrl, forcedArtworkUrl, musicManager);
            }
        }
    }

    private void executeLoadAndPlayInstant(SlashCommandInteractionEvent event, String trackUrl, String artworkUrl,
            MusicManager musicManager) {
        loadItemWithFallback(musicManager, trackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                if (artworkUrl != null && !artworkUrl.isEmpty()) {
                    track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\",\"artworkUrl\":\"" + artworkUrl + "\"}");
                }
                musicManager.getScheduler().playInstant(track);
                sendHookMessage(event, com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "**").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    sendHookMessage(event, com.discord.musicbot.commands.framework.EmbedHelper.MSG_ERROR + " No tracks found.").queue();
                    return;
                }
                AudioTrack track = playlist.getTracks().get(0);
                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                if (artworkUrl != null && !artworkUrl.isEmpty()) {
                    track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\",\"artworkUrl\":\"" + artworkUrl + "\"}");
                }
                musicManager.getScheduler().playInstant(track);
                
                if (!playlist.isSearchResult() && playlist.getTracks().size() > 1) {
                    for (int i = 1; i < playlist.getTracks().size(); i++) {
                        AudioTrack t = playlist.getTracks().get(i);
                        t.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                        if (artworkUrl != null && !artworkUrl.isEmpty()) {
                            t.setUserData("{\"requester\":\"" + event.getUser().getId() + "\",\"artworkUrl\":\"" + artworkUrl + "\"}");
                        }
                        musicManager.getScheduler().getQueueRaw().offer(t);
                    }
                    sendHookMessage(event, com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "** • Queued `" + (playlist.getTracks().size() - 1) + "` tracks").queue();
                } else {
                    sendHookMessage(event, com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "**").queue();
                }
            }

            @Override
            public void noMatches() {
                sendHookMessage(event, "No results found.").queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                sendHookMessage(event, "Error: " + exception.getMessage()).queue();
            }
        });
    }

    public void loadAndInsert(SlashCommandInteractionEvent event, String rawTrackUrl, int position) {
        final String trackUrl;
        if (rawTrackUrl != null && rawTrackUrl.contains("spotify.com") && rawTrackUrl.contains("/search")) {
            String q = extractSpotifySearchQuery(rawTrackUrl);
            trackUrl = (q != null && !q.isEmpty()) ? q : rawTrackUrl;
        } else {
            trackUrl = rawTrackUrl;
        }
        MusicManager musicManager = getMusicManager(event.getGuild());

        if (trackUrl.contains("spotify.com") && (trackUrl.contains("/track/") || trackUrl.contains("/playlist/") || trackUrl.contains("/album/") || trackUrl.contains("/episode/"))) {
            if (trackUrl.contains("/playlist/") || trackUrl.contains("/album/")) {
                String type = trackUrl.contains("/album/") ? "Album" : "Playlist";
                String userId = event.getUser().getId();
                fetchSpotifyPlaylist(trackUrl).thenAccept(result -> {
                    if (result == null || result.tracks().isEmpty()) {
                        sendHookMessage(event, EmbedHelper.MSG_ERROR + " Spotify " + type.toLowerCase()
                                + " is empty or could not be loaded.").queue();
                        return;
                    }
                    List<SpotifyMetadata> metas = result.tracks();
                    if (metas.size() > 100) {
                        metas = metas.subList(0, 100);
                    }
                    int currentPos = position;
                    for (SpotifyMetadata meta : metas) {
                        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                meta.title(), meta.artist() != null ? meta.artist() : "Spotify", meta.duration(), "spotify", false, meta.spotifyUrl() != null ? meta.spotifyUrl() : meta.query());
                        DeferredTrack track = new DeferredTrack(info, meta.query(), meta.artworkUrl());
                        track.setUserData("{\"requester\":\"" + userId + "\"}");
                        musicManager.getScheduler().insert(track, currentPos++);
                    }
                    musicManager.updateNowPlayingMessage();
                    sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Inserted **" + metas.size()
                            + " tracks** from `" + escapeMarkdown(result.name()) + "` • Starting at Position: `" + (position + 1) + "`").queue();
                });
                return;
            }
            fetchSpotifyMetadata(trackUrl).thenAccept(meta -> {
                if (meta != null) {
                    loadSpotifyTrackWithFallback(musicManager, meta, new AudioLoadResultHandler() {
                        @Override
                        public void trackLoaded(AudioTrack track) {
                            track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                            musicManager.getScheduler().insert(track, position);
                            String displayTitle = escapeMarkdown(track.getInfo().title);
                            sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Inserted **" + displayTitle + "** • Position: `" + position + "`").queue();
                        }
                        @Override
                        public void playlistLoaded(AudioPlaylist playlist) {
                            if (!playlist.getTracks().isEmpty()) {
                                trackLoaded(playlist.getTracks().get(0));
                            } else {
                                noMatches();
                            }
                        }
                        @Override
                        public void noMatches() {
                            sendHookMessage(event, "No results found.").queue();
                        }
                        @Override
                        public void loadFailed(FriendlyException exception) {
                            sendHookMessage(event, "Error: " + exception.getMessage()).queue();
                        }
                    });
                } else {
                    executeLoadAndInsert(event, trackUrl, position, musicManager);
                }
            });
        } else {
            boolean isDirectUrl = trackUrl.startsWith("http://") || trackUrl.startsWith("https://") || trackUrl.contains("://") || trackUrl.startsWith("scsearch:") || trackUrl.startsWith("ytsearch:") || trackUrl.startsWith("ytmsearch:");
            if (!isDirectUrl) {
                executeLoadAndInsert(event, "ytmsearch:" + trackUrl, position, musicManager);
            } else {
                executeLoadAndInsert(event, trackUrl, position, musicManager);
            }
        }
    }

    private void executeLoadAndInsert(SlashCommandInteractionEvent event, String trackUrl, int position,
            MusicManager musicManager) {
        loadItemWithFallback(musicManager, trackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                musicManager.getScheduler().insert(track, position);
                String displayTitle = escapeMarkdown(track.getInfo().title);
                sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Inserted **" + displayTitle
                        + "** • Position: `" + position + "`").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    sendHookMessage(event, "No tracks found.").queue();
                    return;
                }
                
                if (playlist.isSearchResult()) {
                    AudioTrack track = playlist.getTracks().get(0);
                    track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                    musicManager.getScheduler().insert(track, position);
                    sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Inserted **" + escapeMarkdown(track.getInfo().title) + "** • Position: `" + (position + 1) + "`").queue();
                } else {
                    int currentPos = position;
                    for (AudioTrack track : playlist.getTracks()) {
                        track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                        musicManager.getScheduler().insert(track, currentPos++);
                    }
                    sendHookMessage(event, EmbedHelper.MSG_SUCCESS + " Inserted `" + playlist.getTracks().size() + "` tracks • Starting at Position: `" + (position + 1) + "`").queue();
                }
            }

            @Override
            public void noMatches() {
                sendHookMessage(event, "No results found.").queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                sendHookMessage(event, "Error: " + exception.getMessage()).queue();
            }
        });
    }

    public AudioTrack decodeTrack(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            if (encoded.startsWith("SPOTIFY_RESOLVED|||")) {
                String[] parts = encoded.split("\\|\\|\\|", 6);
                if (parts.length >= 6) {
                    String title = parts[1];
                    String author = parts[2];
                    String art = parts[3].equals("null") ? null : parts[3];
                    String uri = parts[4];
                    String delegateEncoded = parts[5];
                    AudioTrack delegate = decodeTrack(delegateEncoded);
                    if (delegate != null) {
                        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                title, author, delegate.getDuration(), uri, false, uri);
                        return new SpotifyResolvedTrack(info, delegate, art);
                    }
                }
            }
            if (encoded.startsWith("DEFERRED_V2|||")) {
                String[] parts = encoded.split("\\|\\|\\|", 7);
                if (parts.length >= 7) {
                    String title = parts[1];
                    String author = parts[2];
                    long length = 0;
                    try { length = Long.parseLong(parts[3]); } catch (NumberFormatException ignored) {}
                    String uri = parts[4];
                    String query = parts[5];
                    String art = parts[6].equals("null") ? null : parts[6];
                    com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                            title, author, length, uri, true, uri != null && !uri.isEmpty() ? uri : query);
                    return new DeferredTrack(info, query, art);
                }
            }
            if (encoded.startsWith("DEFERRED|||") || encoded.startsWith("DEFERRED:")) {
                String delimiter = encoded.contains("|||") ? "\\|\\|\\|" : ":";
                String[] parts = encoded.split(delimiter, 3);
                if (parts.length >= 3) {
                    String query = parts[1];
                    String art = parts[2].equals("null") ? null : parts[2];
                    com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                            cleanTrackTitle(query.replace("ytsearch:", "").replace("ytmsearch:", "")), "Spotify", 0, "spotify", true, query);
                    return new DeferredTrack(info, query, art);
                }
            }
            return playerManager.decodeTrack(new com.sedmelluq.discord.lavaplayer.tools.io.MessageInput(
                    new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(encoded)))).decodedTrack;
        } catch (Exception e) {
            logger.error("Failed to decode track: " + encoded, e);
            return null;
        }
    }

    public AudioTrack decodeAudioTrack(String encoded) {
        return decodeTrack(encoded);
    }

    public String encodeAudioTrack(AudioTrack track) {
        if (track == null) return null;
        try {
            if (track instanceof SpotifyResolvedTrack srt) {
                String delegateEncoded = encodeAudioTrack(srt.getDelegate());
                if (delegateEncoded == null) return null;
                String title = srt.getInfo().title != null ? srt.getInfo().title : "";
                String author = srt.getInfo().author != null ? srt.getInfo().author : "";
                String art = srt.getArtworkUrl() != null ? srt.getArtworkUrl() : "null";
                String uri = srt.getInfo().uri != null ? srt.getInfo().uri : "";
                return "SPOTIFY_RESOLVED|||" + title + "|||" + author + "|||" + art + "|||" + uri + "|||" + delegateEncoded;
            }
            if (track instanceof DeferredTrack deferred) {
                String title = deferred.getInfo().title != null ? deferred.getInfo().title : "";
                String author = deferred.getInfo().author != null ? deferred.getInfo().author : "";
                long length = deferred.getInfo().length;
                String uri = deferred.getInfo().uri != null ? deferred.getInfo().uri : "";
                String query = deferred.getQuery() != null ? deferred.getQuery() : "";
                String art = deferred.getArtworkUrl() != null ? deferred.getArtworkUrl() : "null";
                return "DEFERRED_V2|||" + title + "|||" + author + "|||" + length + "|||" + uri + "|||" + query + "|||" + art;
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            playerManager.encodeTrack(new com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput(baos), track);
            return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            logger.error("Failed to encode track: " + (track.getInfo() != null ? track.getInfo().title : "unknown"), e);
            return null;
        }
    }
}
