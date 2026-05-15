package com.discord.musicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
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

    public enum LoopMode {
        OFF, TRACK, QUEUE
    }

    private final AudioPlayer player;
    private final MusicManager musicManager;
    private final LinkedBlockingDeque<AudioTrack> queue;
    private final LinkedBlockingDeque<AudioTrack> history;
    private final AtomicInteger playbackGeneration;
    private AudioTrack currentTrack;
    private LoopMode loopMode = LoopMode.OFF;
    private boolean autoplay = false;

    public TrackScheduler(AudioPlayer player, MusicManager musicManager) {
        this.player = player;
        this.musicManager = musicManager;
        this.queue = new LinkedBlockingDeque<>();
        this.history = new LinkedBlockingDeque<>();
        this.playbackGeneration = new AtomicInteger(0);
    }

    private AudioTrack preloadedAutoplayTrack;
    private CompletableFuture<Void> preloadFuture;
    private String lastRequesterId;

    public String getLastRequesterId() {
        return lastRequesterId;
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        logger.info("Track Start: " + track.getInfo().title);

        Object userData = track.getUserData();
        if (userData instanceof net.dv8tion.jda.api.entities.User) {
            lastRequesterId = ((net.dv8tion.jda.api.entities.User) userData).getId();
        } else if (userData instanceof String) {
            lastRequesterId = (String) userData;
        }

        musicManager.sendNowPlayingMessage(false);

        com.discord.musicbot.data.HistoryManager.getInstance().addEntry(
                track.getInfo().title,
                track.getInfo().uri,
                track.getInfo().author,
                track.getDuration(),
                "Unknown");

        musicManager.updateVoiceChannelStatus("<:music3:1504821132044402699> " + track.getInfo().title);

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
            });
        }
    }

    @Override
    public void onPlayerPause(AudioPlayer player) {
        musicManager.updateNowPlayingMessage();
        if (player.getPlayingTrack() != null) {
            musicManager.updateVoiceChannelStatus("Paused song");
        }
    }

    @Override
    public void onPlayerResume(AudioPlayer player) {
        musicManager.updateNowPlayingMessage();
        if (player.getPlayingTrack() != null) {
            musicManager.updateVoiceChannelStatus(
                    "<:music3:1504821132044402699> " + player.getPlayingTrack().getInfo().title);
        }
    }

    public void queue(AudioTrack track) {
        if (player.getPlayingTrack() == null && currentTrack == null && queue.isEmpty()) {
            playbackGeneration.incrementAndGet(); // Invalidate any running autoplay fallbacks
            if (track instanceof DeferredTrack deferred) {
                currentTrack = track; // Temporarily hold it
                resolveAndPlayDeferred(deferred);
            } else {
                player.startTrack(track, false);
                currentTrack = track;
                musicManager.cancelIdleTimeout();
            }
        } else {
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
            if (history.size() > 50)
                history.removeLast();
        }
        player.startTrack(track, false);
        currentTrack = track;
        musicManager.cancelIdleTimeout();
        cancelPreload();
        musicManager.notifySessionChanged();
    }

    public void nextTrack() {
        playbackGeneration.incrementAndGet();
        if (currentTrack != null) {
            history.addFirst(currentTrack.makeClone());
            if (history.size() > 25)
                history.removeLast();
        }

        if (loopMode == LoopMode.TRACK && currentTrack != null) {
            player.startTrack(currentTrack.makeClone(), false);
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
            player.startTrack(next, false);
            player.setVolume(100);
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
                });
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
                    player.startTrack(track, false);
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
                    });
                    return;
                }

                if (currentTrack != null || !history.isEmpty()) {
                    AudioTrack seed = currentTrack != null ? currentTrack : history.peekFirst();
                    if (seed != null) {
                        logger.info("Autoplay triggered (Fallback). Seed: {}", seed.getInfo().title);
                        int gen = playbackGeneration.get();
                        currentTrack = null; // Clear the track so the queue knows we are idle

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
                                musicManager.updateVoiceChannelStatus("<:addmusic3:1504821095201505390> Use /play to queue a song");
                                musicManager.startIdleTimeout();
                                musicManager.notifySessionChanged();
                            }
                        });
                        return;
                    }
                }
            }

            currentTrack = null;
            player.stopTrack();
            musicManager.deleteNowPlayingMessage();
            musicManager.updateVoiceChannelStatus("<:addmusic3:1504821095201505390> Use /play to queue a song");
            musicManager.startIdleTimeout();
            musicManager.notifySessionChanged();
        }
    }

    private void resolveAndPlayDeferred(DeferredTrack deferred) {
        musicManager.startIdleTimeout();
        PlayerManager.getInstance().loadItemOrdered(musicManager.getGuild(), deferred.getQuery(),
                new com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        track.setUserData(deferred.getUserData());
                        player.startTrack(track, false);
                        currentTrack = track;
                        musicManager.cancelIdleTimeout();
                        musicManager.notifySessionChanged();
                    }

                    @Override
                    public void playlistLoaded(com.sedmelluq.discord.lavaplayer.track.AudioPlaylist playlist) {
                        if (!playlist.getTracks().isEmpty()) {
                            trackLoaded(playlist.getTracks().get(0));
                        } else {
                            noMatches();
                        }
                    }

                    @Override
                    public void noMatches() {
                        logger.warn("Failed to resolve deferred track: {}", deferred.getQuery());
                        nextTrack();
                    }

                    @Override
                    public void loadFailed(com.sedmelluq.discord.lavaplayer.tools.FriendlyException exception) {
                        logger.warn("Failed to resolve deferred track: {}", deferred.getQuery(), exception);
                        nextTrack();
                    }
                });
    }

    private final Set<String> playedAutoplayUris = Collections.synchronizedSet(new HashSet<>());

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
        return t.contains(a) || a.contains(t);
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
        musicManager.updateVoiceChannelStatus("<:addmusic3:1504821095201505390> Use /play to queue a song");
        musicManager.notifySessionChanged();
    }

    public int clear() {
        playbackGeneration.incrementAndGet();
        int size = queue.size();
        queue.clear();
        musicManager.notifySessionChanged();
        return size;
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
        return player.isPaused();
    }

    public AudioTrack getCurrentTrack() {
        return player.getPlayingTrack();
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
        for (int i = 0; i < index; i++) {
            AudioTrack skipped = queue.poll();
            if (skipped != null && currentTrack != null) {
                history.addFirst(currentTrack.makeClone());
                if (history.size() > 50)
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

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (endReason.mayStartNext) {
            nextTrack();
        }
    }

}
