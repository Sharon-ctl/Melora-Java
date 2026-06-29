package com.discord.musicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

/**
 * TrackScheduler - Manages the audio queue and playback with advanced features.
 */
public class TrackScheduler extends AudioEventAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);
    private static final int MAX_HISTORY = 50;
    private static final int MAX_QUEUE_SIZE = 2000;

    public enum LoopMode {
        OFF, TRACK, QUEUE
    }

    private final AudioPlayer player;
    private final AudioPlayer secondaryPlayer;
    private final MusicManager musicManager;
    private final LinkedBlockingDeque<AudioTrack> queue;
    private final LinkedBlockingDeque<AudioTrack> history;
    private final AtomicInteger playbackGeneration;
    private volatile AudioTrack currentTrack;
    private volatile LoopMode loopMode = LoopMode.OFF;
    private volatile boolean autoplay = false;
    private volatile boolean primaryIsActive = true;
    private volatile boolean crossfadeFired = false;
    private final java.util.concurrent.ScheduledFuture<?> crossfadeTask;

    public TrackScheduler(AudioPlayer player, AudioPlayer secondaryPlayer, MusicManager musicManager) {
        this.player = player;
        this.secondaryPlayer = secondaryPlayer;
        this.musicManager = musicManager;
        this.queue = new LinkedBlockingDeque<>();
        this.history = new LinkedBlockingDeque<>();
        this.playbackGeneration = new AtomicInteger(0);

        this.crossfadeTask = PlayerManager.scheduledExecutor.scheduleAtFixedRate(this::checkCrossfade, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    public AudioPlayer getActivePlayer() {
        return primaryIsActive ? player : secondaryPlayer;
    }

    private void checkCrossfade() {
        if (isPaused()) return;
        AudioTrack current = getActivePlayer().getPlayingTrack();
        if (current == null) return;
        
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(musicManager.getGuild().getId());
        if (!settings.isCrossfadeEnabled() || settings.getCrossfadeDuration() <= 0) return;
        
        long remaining = current.getDuration() - current.getPosition();
        long crossfadeMs = settings.getCrossfadeDuration() * 1000L;
        
        if (current.getDuration() < crossfadeMs * 3) return;
        
        if (remaining <= crossfadeMs && !crossfadeFired) {
            AudioTrack next = queue.peek();
            if (next != null && !(next instanceof DeferredTrack)) {
                crossfadeFired = true;
                next = queue.poll();
                
                AudioPlayer inactive = primaryIsActive ? secondaryPlayer : player;
                inactive.startTrack(next, false);
                
                musicManager.getSendHandler().startCrossfade(crossfadeMs, primaryIsActive);
                
                primaryIsActive = !primaryIsActive;
                currentTrack = next;
                
                musicManager.updateNowPlayingMessage();
            }
        }
    }
    public AudioTrack getCurrentTrack() {
        return getActivePlayer().getPlayingTrack();
    }

    private volatile AudioTrack preloadedAutoplayTrack;
    private volatile CompletableFuture<Void> preloadFuture;
    private volatile String lastRequesterId;
    private volatile long trackStartTimeMs = 0;

    public String getLastRequesterId() {
        return lastRequesterId;
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        logger.info("Track Start: " + track.getInfo().title);
        this.trackStartTimeMs = System.currentTimeMillis();

        Object userData = track.getUserData();
        if (userData instanceof net.dv8tion.jda.api.entities.User) {
            lastRequesterId = ((net.dv8tion.jda.api.entities.User) userData).getId();
        } else if (userData instanceof String s) {
            if (s.contains("\"requester\":\"")) {
                lastRequesterId = s.split("\"requester\":\"")[1].split("\"")[0];
            } else if (s.matches("\\d+")) {
                lastRequesterId = s;
            } else {
                lastRequesterId = s;
            }
        }

        musicManager.resetKaraokeTrack();
        musicManager.sendNowPlayingMessage(true, track);

        musicManager.updateVoiceChannelStatus(com.discord.musicbot.config.EmojiConfig.getInstance().music + " " + track.getInfo().title);

        if (lastRequesterId != null) {
            com.discord.musicbot.data.HistoryManager.getInstance().addEntry(
                    track.getInfo().title, track.getInfo().uri, track.getInfo().author, track.getDuration(), lastRequesterId);
        }

        if (autoplay && queue.isEmpty()) {
            AudioTrack seed = track;
            int gen = playbackGeneration.get();
            preloadFuture = CompletableFuture.runAsync(() -> {
                try {
                    logger.debug("Pre-loading autoplay for: {}", seed.getInfo().title);
                    AudioTrack related = getRelatedTrack(seed);
                    if (related != null && gen == playbackGeneration.get()) {
                        related.setUserData(seed.getUserData());
                        preloadedAutoplayTrack = related;
                        logger.info("Autoplay pre-loaded: {}", related.getInfo().title);
                    }
                } catch (Exception e) {
                    logger.warn("Pre-load failed", e);
                }
            }, com.discord.musicbot.audio.PlayerManager.ioExecutor);
        }
    }

    @Override
    public void onPlayerPause(AudioPlayer player) {
        if (!player.isPaused()) {
            logger.debug("onPlayerPause called but player is not paused (likely transient watchdog kick). Ignoring.");
            return;
        }
        musicManager.updateNowPlayingMessage();
        if (player.getPlayingTrack() != null) {
            musicManager.updateVoiceChannelStatus("Paused song");
        }
    }

    @Override
    public void onPlayerResume(AudioPlayer player) {
        if (player.isPaused()) {
            logger.debug("onPlayerResume called but player is paused. Ignoring.");
            return;
        }
        musicManager.updateNowPlayingMessage();
        if (player.getPlayingTrack() != null) {
            musicManager.updateVoiceChannelStatus(
                    com.discord.musicbot.config.EmojiConfig.getInstance().music + " " + player.getPlayingTrack().getInfo().title);
        }
    }

    public void queue(AudioTrack track) {
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(musicManager.getGuild().getId());
        String uri = track.getInfo().uri;
        if (uri != null) {
            for (String domain : settings.getBlacklistDomains()) {
                if (uri.contains(domain)) {
                    logger.warn("Track rejected by domain blacklist: {}", uri);
                    return;
                }
            }
        }
        String title = track.getInfo().title.toLowerCase();
        String author = track.getInfo().author.toLowerCase();
        for (String trackBl : settings.getBlacklistTracks()) {
            if (title.contains(trackBl.toLowerCase()) || (uri != null && uri.equalsIgnoreCase(trackBl))) return;
        }
        for (String artistBl : settings.getBlacklistArtists()) {
            if (author.contains(artistBl.toLowerCase()) || title.contains(artistBl.toLowerCase())) return;
        }

        if (player.getPlayingTrack() == null && currentTrack == null && queue.isEmpty()) {
            playbackGeneration.incrementAndGet(); // Invalidate any running autoplay fallbacks
            if (track instanceof DeferredTrack deferred) {
                currentTrack = track; // Temporarily hold it
                resolveAndPlayDeferred(deferred);
            } else {
                getActivePlayer().startTrack(track, false);
                currentTrack = track;
                musicManager.cancelIdleTimeout();
            }
        } else {
            if (queue.size() >= MAX_QUEUE_SIZE) {
                logger.warn("Queue size limit reached ({}), rejecting track: {}", MAX_QUEUE_SIZE, track.getInfo().title);
                return;
            }
            queue.offer(track);
            logger.debug("Queued track: {}", track.getInfo().title);
            cancelPreload();
        }
        musicManager.notifySessionChanged();
    }

    private void cancelPreload() {
        if (preloadFuture != null && !preloadFuture.isDone()) {
            preloadFuture.cancel(true);
        }
        preloadedAutoplayTrack = null;
    }

    public void playInstant(AudioTrack track) {
        playbackGeneration.incrementAndGet();
        if (currentTrack != null) {
            history.addFirst(currentTrack.makeClone());
            if (history.size() > MAX_HISTORY)
                history.removeLast();
        }
        crossfadeFired = false;
        musicManager.getSendHandler().stopCrossfade();
        player.stopTrack();
        secondaryPlayer.stopTrack();
        getActivePlayer().startTrack(track, false);
        currentTrack = track;
        musicManager.cancelIdleTimeout();
        cancelPreload();
        musicManager.notifySessionChanged();
    }

    public void nextTrack() {
        playbackGeneration.incrementAndGet();
        if (currentTrack != null) {
            history.addFirst(currentTrack.makeClone());
            if (history.size() > MAX_HISTORY)
                history.removeLast();
        }

        crossfadeFired = false;
        musicManager.getSendHandler().stopCrossfade();
        player.stopTrack();
        secondaryPlayer.stopTrack();

        if (loopMode == LoopMode.TRACK && currentTrack != null) {
            getActivePlayer().startTrack(currentTrack.makeClone(), false);
            return;
        }

        if (loopMode == LoopMode.QUEUE && currentTrack != null) {
            queue.offer(currentTrack.makeClone());
        }

        AudioTrack next = queue.poll();
        if (next instanceof DeferredTrack deferred) {
            resolveAndPlayDeferred(deferred);
            return;
        }

        if (next != null) {
            getActivePlayer().startTrack(next, false);
            currentTrack = next;
            musicManager.cancelIdleTimeout();

            if (autoplay && queue.isEmpty()) {
                AudioTrack seed = next;
                int gen = playbackGeneration.get();
                preloadFuture = CompletableFuture.runAsync(() -> {
                    try {
                        AudioTrack related = getRelatedTrack(seed);
                        if (related != null && gen == playbackGeneration.get()) {
                            related.setUserData(seed.getUserData());
                            preloadedAutoplayTrack = related;
                        }
                    } catch (Exception ignored) {
                    }
                }, com.discord.musicbot.audio.PlayerManager.ioExecutor);
            }
        } else {
            if (autoplay) {
                // If preload is still running, wait briefly for it
                if (preloadedAutoplayTrack == null && preloadFuture != null && !preloadFuture.isDone()) {
                    try {
                        preloadFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }
                }

                if (preloadedAutoplayTrack != null) {
                    logger.info("Using pre-loaded autoplay track: {}", preloadedAutoplayTrack.getInfo().title);
                    AudioTrack track = preloadedAutoplayTrack;
                    preloadedAutoplayTrack = null;
                    getActivePlayer().startTrack(track, false);
                    currentTrack = track;
                    musicManager.cancelIdleTimeout();

                    int gen = playbackGeneration.get();
                    preloadFuture = CompletableFuture.runAsync(() -> {
                        try {
                            AudioTrack related = getRelatedTrack(track);
                            if (related != null && gen == playbackGeneration.get()) {
                                related.setUserData(track.getUserData());
                                preloadedAutoplayTrack = related;
                            }
                        } catch (Exception ignored) {
                        }
                    }, com.discord.musicbot.audio.PlayerManager.ioExecutor);
                    return;
                }

                if (currentTrack != null || !history.isEmpty()) {
                    AudioTrack seed = currentTrack != null ? currentTrack : history.peekFirst();
                    if (seed != null) {
                        logger.info("Autoplay triggered (Fallback). Seed: {}", seed.getInfo().title);
                        int gen = playbackGeneration.get();
                        currentTrack = null; // Clear the track so the queue knows we are idle
                        if (player.getPlayingTrack() != null) {
                            player.stopTrack(); // Stop immediately so the user knows skip worked
                        }

                        CompletableFuture.runAsync(() -> {
                            try {
                                AudioTrack related = getRelatedTrack(seed);
                                if (related != null && gen == playbackGeneration.get()) {
                                    related.setUserData(seed.getUserData());
                                    queue(related);
                                    return;
                                }
                            } catch (Exception e) {
                                logger.error("Autoplay failed", e);
                            }

                            if (gen == playbackGeneration.get()) {
                                currentTrack = null;
                                if (player.getPlayingTrack() != null)
                                    player.stopTrack();
                                musicManager.deleteNowPlayingMessage();
                                musicManager.updateVoiceChannelStatus(com.discord.musicbot.config.EmojiConfig.getInstance().addMusic + " Use /play to queue a song");
                                musicManager.startIdleTimeout();
                                if (musicManager.getNowPlayingChannelId() != null) {
                                    try {
                                        net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel tc = musicManager.getGuild()
                        .getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                                musicManager.getNowPlayingChannelId());
                                        if (tc != null) {
                                            tc.sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_ERROR + " Autoplay ran out of recommendations.").queue();
                                        }
                                    } catch (Exception ignored) {}
                                }
                                musicManager.notifySessionChanged();
                            }
                        }, com.discord.musicbot.audio.PlayerManager.ioExecutor);
                        return;
                    }
                }
            }

            currentTrack = null;
            player.stopTrack();
            musicManager.deleteNowPlayingMessage();
            musicManager.updateVoiceChannelStatus(com.discord.musicbot.config.EmojiConfig.getInstance().addMusic + " Use /play to queue a song");
            musicManager.startIdleTimeout();
            musicManager.notifySessionChanged();

            // Send "Queue ended" message to text channel
            if (musicManager.getNowPlayingChannelId() != null) {
                try {
                    net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel ch = musicManager.getGuild()
                        .getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                                musicManager.getNowPlayingChannelId());
                    if (ch != null) {
                        ch.sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_STOP + " Queue ended.").queue();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void resolveAndPlayDeferred(DeferredTrack deferred) {
        musicManager.startIdleTimeout();
        final int gen = playbackGeneration.get();
        PlayerManager.getInstance().loadItemOrdered(musicManager.getGuild(), deferred.getQuery(),
                new com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        if (gen != playbackGeneration.get()) {
                            logger.info("Deferred track resolved but generation changed. Ignoring.");
                            return;
                        }
                        track.setUserData(deferred.getUserData());
                        player.startTrack(track, false);
                        currentTrack = track;
                        musicManager.cancelIdleTimeout();
                        musicManager.notifySessionChanged();
                    }

                    @Override
                    public void playlistLoaded(com.sedmelluq.discord.lavaplayer.track.AudioPlaylist playlist) {
                        if (gen != playbackGeneration.get()) return;
                        if (!playlist.getTracks().isEmpty()) {
                            trackLoaded(playlist.getTracks().get(0));
                        } else {
                            noMatches();
                        }
                    }

                    @Override
                    public void noMatches() {
                        if (gen != playbackGeneration.get()) return;
                        logger.warn("Failed to resolve deferred track: {}", deferred.getQuery());
                        nextTrack();
                    }

                    @Override
                    public void loadFailed(com.sedmelluq.discord.lavaplayer.tools.FriendlyException exception) {
                        if (gen != playbackGeneration.get()) return;
                        logger.warn("Failed to resolve deferred track: {}", deferred.getQuery(), exception);
                        nextTrack();
                    }
                });
    }

    private final Set<String> playedAutoplayUris = Collections.synchronizedSet(new LinkedHashSet<>());

    private AudioTrack getRelatedTrack(AudioTrack referenceTrack) {
        String artist = referenceTrack.getInfo().author;
        String title = referenceTrack.getInfo().title;
        String cleanTitle = normalizeTitle(title);

        logger.info("[AutoPlay] Reference: \"{}\" by {}", cleanTitle, artist);

        String[] searchQueries = {
                "ytsearch:" + artist + " top songs",
                "ytsearch:" + artist + " best songs",
                "ytsearch:" + artist + " popular",
                "ytsearch:" + artist + " greatest hits",
                "ytsearch:similar to " + artist,
                "ytsearch:songs like " + cleanTitle,
                "ytsearch:" + artist + " type beat",
                "ytsearch:artists like " + artist,
                "ytsearch:" + artist + " latest songs",
                "ytsearch:" + artist + " official audio",
                "ytsearch:" + artist + " music",
                "ytsearch:" + artist + " radio",
                "ytsearch:music like " + cleanTitle,
                "ytsearch:" + artist + " playlist"
        };

        for (String query : searchQueries) {
            try {
                Thread.sleep(100);
                logger.debug("[AutoPlay] Trying: {}", query);

                List<AudioTrack> tracks = loadTracks(query);

                if (tracks != null && !tracks.isEmpty()) {
                    List<AudioTrack> validTracks = tracks.stream()
                            .filter(track -> isValidAutoPlayTrack(track, referenceTrack))
                            .sorted((a, b) -> Integer.compare(getAutoplayScore(b, artist), getAutoplayScore(a, artist)))
                            .limit(30)
                            .collect(java.util.stream.Collectors.toList());

                    if (!validTracks.isEmpty()) {
                        List<AudioTrack> sameArtistTracks = validTracks.stream()
                                .filter(t -> isSameArtist(t, artist))
                                .collect(java.util.stream.Collectors.toList());

                        List<AudioTrack> candidates = !sameArtistTracks.isEmpty() ? sameArtistTracks : validTracks;

                        int top = Math.min(8, candidates.size());
                        AudioTrack selected = candidates.get(new Random().nextInt(top));

                        playedAutoplayUris.add(selected.getInfo().uri);
                        if (playedAutoplayUris.size() > 500) {
                            Iterator<String> it = playedAutoplayUris.iterator();
                            if (it.hasNext()) {
                                it.next();
                                it.remove();
                            }
                        }

                        logger.info("[AutoPlay] Found: \"{}\" by {} (Score: {})",
                                selected.getInfo().title, selected.getInfo().author,
                                getAutoplayScore(selected, artist));
                        return selected;
                    }
                }
            } catch (Exception e) {
                logger.warn("Autoplay search error for query {}: {}", query, e.toString());
            }
        }

        try {
            logger.info("[AutoPlay] Trying final fallback...");
            List<AudioTrack> fallback = loadTracks("ytsearch:" + artist);
            if (fallback != null) {
                AudioTrack valid = fallback.stream()
                        .filter(t -> isValidAutoPlayTrack(t, referenceTrack))
                        .findFirst().orElse(null);
                if (valid != null) {
                    playedAutoplayUris.add(valid.getInfo().uri);
                    return valid;
                }
            }
        } catch (Exception e) {
        }

        logger.info("[AutoPlay] No suitable tracks found.");
        return null;
    }

    private List<AudioTrack> loadTracks(String query) {
        final List<AudioTrack> result = new ArrayList<>();
        CompletableFuture<Void> future = new CompletableFuture<>();

        com.discord.musicbot.audio.PlayerManager.getInstance().loadItemOrdered(musicManager.getGuild(), query,
                new com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        result.add(track);
                        future.complete(null);
                    }

                    @Override
                    public void playlistLoaded(com.sedmelluq.discord.lavaplayer.track.AudioPlaylist playlist) {
                        if (playlist.getTracks() != null)
                            result.addAll(playlist.getTracks());
                        future.complete(null);
                    }

                    @Override
                    public void noMatches() {
                        future.complete(null);
                    }

                    @Override
                    public void loadFailed(com.sedmelluq.discord.lavaplayer.tools.FriendlyException exception) {
                        future.complete(null);
                    }
                });

        try {
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
        return result;
    }

    private boolean isValidAutoPlayTrack(AudioTrack track, AudioTrack reference) {
        String titleLower = track.getInfo().title.toLowerCase();

        if (playedAutoplayUris.contains(track.getInfo().uri))
            return false;
        if (com.discord.musicbot.data.HistoryManager.getInstance().getRecent(50).stream()
                .anyMatch(h -> h.uri.equals(track.getInfo().uri)))
            return false;

        String refTitleNorm = normalizeTitle(reference.getInfo().title);
        String trackTitleNorm = normalizeTitle(track.getInfo().title);
        if (refTitleNorm.equals(trackTitleNorm))
            return false;

        if (track.getDuration() > 600000)
            return false;
        if (track.getDuration() < 45000)
            return false;

        if (titleLower.contains("#short"))
            return false;

        String[] keywords = {
                "podcast", "episode", "interview", "talk show", "review",
                "reaction", "reacts to", "trailer", "tutorial", "how to",
                "unboxing", "vlog", "behind the scenes", "explained"
        };
        for (String k : keywords) {
            if (titleLower.contains(k))
                return false;
        }

        return true;
    }

    private int getAutoplayScore(AudioTrack track, String originalArtist) {
        int score = 0;
        String titleLower = track.getInfo().title.toLowerCase();
        String authorLower = track.getInfo().author.toLowerCase();
        String originalArtistLower = originalArtist.toLowerCase();

        if (authorLower.contains(originalArtistLower) || originalArtistLower.contains(authorLower))
            score += 15;

        if (titleLower.contains("official") || titleLower.contains("audio") || titleLower.contains("video") ||
                authorLower.contains("vevo"))
            score += 5;

        if (authorLower.contains("music") || authorLower.contains("records") || authorLower.contains("vevo"))
            score += 3;

        if (!titleLower.contains("lyric") && !titleLower.contains("letra"))
            score += 2;

        if (titleLower.contains("live") && !originalArtistLower.contains("live"))
            score -= 5;

        if (titleLower.contains("cover") && !originalArtistLower.contains("cover"))
            score -= 5;

        if (titleLower.contains("remix") && !originalArtistLower.contains("remix"))
            score -= 5;

        return score;
    }

    private boolean isSameArtist(AudioTrack track, String artist) {
        String t = track.getInfo().author.toLowerCase();
        String a = artist.toLowerCase();
        if (t.equals(a)) return true;
        return t.matches(".*\\b" + java.util.regex.Pattern.quote(a) + "\\b.*") || 
               a.matches(".*\\b" + java.util.regex.Pattern.quote(t) + "\\b.*");
    }

    private String normalizeTitle(String title) {
        return title.toLowerCase()
                .replaceAll("\\(.*?official.*?\\)", "")
                .replaceAll("\\[.*?official.*?\\]", "")
                .replaceAll("\\(.*?lyric.*?\\)", "")
                .replaceAll("\\[.*?lyric.*?\\]", "")
                .replaceAll("\\(.*?audio.*?\\)", "")
                .replaceAll("\\(.*?video.*?\\)", "")
                .replaceAll("ft\\.?|feat\\.?|featuring", "")
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("[-|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public boolean previousTrack() {
        if (history.isEmpty())
            return false;

        playbackGeneration.incrementAndGet();
        if (currentTrack != null) {
            queue.addFirst(currentTrack.makeClone());
        }

        AudioTrack prev = history.pollFirst();
        if (prev != null) {
            player.startTrack(prev, false);
            currentTrack = prev;
            musicManager.cancelIdleTimeout();
            return true;
        }
        return false;
    }

    public boolean hasHistory() {
        return !history.isEmpty();
    }

    public void stop() {
        playbackGeneration.incrementAndGet();
        queue.clear();
        player.stopTrack();

        musicManager.deleteNowPlayingMessage();
        currentTrack = null;
        loopMode = LoopMode.OFF;
        autoplay = false;
        cancelPreload();

        // Sync autoplay state back to persisted GuildSettings
        try {
            com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(musicManager.getGuild().getId());
            if (settings.isAutoplay()) {
                settings.setAutoplay(false);
                com.discord.musicbot.data.GuildSettingsManager.getInstance().markDirty();
            }
        } catch (Exception ignored) {}

        musicManager.updateVoiceChannelStatus(com.discord.musicbot.config.EmojiConfig.getInstance().addMusic + " Use /play to queue a song");
        musicManager.startIdleTimeout();
        musicManager.notifySessionChanged();
    }

    public void cleanup() {
        stop();
        if (crossfadeTask != null) {
            crossfadeTask.cancel(true);
        }
    }

    public int clear(String filterUserId) {
        playbackGeneration.incrementAndGet();
        if (filterUserId == null) {
            int size = queue.size();
            queue.clear();
            musicManager.notifySessionChanged();
            return size;
        } else {
            int initialSize = queue.size();
            queue.removeIf(t -> {
                Object ud = t.getUserData();
                String uid = "";
                if (ud instanceof net.dv8tion.jda.api.entities.User u) uid = u.getId();
                else if (ud instanceof String s) {
                    if (s.contains("\"requester\":\"")) uid = s.split("\"requester\":\"")[1].split("\"")[0];
                    else uid = s;
                }
                return uid.equals(filterUserId);
            });
            int removed = initialSize - queue.size();
            if (removed > 0) musicManager.notifySessionChanged();
            return removed;
        }
    }

    public int clear() {
        return clear(null);
    }

    public void pause() {
        if (!player.isPaused()) {
            player.setPaused(true);
        }
    }

    public void resume() {
        if (player.isPaused()) {
            player.setPaused(false);
        }
    }

    public boolean isPaused() {
        return getActivePlayer().isPaused();
    }

    public void setPaused(boolean paused) {
        player.setPaused(paused);
        secondaryPlayer.setPaused(paused);
    }

    public List<AudioTrack> getQueue() {
        return new ArrayList<>(queue);
    }

    public java.util.concurrent.BlockingQueue<AudioTrack> getQueueRaw() {
        return queue;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public long getQueueDuration() {
        long duration = 0;
        for (AudioTrack track : queue) {
            duration += track.getDuration();
        }
        return duration;
    }

    public void shuffle() {
        playbackGeneration.incrementAndGet();
        List<AudioTrack> tracks = new ArrayList<>(queue);
        Collections.shuffle(tracks);
        queue.clear();
        queue.addAll(tracks);
        musicManager.notifySessionChanged();
    }

    public LoopMode getLoopMode() {
        return loopMode;
    }

    public void setLoopMode(LoopMode mode) {
        this.loopMode = mode;
    }

    public LoopMode cycleLoopMode() {
        switch (loopMode) {
            case OFF -> loopMode = LoopMode.TRACK;
            case TRACK -> loopMode = LoopMode.QUEUE;
            case QUEUE -> loopMode = LoopMode.OFF;
        }
        musicManager.notifySessionChanged();
        return loopMode;
    }

    public boolean isLooping() {
        return loopMode != LoopMode.OFF;
    }

    public boolean toggleAutoplay() {
        autoplay = !autoplay;
        musicManager.notifySessionChanged();
        return autoplay;
    }

    public boolean isAutoPlay() {
        return autoplay;
    }

    public AudioTrack remove(int index) {
        if (index < 0 || index >= queue.size())
            return null;

        List<AudioTrack> temp = new ArrayList<>(queue);
        AudioTrack removed = temp.remove(index);
        queue.clear();
        queue.addAll(temp);
        musicManager.notifySessionChanged();
        return removed;
    }

    public boolean insert(AudioTrack track, int position) {
        if (position < 0)
            position = 0;
        if (position > queue.size())
            position = queue.size();

        List<AudioTrack> temp = new ArrayList<>(queue);
        temp.add(position, track);
        queue.clear();
        queue.addAll(temp);
        musicManager.notifySessionChanged();
        return true;
    }

    public AudioTrack move(int from, int to) {
        if (from < 0 || from >= queue.size())
            return null;
        if (to < 0 || to >= queue.size())
            return null;

        List<AudioTrack> temp = new ArrayList<>(queue);
        AudioTrack track = temp.remove(from);
        temp.add(to, track);
        queue.clear();
        queue.addAll(temp);
        musicManager.notifySessionChanged();
        return track;
    }

    public boolean jump(int index) {
        if (index < 0 || index >= queue.size())
            return false;

        playbackGeneration.incrementAndGet();
        // Add current track to history once before skipping
        if (currentTrack != null) {
            history.addFirst(currentTrack.makeClone());
            if (history.size() > MAX_HISTORY)
                history.removeLast();
        }
        for (int i = 0; i < index; i++) {
            AudioTrack skipped = queue.poll();
            if (skipped != null) {
                history.addFirst(skipped.makeClone());
                if (history.size() > MAX_HISTORY)
                    history.removeLast();
            }
        }

        nextTrack();
        return true;
    }

    public void seek(long position) {
        AudioTrack track = player.getPlayingTrack();
        if (track != null && track.isSeekable()) {
            track.setPosition(position);
        }
    }

    public void setVolume(int volume) {
        player.setVolume(Math.max(0, Math.min(200, volume)));
    }

    public int getVolume() {
        return player.getVolume();
    }

    public boolean getAutoplay() {
        return autoplay;
    }

    public int cleanupQueue() {
        int removed = 0;
        Set<String> seen = new java.util.HashSet<>();
        Iterator<AudioTrack> it = queue.iterator();
        while (it.hasNext()) {
            AudioTrack t = it.next();
            if (t == null || t.getInfo() == null) {
                it.remove(); removed++; continue;
            }
            if (t instanceof DeferredTrack def && (def.getQuery() == null || def.getQuery().isEmpty())) {
                it.remove(); removed++; continue;
            }
            String key = t.getInfo().uri != null ? t.getInfo().uri : t.getInfo().title + t.getInfo().author;
            if (seen.contains(key)) {
                it.remove(); removed++; continue;
            }
            seen.add(key);
        }
        if (removed > 0) musicManager.notifySessionChanged();
        return removed;
    }

    public int dedupeQueue() {
        int removed = 0;
        Set<String> seen = new java.util.HashSet<>();
        Iterator<AudioTrack> it = queue.iterator();
        while (it.hasNext()) {
            AudioTrack t = it.next();
            if (t == null || t.getInfo() == null) continue;
            String key = t.getInfo().uri != null ? t.getInfo().uri : t.getInfo().title + t.getInfo().author;
            if (seen.contains(key)) {
                it.remove(); removed++;
            } else {
                seen.add(key);
            }
        }
        if (removed > 0) musicManager.notifySessionChanged();
        return removed;
    }

    public void reverseQueue() {
        playbackGeneration.incrementAndGet();
        List<AudioTrack> list = new ArrayList<>(queue);
        Collections.reverse(list);
        queue.clear();
        queue.addAll(list);
        musicManager.notifySessionChanged();
    }

    public void sortQueue(String criteria) {
        playbackGeneration.incrementAndGet();
        List<AudioTrack> list = new ArrayList<>(queue);
        switch (criteria.toLowerCase()) {
            case "title":
                list.sort(java.util.Comparator.comparing(t -> t.getInfo().title.toLowerCase()));
                break;
            case "artist":
            case "author":
                list.sort(java.util.Comparator.comparing(t -> t.getInfo().author.toLowerCase()));
                break;
            case "duration":
                list.sort(java.util.Comparator.comparingLong(t -> t.getDuration()));
                break;
            case "requester":
                list.sort(java.util.Comparator.comparing(t -> {
                    Object ud = t.getUserData();
                    if (ud instanceof net.dv8tion.jda.api.entities.User u) return u.getName();
                    if (ud instanceof String s && s.contains("\"requester\":\"")) return s.split("\"requester\":\"")[1].split("\"")[0];
                    if (ud instanceof String s) return s;
                    return "";
                }));
                break;
        }
        queue.clear();
        queue.addAll(list);
        musicManager.notifySessionChanged();
    }

    public void shuffleFrom(int index) {
        playbackGeneration.incrementAndGet();
        List<AudioTrack> list = new ArrayList<>(queue);
        if (index < 0 || index >= list.size()) return;
        List<AudioTrack> sublist = list.subList(index, list.size());
        Collections.shuffle(sublist);
        queue.clear();
        queue.addAll(list);
        musicManager.notifySessionChanged();
    }

    public boolean swap(int index1, int index2) {
        playbackGeneration.incrementAndGet();
        List<AudioTrack> list = new ArrayList<>(queue);
        if (index1 < 0 || index1 >= list.size() || index2 < 0 || index2 >= list.size()) return false;
        Collections.swap(list, index1, index2);
        queue.clear();
        queue.addAll(list);
        musicManager.notifySessionChanged();
        return true;
    }


    @Override
    public void onTrackEnd(AudioPlayer pl, AudioTrack track, AudioTrackEndReason endReason) {
        logger.info("Track End: " + track.getInfo().title + " Reason: " + endReason);
        if (trackStartTimeMs > 0) {
            long durationPlayed = System.currentTimeMillis() - trackStartTimeMs;
            if (durationPlayed >= 30000 || durationPlayed >= (track.getDuration() * 0.4)) {
                String uId = lastRequesterId != null ? lastRequesterId : "Unknown";
                com.discord.musicbot.data.StatsManager.getInstance().addListeningData(
                        uId, track.getInfo().title, track.getInfo().author, durationPlayed);
            }
        }
        trackStartTimeMs = 0;

        if (endReason.mayStartNext) {
            if (crossfadeFired && pl != getActivePlayer()) {
                crossfadeFired = false;
                musicManager.getSendHandler().stopCrossfade();
            } else {
                nextTrack();
            }
        }
    }

}
