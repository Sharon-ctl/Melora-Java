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

    public static final java.util.concurrent.ExecutorService ioExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
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
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Shutdown cleanup timed out or was interrupted", e);
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

    public CompletableFuture<List<SpotifyMetadata>> searchSpotify(String query) {
        return CompletableFuture.supplyAsync(() -> {
            List<SpotifyMetadata> results = new ArrayList<>();
            try {
                java.net.http.HttpClient client = httpClient;
                String url = "https://itunes.apple.com/search?term="
                        + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                        + "&entity=song&limit=10";
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                String body = response.body();

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
                com.fasterxml.jackson.databind.JsonNode items = root.path("results");
                if (items.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode item : items) {
                        String title = item.path("trackName").asText("");
                        String artist = item.path("artistName").asText("");
                        long duration = item.path("trackTimeMillis").asLong(0);
                        String artwork = item.path("artworkUrl100").asText(null);
                        if (artwork != null) {
                            artwork = artwork.replace("100x100bb.jpg", "600x600bb.jpg");
                        }
                        if (!title.isEmpty() && duration > 0) {
                            results.add(new SpotifyMetadata("ytmsearch:" + title + " " + artist, title, artist, artwork, duration, null));
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("searchSpotify fallback error: " + e.getMessage());
            }
            return results;
        }, ioExecutor);
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

            String candTitle = track.getInfo().title.toLowerCase();
            String candAuthor = track.getInfo().author.toLowerCase();

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
        String query = meta.query() != null ? meta.query() : "ytmsearch:" + meta.title() + " " + meta.artist();
        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo spotifyInfo = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                meta.title(), meta.artist() != null ? meta.artist() : "Spotify", meta.duration(), "spotify", false, meta.spotifyUrl() != null ? meta.spotifyUrl() : query);

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
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.net.http.HttpClient client = httpClient;
                String trackId = url.split("\\?")[0].replaceAll(".*/track/", "");
                String token = getAnonymousSpotifyToken(trackId, "track");
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
                                return new SpotifyMetadata("ytmsearch:" + tTitle + " " + artists, tTitle, artists.toString(), art, dur, url);
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
                    return new SpotifyMetadata("ytmsearch:" + title + " " + artist, title, artist, artworkUrl, duration, url);
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

    private CompletableFuture<SpotifyPlaylistResult> fetchSpotifyPlaylist(String url) {
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
                                        if (artistItems.isArray()) {
                                            for (int i = 0; i < artistItems.size(); i++) {
                                                if (i > 0) artistStr.append(", ");
                                                artistStr.append(artistItems.get(i).path("profile").path("name").asText(""));
                                            }
                                        }
                                        long duration = trackData.path("duration").path("totalMilliseconds").asLong(0);
                                        if (duration == 0) {
                                            duration = trackData.path("duration_ms").asLong(0);
                                        }
                                        tracks.add(new SpotifyMetadata("ytmsearch:" + name + " " + artistStr.toString(), name, artistStr.toString(), null, duration, null));
                                    } catch (Exception e) {
                                        logger.debug("Spotify: Failed to parse track item", e);
                                    }
                                }
                            }
                        }
                    }
                }

                // If there are more tracks than what the scraper got, try anonymous Spotify token
                if (totalCount > tracks.size()) {
                    String id = url.split("\\?")[0].replaceAll(".*/(playlist|album)/", "");
                    String token = getAnonymousSpotifyToken(id, isAlbum ? "album" : "playlist");
                    if (token != null) {
                        try {
                            List<SpotifyMetadata> apiTracks = new ArrayList<>();
                            String apiUrl = isAlbum
                                    ? "https://api.spotify.com/v1/albums/" + id + "/tracks?limit=50"
                                    : "https://api.spotify.com/v1/playlists/" + id + "/tracks?limit=100";

                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            while (apiUrl != null && !apiUrl.isEmpty() && !apiUrl.equals("null")) {
                                java.net.http.HttpRequest apiReq = java.net.http.HttpRequest.newBuilder()
                                        .uri(java.net.URI.create(apiUrl))
                                        .header("Authorization", "Bearer " + token)
                                        .GET()
                                        .build();
                                java.net.http.HttpResponse<String> apiResp = client
                                        .send(apiReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                                
                                if (apiResp.statusCode() != 200) {
                                    logger.warn("Spotify API returned {}, using scraped tracks", apiResp.statusCode());
                                    break;
                                }
                                
                                com.fasterxml.jackson.databind.JsonNode apiRoot = mapper.readTree(apiResp.body());

                                com.fasterxml.jackson.databind.JsonNode items = apiRoot.path("items");
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

                                    String artwork = null;
                                    if (!isAlbum) {
                                        com.fasterxml.jackson.databind.JsonNode images = td.path("album").path("images");
                                        if (images.isArray() && images.size() > 0) {
                                            artwork = images.get(0).path("url").asText(null);
                                        }
                                    }
                                    long duration = td.path("duration_ms").asLong(0);
                                    apiTracks.add(new SpotifyMetadata("ytmsearch:" + trackName + " " + artists, trackName, artists.toString(), artwork, duration, null));
                                }
                                apiUrl = apiRoot.path("next").asText(null);
                            }

                            if (!apiTracks.isEmpty()) {
                                tracks = apiTracks;
                                logger.info("Spotify: Fetched full {} tracks via anonymous token from '{}'", tracks.size(), playlistName);
                            }
                        } catch (Exception e) {
                            logger.error("Spotify API pagination failed, using scraped tracks", e);
                        }
                    } else {
                        logger.warn("Spotify: '{}' has {} tracks but only {} loaded (could not get anonymous token).",
                                playlistName, totalCount, tracks.size());
                    }
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
        } else {
            playerManager.loadItemOrdered(orderingKey, query, handler);
        }
    }

    public void loadItemOrdered(Guild guild, String trackUrl, AudioLoadResultHandler handler) {
        MusicManager musicManager = getMusicManager(guild);
        
        if (trackUrl.contains("spotify.com")) {
            if (trackUrl.contains("/playlist/") || trackUrl.contains("/album/")) {
                fetchSpotifyPlaylist(trackUrl).thenAccept(result -> {
                    if (result == null || result.tracks().isEmpty()) {
                        handler.noMatches();
                        return;
                    }
                    List<AudioTrack> fakeTracks = new ArrayList<>();
                    for (SpotifyMetadata meta : result.tracks()) {
                        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                meta.title(), meta.artist() != null ? meta.artist() : "Spotify", meta.duration(), "spotify", false, meta.spotifyUrl() != null ? meta.spotifyUrl() : meta.query());
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
            loadItemWithFallback(musicManager, trackUrl, handler);
        }
    }

    public void loadAndPlay(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String trackUrl) {
        loadAndPlay(event, trackUrl, null);
    }

    public void loadAndPlay(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String trackUrl,
            String forcedArtworkUrl) {
        MusicManager musicManager = getMusicManager(event.getGuild());

        if (trackUrl.contains("spotify.com")) {
            if (trackUrl.contains("/playlist/") || trackUrl.contains("/album/")) {
                String type = trackUrl.contains("/album/") ? "Album" : "Playlist";
                String userId = event.getUser().getId();
                // Fetch + queue in background — tracks appear progressively in the queue
                fetchSpotifyPlaylist(trackUrl).thenAccept(result -> {
                    if (result == null || result.tracks().isEmpty()) {
                        event.getHook().sendMessage(EmbedHelper.MSG_ERROR + " Spotify " + type.toLowerCase()
                                + " is empty or could not be loaded.").queue();
                        return;
                    }
                    for (SpotifyMetadata meta : result.tracks()) {
                        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                meta.title(), meta.artist() != null ? meta.artist() : "Spotify", meta.duration(), "spotify", false, meta.spotifyUrl() != null ? meta.spotifyUrl() : meta.query());
                        DeferredTrack track = new DeferredTrack(info, meta.query(), meta.artworkUrl());
                        track.setUserData("{\"requester\":\"" + userId + "\"}");
                        musicManager.getScheduler().queue(track);
                    }
                    musicManager.updateNowPlayingMessage();
                    event.getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Queued **" + result.tracks().size()
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
                                event.getHook().sendMessage(
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
                                event.getHook().sendMessage("Nothing found for Spotify track.").queue();
                            }
                            @Override
                            public void loadFailed(FriendlyException exception) {
                                event.getHook().sendMessage("Error: " + exception.getMessage()).queue();
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
                searchSpotify(trackUrl).thenAccept(results -> {
                    if (results != null && !results.isEmpty()) {
                        SpotifyMetadata meta = results.get(0);
                        loadSpotifyTrackWithFallback(musicManager, meta, new AudioLoadResultHandler() {
                            @Override
                            public void trackLoaded(AudioTrack track) {
                                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                                musicManager.getScheduler().queue(track);
                                musicManager.updateNowPlayingMessage();
                                String displayTitle = escapeMarkdown(track.getInfo().title);
                                String displayDuration = formatTime(track.getDuration());
                                event.getHook().sendMessage(
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
                                executeLoadAndPlay(event, trackUrl, forcedArtworkUrl, musicManager);
                            }
                            @Override
                            public void loadFailed(FriendlyException exception) {
                                executeLoadAndPlay(event, trackUrl, forcedArtworkUrl, musicManager);
                            }
                        });
                    } else {
                        executeLoadAndPlay(event, trackUrl, forcedArtworkUrl, musicManager);
                    }
                });
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
                event.getHook().sendMessage(
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
                    event.getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Queued **" + displayTitle + "** • `"
                            + displayDuration + "`").queue();
                } else {
                    for (AudioTrack track : playlist.getTracks()) {
                        track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                        musicManager.getScheduler().queue(track);
                    }
                    musicManager.updateNowPlayingMessage();
                    String name = escapeMarkdown(playlist.getName());
                    event.getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Queued **" + name + "** • `"
                            + playlist.getTracks().size() + " tracks`").queue();
                }
            }

            @Override
            public void noMatches() {
                event.getHook().sendMessage("Nothing found for: " + trackUrl).queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                event.getHook().sendMessage("Error: " + exception.getMessage()).queue();
            }
        });
    }

    public void loadAndPlayInstant(SlashCommandInteractionEvent event, String trackUrl) {
        loadAndPlayInstant(event, trackUrl, null);
    }

    public void loadAndPlayInstant(SlashCommandInteractionEvent event, String trackUrl, String forcedArtworkUrl) {
        MusicManager musicManager = getMusicManager(event.getGuild());

        if (trackUrl.contains("spotify.com")) {
            fetchSpotifyMetadata(trackUrl).thenAccept(meta -> {
                if (meta != null) {
                    loadSpotifyTrackWithFallback(musicManager, meta, new AudioLoadResultHandler() {
                        @Override
                        public void trackLoaded(AudioTrack track) {
                            track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                            musicManager.getScheduler().playInstant(track);
                            event.getHook().sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "**").queue();
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
                            event.getHook().sendMessage("No results found.").queue();
                        }
                        @Override
                        public void loadFailed(FriendlyException exception) {
                            event.getHook().sendMessage("Error: " + exception.getMessage()).queue();
                        }
                    });
                } else {
                    executeLoadAndPlayInstant(event, trackUrl, forcedArtworkUrl, musicManager);
                }
            });
        } else {
            boolean isDirectUrl = trackUrl.startsWith("http://") || trackUrl.startsWith("https://") || trackUrl.contains("://") || trackUrl.startsWith("scsearch:") || trackUrl.startsWith("ytsearch:") || trackUrl.startsWith("ytmsearch:");
            if (!isDirectUrl) {
                searchSpotify(trackUrl).thenAccept(results -> {
                    if (results != null && !results.isEmpty()) {
                        SpotifyMetadata meta = results.get(0);
                        loadSpotifyTrackWithFallback(musicManager, meta, new AudioLoadResultHandler() {
                            @Override
                            public void trackLoaded(AudioTrack track) {
                                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                                musicManager.getScheduler().playInstant(track);
                                event.getHook().sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "**").queue();
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
                                executeLoadAndPlayInstant(event, trackUrl, forcedArtworkUrl, musicManager);
                            }
                            @Override
                            public void loadFailed(FriendlyException exception) {
                                executeLoadAndPlayInstant(event, trackUrl, forcedArtworkUrl, musicManager);
                            }
                        });
                    } else {
                        executeLoadAndPlayInstant(event, trackUrl, forcedArtworkUrl, musicManager);
                    }
                });
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
                musicManager.getScheduler().playInstant(track);
                event.getHook().sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "**").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    event.getHook().sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_ERROR + " No tracks found.").queue();
                    return;
                }
                AudioTrack track = playlist.getTracks().get(0);
                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                musicManager.getScheduler().playInstant(track);
                
                if (!playlist.isSearchResult() && playlist.getTracks().size() > 1) {
                    for (int i = 1; i < playlist.getTracks().size(); i++) {
                        AudioTrack t = playlist.getTracks().get(i);
                        t.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                        musicManager.getScheduler().getQueueRaw().offer(t);
                    }
                    event.getHook().sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "** • Queued `" + (playlist.getTracks().size() - 1) + "` tracks").queue();
                } else {
                    event.getHook().sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_SUCCESS + " Instant Playing **" + escapeMarkdown(track.getInfo().title) + "**").queue();
                }
            }

            @Override
            public void noMatches() {
                event.getHook().sendMessage("No results found.").queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                event.getHook().sendMessage("Error: " + exception.getMessage()).queue();
            }
        });
    }

    public void loadAndInsert(SlashCommandInteractionEvent event, String trackUrl, int position) {
        MusicManager musicManager = getMusicManager(event.getGuild());

        if (trackUrl.contains("spotify.com")) {
            fetchSpotifyMetadata(trackUrl).thenAccept(meta -> {
                if (meta != null) {
                    loadSpotifyTrackWithFallback(musicManager, meta, new AudioLoadResultHandler() {
                        @Override
                        public void trackLoaded(AudioTrack track) {
                            track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                            musicManager.getScheduler().insert(track, position);
                            String displayTitle = escapeMarkdown(track.getInfo().title);
                            event.getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Inserted **" + displayTitle + "** • Position: `" + position + "`").queue();
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
                            event.getHook().sendMessage("No results found.").queue();
                        }
                        @Override
                        public void loadFailed(FriendlyException exception) {
                            event.getHook().sendMessage("Error: " + exception.getMessage()).queue();
                        }
                    });
                } else {
                    executeLoadAndInsert(event, trackUrl, position, musicManager);
                }
            });
        } else {
            boolean isDirectUrl = trackUrl.startsWith("http://") || trackUrl.startsWith("https://") || trackUrl.contains("://") || trackUrl.startsWith("scsearch:") || trackUrl.startsWith("ytsearch:") || trackUrl.startsWith("ytmsearch:");
            if (!isDirectUrl) {
                searchSpotify(trackUrl).thenAccept(results -> {
                    if (results != null && !results.isEmpty()) {
                        SpotifyMetadata meta = results.get(0);
                        loadSpotifyTrackWithFallback(musicManager, meta, new AudioLoadResultHandler() {
                            @Override
                            public void trackLoaded(AudioTrack track) {
                                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                                musicManager.getScheduler().insert(track, position);
                                String displayTitle = escapeMarkdown(track.getInfo().title);
                                event.getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Inserted **" + displayTitle + "** • Position: `" + position + "`").queue();
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
                                executeLoadAndInsert(event, trackUrl, position, musicManager);
                            }
                            @Override
                            public void loadFailed(FriendlyException exception) {
                                executeLoadAndInsert(event, trackUrl, position, musicManager);
                            }
                        });
                    } else {
                        executeLoadAndInsert(event, trackUrl, position, musicManager);
                    }
                });
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
                event.getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Inserted **" + displayTitle
                        + "** • Position: `" + position + "`").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    event.getHook().sendMessage("No tracks found.").queue();
                    return;
                }
                
                if (playlist.isSearchResult()) {
                    AudioTrack track = playlist.getTracks().get(0);
                    track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                    musicManager.getScheduler().insert(track, position);
                    event.getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Inserted **" + escapeMarkdown(track.getInfo().title) + "** • Position: `" + (position + 1) + "`").queue();
                } else {
                    int currentPos = position;
                    for (AudioTrack track : playlist.getTracks()) {
                        track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                        musicManager.getScheduler().insert(track, currentPos++);
                    }
                    event.getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Inserted `" + playlist.getTracks().size() + "` tracks • Starting at Position: `" + (position + 1) + "`").queue();
                }
            }

            @Override
            public void noMatches() {
                event.getHook().sendMessage("No results found.").queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                event.getHook().sendMessage("Error: " + exception.getMessage()).queue();
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
            if (encoded.startsWith("DEFERRED|||") || encoded.startsWith("DEFERRED:")) {
                String delimiter = encoded.contains("|||") ? "\\|\\|\\|" : ":";
                String[] parts = encoded.split(delimiter, 3);
                if (parts.length >= 3) {
                    String query = parts[1];
                    String art = parts[2].equals("null") ? null : parts[2];
                    com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                            query.replace("ytsearch:", "").replace("ytmsearch:", ""), "Spotify", 0, "spotify", true, query);
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
                return "DEFERRED|||" + deferred.getQuery() + "|||" + (deferred.getArtworkUrl() == null ? "null" : deferred.getArtworkUrl());
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
