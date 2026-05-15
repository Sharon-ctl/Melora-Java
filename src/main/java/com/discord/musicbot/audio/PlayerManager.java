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

    private final DefaultAudioPlayerManager playerManager;
    private final Map<Long, MusicManager> musicManagers;

    private PlayerManager() {
        this.musicManagers = new ConcurrentHashMap<>();
        this.playerManager = new DefaultAudioPlayerManager();

        playerManager.getConfiguration()
                .setOutputFormat(com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats.DISCORD_OPUS);

        playerManager.getConfiguration().setResamplingQuality(
                com.sedmelluq.discord.lavaplayer.player.AudioConfiguration.ResamplingQuality.HIGH);
        playerManager.getConfiguration().setOpusEncodingQuality(10);
        playerManager.getConfiguration().setFilterHotSwapEnabled(true);
        playerManager.setFrameBufferDuration(1000);

        // --- Register YouTube Source (v2) ---
        YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(true);
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
        new java.util.ArrayList<>(musicManagers.values()).forEach(MusicManager::cleanup);
        musicManagers.clear();
        playerManager.shutdown();
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

    private record SpotifyMetadata(String query, String artworkUrl) {
    }

    private CompletableFuture<SpotifyMetadata> fetchSpotifyMetadata(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
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
                if (mDesc.find()) {
                    String desc = mDesc.group(1);
                    if (desc.contains("·")) {
                        title = title + " " + desc.split("·")[1].trim();
                    } else {
                        title = title + " " + desc;
                    }
                }

                java.util.regex.Matcher mImage = java.util.regex.Pattern
                        .compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html);
                if (mImage.find()) {
                    artworkUrl = mImage.group(1);
                }

                if (!title.isEmpty()) {
                    return new SpotifyMetadata("ytsearch:" + title, artworkUrl);
                }
            } catch (Exception e) {
                logger.error("Failed to fetch Spotify URL: " + url, e);
            }
            return null;
        });
    }

    private CompletableFuture<List<SpotifyMetadata>> fetchSpotifyPlaylist(String url) {
        return CompletableFuture.supplyAsync(() -> {
            List<SpotifyMetadata> tracks = new ArrayList<>();
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                String html = response.body();

                // Extract the Base64-encoded initialState JSON from the page
                java.util.regex.Matcher stateMatcher = java.util.regex.Pattern
                        .compile("<script id=\"initialState\"[^>]*>([A-Za-z0-9+/=]+)</script>")
                        .matcher(html);

                if (!stateMatcher.find()) {
                    logger.warn("Spotify: Could not find initialState script tag in HTML");
                    return tracks;
                }

                String base64 = stateMatcher.group(1);
                String json = new String(java.util.Base64.getDecoder().decode(base64),
                        java.nio.charset.StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

                com.fasterxml.jackson.databind.JsonNode entities = root.path("entities").path("items");
                if (entities.isMissingNode()) {
                    logger.warn("Spotify: entities.items missing from initialState");
                    return tracks;
                }

                // Find the first entity key (e.g. "spotify:playlist:xxx" or
                // "spotify:album:xxx")
                java.util.Iterator<String> fieldNames = entities.fieldNames();
                if (!fieldNames.hasNext())
                    return tracks;
                String entityKey = fieldNames.next();
                com.fasterxml.jackson.databind.JsonNode entity = entities.get(entityKey);

                boolean isAlbum = url.contains("/album/");
                com.fasterxml.jackson.databind.JsonNode itemsNode;

                if (isAlbum) {
                    // Albums: entity.tracksV2.items[].track.{name, artists}
                    itemsNode = entity.path("tracksV2").path("items");
                } else {
                    // Playlists: entity.content.items[].itemV2.data.{name, artists}
                    itemsNode = entity.path("content").path("items");
                }

                if (!itemsNode.isArray()) {
                    logger.warn("Spotify: items node is not an array");
                    return tracks;
                }

                for (com.fasterxml.jackson.databind.JsonNode item : itemsNode) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode trackData;
                        if (isAlbum) {
                            trackData = item.path("track");
                        } else {
                            trackData = item.path("itemV2").path("data");
                        }

                        String name = trackData.path("name").asText("");
                        if (name.isEmpty())
                            continue;

                        // Build artist string
                        StringBuilder artistStr = new StringBuilder();
                        com.fasterxml.jackson.databind.JsonNode artistItems = trackData.path("artists").path("items");
                        if (artistItems.isArray()) {
                            for (int i = 0; i < artistItems.size(); i++) {
                                if (i > 0)
                                    artistStr.append(", ");
                                artistStr.append(artistItems.get(i).path("profile").path("name").asText(""));
                            }
                        }

                        String searchQuery = "ytsearch:" + name + " " + artistStr.toString();
                        tracks.add(new SpotifyMetadata(searchQuery, null));
                    } catch (Exception e) {
                        logger.debug("Spotify: Failed to parse individual track item", e);
                    }
                }

                logger.info("Spotify: Extracted {} tracks from {}", tracks.size(), url);

            } catch (Exception e) {
                logger.error("Failed to fetch Spotify playlist: " + url, e);
            }
            return tracks;
        });
    }

    public void loadItemOrdered(Guild guild, String trackUrl, AudioLoadResultHandler handler) {
        MusicManager musicManager = getMusicManager(guild);
        playerManager.loadItemOrdered(musicManager, trackUrl, handler);
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
                fetchSpotifyPlaylist(trackUrl).thenAccept(tracks -> {
                    if (tracks == null || tracks.isEmpty()) {
                        event.getHook().sendMessage("<:error1:1461351972924817552> Spotify " + type.toLowerCase()
                                + " is empty or could not be loaded.").queue();
                        return;
                    }
                    for (SpotifyMetadata meta : tracks) {
                        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                meta.query().replace("ytsearch:", ""), "Spotify", 0, "spotify", true, meta.query());
                        DeferredTrack track = new DeferredTrack(info, meta.query(), meta.artworkUrl());
                        track.setUserData("{\"requester\":\"" + userId + "\"}");
                        musicManager.getScheduler().queue(track);
                    }
                    musicManager.updateNowPlayingMessage();
                    event.getHook().sendMessage("<:success1:1461351761607393453> Queued **" + tracks.size()
                            + " tracks** from Spotify " + type + ".").queue();
                });
            } else {
                fetchSpotifyMetadata(trackUrl).thenAccept(meta -> {
                    String finalUrl = trackUrl;
                    String finalArt = forcedArtworkUrl;
                    if (meta != null) {
                        finalUrl = meta.query();
                        finalArt = meta.artworkUrl();
                    }
                    executeLoadAndPlay(event, finalUrl, finalArt, musicManager);
                });
            }
        } else {
            executeLoadAndPlay(event, trackUrl, forcedArtworkUrl, musicManager);
        }
    }

    private void executeLoadAndPlay(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String trackUrl,
            String artworkUrl, MusicManager musicManager) {
        playerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler() {
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
                        "<:success1:1461351761607393453> Queued **" + displayTitle + "** • `" + displayDuration + "`")
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
                    event.getHook().sendMessage("<:success1:1461351761607393453> Queued **" + displayTitle + "** • `"
                            + displayDuration + "`").queue();
                } else {
                    for (AudioTrack track : playlist.getTracks()) {
                        track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                        musicManager.getScheduler().queue(track);
                    }
                    musicManager.updateNowPlayingMessage();
                    String name = escapeMarkdown(playlist.getName());
                    event.getHook().sendMessage("<:success1:1461351761607393453> Queued **" + name + "** • `"
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
                String finalUrl = trackUrl;
                String finalArt = forcedArtworkUrl;
                if (meta != null) {
                    finalUrl = meta.query();
                    finalArt = meta.artworkUrl();
                }
                executeLoadAndPlayInstant(event, finalUrl, finalArt, musicManager);
            });
        } else {
            executeLoadAndPlayInstant(event, trackUrl, forcedArtworkUrl, musicManager);
        }
    }

    private void executeLoadAndPlayInstant(SlashCommandInteractionEvent event, String trackUrl, String artworkUrl,
            MusicManager musicManager) {
        playerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                musicManager.getScheduler().playInstant(track);
                event.getHook().sendMessage("**Playing Now:** " + track.getInfo().title).queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult() && !playlist.getTracks().isEmpty()) {
                    AudioTrack track = playlist.getTracks().get(0);
                    track.setUserData("{\"requester\":\"" + event.getUser().getId() + "\"}");
                    musicManager.getScheduler().playInstant(track);
                    event.getHook().sendMessage("**Playing Now:** " + track.getInfo().title).queue();
                } else {
                    event.getHook().sendMessage("Cannot instant play playlists.").queue();
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
                String finalUrl = trackUrl;
                if (meta != null) {
                    finalUrl = meta.query();
                }
                executeLoadAndInsert(event, finalUrl, position, musicManager);
            });
        } else {
            executeLoadAndInsert(event, trackUrl, position, musicManager);
        }
    }

    private void executeLoadAndInsert(SlashCommandInteractionEvent event, String trackUrl, int position,
            MusicManager musicManager) {
        playerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                track.setUserData(event.getUser());
                musicManager.getScheduler().insert(track, position);
                String displayTitle = escapeMarkdown(track.getInfo().title);
                event.getHook().sendMessage("<:success1:1461351761607393453> Inserted **" + displayTitle
                        + "** • Position: `" + position + "`").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult() && !playlist.getTracks().isEmpty()) {
                    AudioTrack track = playlist.getTracks().get(0);
                    track.setUserData(event.getUser());
                    musicManager.getScheduler().insert(track, position);
                    String displayTitle = escapeMarkdown(track.getInfo().title);
                    event.getHook().sendMessage("<:success1:1461351761607393453> Inserted **" + displayTitle
                            + "** • Position: `" + position + "`").queue();
                } else {
                    event.getHook().sendMessage("Cannot insert playlists.").queue();
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
        try {
            return playerManager.decodeTrack(new com.sedmelluq.discord.lavaplayer.tools.io.MessageInput(
                    new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(encoded)))).decodedTrack;
        } catch (java.io.IOException e) {
            logger.error("Failed to decode track: " + encoded, e);
            return null;
        }
    }

    public AudioTrack decodeAudioTrack(String encoded) {
        return decodeTrack(encoded);
    }

    public String encodeAudioTrack(AudioTrack track) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            playerManager.encodeTrack(new com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput(baos), track);
            return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (java.io.IOException e) {
            logger.error("Failed to encode track: " + track.getInfo().title, e);
            return null;
        }
    }
}
