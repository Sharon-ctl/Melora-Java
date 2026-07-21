package com.discord.musicbot.audio;

import com.discord.musicbot.data.SessionManager;
import com.discord.musicbot.data.SessionManager.SessionSnapshot;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import net.dv8tion.jda.api.entities.Guild;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.discord.musicbot.lyrics.LyricsManager;
import com.discord.musicbot.lyrics.KaraokeManager;

import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * MusicManager - Per-guild audio player and queue manager.
 */
public class MusicManager {

    private static final Logger logger = LoggerFactory.getLogger(MusicManager.class);
    private static final long IDLE_TIMEOUT_SECONDS = 180; // 3 minutes

    private final AudioPlayer player;
    private final AudioPlayer secondaryPlayer;
    private final TrackScheduler scheduler;
    private final AudioPlayerSendHandler sendHandler;
    private final FilterManager filterManager;
    private final Guild guild;
    private ScheduledFuture<?> idleTask;

    // Alone Mode
    private ScheduledFuture<?> aloneTask;
    private boolean wasAlonePaused = false;
    private boolean mode247 = false;

    // Voice channel status cooldown (matches JS bot)
    private long lastVCStatusUpdate = 0;
    private static final long VC_STATUS_COOLDOWN = 3000; // 3 seconds

    // Stall Watchdog
    private ScheduledFuture<?> watchdogTask;
    private int stallCount = 0;

    private volatile boolean isDeliberateDisconnect = false;
    private volatile String activeVoiceChannelId = null;

    public void markDeliberateDisconnect() {
        this.isDeliberateDisconnect = true;
    }

    public boolean isDeliberateDisconnect() {
        return isDeliberateDisconnect;
    }

    public String getActiveVoiceChannelId() {
        return activeVoiceChannelId;
    }

    public void setActiveVoiceChannelId(String channelId) {
        if (channelId != null) {
            this.activeVoiceChannelId = channelId;
        }
    }

    private java.util.Set<String> tempDjs;

    public void grantTempDj(String userId) {
        tempDjs.add(userId);
    }

    public void revokeTempDj(String userId) {
        tempDjs.remove(userId);
    }

    public boolean isTempDj(String userId) {
        return tempDjs.contains(userId);
    }

    // Karaoke Mode & Live Lyrics Container
    private boolean karaokeMode = false;
    private ScheduledFuture<?> karaokeTask;
    private volatile List<KaraokeManager.LrcLine> karaokeLines = null;
    private volatile boolean fetchingLyrics = false;
    private volatile long liveLyricsMessageId = -1;
    private volatile long liveLyricsChannelId = -1;
    private volatile int lastActiveLineIndex = -2;
    private volatile boolean isSendingLiveLyrics = false;

    public boolean isKaraokeMode() {
        return karaokeMode;
    }

    public void setKaraokeMode(boolean karaokeMode) {
        this.karaokeMode = karaokeMode;
        if (!karaokeMode) {
            cleanupLiveLyricsContainer();
            resetKaraokeTrack();
        } else {
            AudioTrack current = player.getPlayingTrack();
            if (current != null) {
                ensureLyricsFetchedAndDisplay(current, null);
            }
        }
        sendNowPlayingMessage(false);
    }

    public void resetKaraokeTrack() {
        cleanupLiveLyricsContainer();
        karaokeLines = null;
        fetchingLyrics = false;
        lastActiveLineIndex = -2;
        isSendingLiveLyrics = false;
    }

    public List<KaraokeManager.LrcLine> getKaraokeLines() {
        return karaokeLines;
    }

    public boolean isFetchingLyrics() {
        return fetchingLyrics;
    }

    public synchronized void cleanupLiveLyricsContainer() {
        if (liveLyricsMessageId != -1 && liveLyricsChannelId != -1) {
            long msgId = liveLyricsMessageId;
            long chId = liveLyricsChannelId;
            liveLyricsMessageId = -1;
            lastActiveLineIndex = -2;
            isSendingLiveLyrics = false;
            net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel = guild.getJDA()
                    .getTextChannelById(chId);
            if (channel == null) {
                channel = getAnnouncementChannel();
            }
            if (channel != null) {
                channel.deleteMessageById(msgId).queue(null, e -> {
                });
            }
        } else {
            liveLyricsMessageId = -1;
            lastActiveLineIndex = -2;
            isSendingLiveLyrics = false;
        }
    }

    public void ensureLyricsFetched(AudioTrack current) {
        ensureLyricsFetchedAndDisplay(current, null);
    }

    public void ensureLyricsFetchedAndDisplay(AudioTrack current,
            net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel) {
        if (current == null || fetchingLyrics) {
            return;
        }
        if (channel != null) {
            this.liveLyricsChannelId = channel.getIdLong();
        } else if (this.liveLyricsChannelId == -1) {
            net.dv8tion.jda.api.entities.channel.middleman.MessageChannel ch = getAnnouncementChannel();
            if (ch != null) {
                this.liveLyricsChannelId = ch.getIdLong();
            }
        }
        if (this.karaokeLines != null && !this.karaokeLines.isEmpty()) {
            sendOrUpdateLiveLyricsContainer(current, this.karaokeLines, null, true);
            return;
        }
        fetchingLyrics = true;
        sendOrUpdateLiveLyricsContainer(current, null, com.discord.musicbot.config.EmojiConfig.getInstance().music + " Searching for live synced lyrics...", true);

        String query = current.getInfo().author + " " + current.getInfo().title;
        query = query.replaceAll("(?i)\\b(official|music video|audio|lyric video|lyrics)\\b", "")
                .replaceAll("[\\(\\[].*?[\\)\\]]", "").trim();
        final String finalQuery = query;

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                String syncedLrc = LyricsManager.fetchSyncedLyrics(finalQuery).get(4, TimeUnit.SECONDS);
                if (syncedLrc != null) {
                    karaokeLines = KaraokeManager.parseLrc(syncedLrc);
                    long pos = current.getPosition();
                    lastActiveLineIndex = KaraokeManager.getActiveLineIndex(karaokeLines, pos);
                    sendOrUpdateLiveLyricsContainer(current, karaokeLines, null, true);
                } else {
                    // Try normal fetchLyrics fallback
                    var lyricsResult = LyricsManager.fetchLyrics(finalQuery).get(4, TimeUnit.SECONDS);
                    if (lyricsResult != null && lyricsResult.isLive && lyricsResult.text != null
                            && !lyricsResult.text.isBlank()) {
                        karaokeLines = KaraokeManager.parseLrc(lyricsResult.text);
                        long pos = current.getPosition();
                        lastActiveLineIndex = KaraokeManager.getActiveLineIndex(karaokeLines, pos);
                        sendOrUpdateLiveLyricsContainer(current, karaokeLines, null, true);
                    } else {
                        karaokeLines = new ArrayList<>();
                        sendOrUpdateLiveLyricsContainer(current, null,
                                com.discord.musicbot.config.EmojiConfig.getInstance().error + " No live synced lyrics found for **" + escapeMarkdownHelper(finalQuery) + "**.", true);
                    }
                }
            } catch (Exception e) {
                karaokeLines = new ArrayList<>();
                sendOrUpdateLiveLyricsContainer(current, null,
                        com.discord.musicbot.config.EmojiConfig.getInstance().error + " No live synced lyrics found for **" + escapeMarkdownHelper(finalQuery) + "**.", true);
            } finally {
                fetchingLyrics = false;
            }
        }, PlayerManager.ioExecutor);
    }

    private String escapeMarkdownHelper(String s) {
        return s == null ? "" : s.replaceAll("([*_`~>|])", "\\\\$1");
    }

    public synchronized void sendOrUpdateLiveLyricsContainer(AudioTrack current, List<KaraokeManager.LrcLine> lines,
            String statusMessage) {
        sendOrUpdateLiveLyricsContainer(current, lines, statusMessage, false);
    }

    public synchronized void sendOrUpdateLiveLyricsContainer(AudioTrack current, List<KaraokeManager.LrcLine> lines,
            String statusMessage, boolean force) {
        if (!karaokeMode)
            return;
        net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel = null;
        if (liveLyricsChannelId != -1) {
            channel = guild.getJDA().getTextChannelById(liveLyricsChannelId);
        }
        if (channel == null) {
            channel = getAnnouncementChannel();
            if (channel != null) {
                liveLyricsChannelId = channel.getIdLong();
            }
        }
        if (channel == null || current == null)
            return;

        long pos = current.getPosition();
        int activeIdx = (lines != null && !lines.isEmpty()) ? KaraokeManager.getActiveLineIndex(lines, pos) : -1;

        if (!force && statusMessage == null && activeIdx == this.lastActiveLineIndex && liveLyricsMessageId != -1) {
            return; // No line change and message already exists
        }
        this.lastActiveLineIndex = activeIdx;

        var container = com.discord.musicbot.commands.framework.EmbedHelper.createLiveLyricsContainer(
                current.getInfo().title, current.getInfo().author, lines, activeIdx, statusMessage);

        final net.dv8tion.jda.api.entities.channel.middleman.MessageChannel finalCh = channel;
        if (liveLyricsMessageId != -1) {
            final long msgId = liveLyricsMessageId;
            finalCh.editMessageComponentsById(msgId, container)
                    .useComponentsV2()
                    .queue(null, e -> {
                        if (e instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException ere && ere
                                .getErrorResponse() == net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE) {
                            liveLyricsMessageId = -1;
                            sendOrUpdateLiveLyricsContainer(current, lines, statusMessage, true);
                        }
                    });
        } else {
            if (isSendingLiveLyrics) {
                return; // Prevent duplicate message creation while initial container is across network
            }
            isSendingLiveLyrics = true;
            finalCh.sendMessageComponents(container).useComponentsV2()
                    .queue(msg -> {
                        liveLyricsMessageId = msg.getIdLong();
                        isSendingLiveLyrics = false;
                        if (this.karaokeLines != null && !this.karaokeLines.isEmpty()) {
                            sendOrUpdateLiveLyricsContainer(current, this.karaokeLines, null, true);
                        }
                    }, err -> {
                        isSendingLiveLyrics = false;
                    });
        }
    }

    public void enableInstantKaraoke() {
        enableInstantKaraoke(null);
    }

    public void enableInstantKaraoke(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel) {
        this.karaokeMode = true;
        if (channel != null) {
            this.liveLyricsChannelId = channel.getIdLong();
        }
        AudioTrack current = player.getPlayingTrack();
        if (current == null)
            return;

        ensureLyricsFetchedAndDisplay(current, channel);
    }

    private long lastWatchdogAction = 0;

    public MusicManager(AudioPlayerManager manager, Guild guild) {
        this.guild = guild;
        this.player = manager.createPlayer();
        this.secondaryPlayer = manager.createPlayer();
        this.scheduler = new TrackScheduler(player, secondaryPlayer, this);
        this.sendHandler = new AudioPlayerSendHandler(player, secondaryPlayer);
        this.filterManager = new FilterManager(this);

        player.addListener(scheduler);
        secondaryPlayer.addListener(scheduler);

        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager
                .getInstance().getSettings(guild.getId());
        player.setVolume(settings.getDefaultVolume());
        secondaryPlayer.setVolume(settings.getDefaultVolume());
        this.mode247 = settings.isMode247();

        this.tempDjs = java.util.concurrent.ConcurrentHashMap.newKeySet();

        this.npExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NP-Update-" + guild.getId());
            t.setDaemon(true);
            return t;
        });

        // Start Watchdog
        this.watchdogTask = PlayerManager.scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                AudioTrack current = player.getPlayingTrack();
                if (current != null && !scheduler.isPaused()) {
                    long lastFrame = sendHandler.getLastFrameTime();
                    long now = System.currentTimeMillis();

                    if (lastFrame > 0 && now - lastFrame > 10000 && now - lastWatchdogAction > 10000) {
                        stallCount++;
                        logger.warn("Watchdog detected stalled track (stall count: {}) in guild {}.", stallCount,
                                guild.getName());

                        if (stallCount == 1) {
                            logger.info("Watchdog Stage 1: Pause/Resume kick");
                            player.setPaused(true);
                            player.setPaused(false);
                            lastWatchdogAction = now;
                        } else if (stallCount == 2) {
                            logger.info("Watchdog Stage 2: Track clone and seek");
                            long pos = current.getPosition();
                            AudioTrack clone = current.makeClone();
                            clone.setPosition(pos);
                            player.startTrack(clone, false);
                            lastWatchdogAction = now;
                        } else {
                            logger.error("Watchdog Stage 3: Unrecoverable stall. Skipping.");
                            scheduler.nextTrack();
                            stallCount = 0;
                            lastWatchdogAction = now;
                        }
                    } else if (lastFrame > 0 && now - lastFrame < 1000) {
                        stallCount = 0;
                    }
                }
            } catch (Exception e) {
                logger.error("Error in watchdog task", e);
            }
        }, 5, 5, TimeUnit.SECONDS);

        // Start Karaoke / Live Lyrics Ticker (300ms for instant real-time highlighting)
        this.karaokeTask = PlayerManager.scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!karaokeMode || scheduler.isPaused())
                    return;
                AudioTrack current = player.getPlayingTrack();
                if (current == null)
                    return;

                if (liveLyricsChannelId != -1 && (karaokeLines == null || karaokeLines.isEmpty()) && !fetchingLyrics
                        && lastActiveLineIndex == -2) {
                    net.dv8tion.jda.api.entities.channel.middleman.MessageChannel ch = guild.getJDA()
                            .getTextChannelById(liveLyricsChannelId);
                    if (ch != null) {
                        ensureLyricsFetchedAndDisplay(current, ch);
                    }
                } else if (liveLyricsChannelId != -1 && karaokeLines != null && !karaokeLines.isEmpty()) {
                    sendOrUpdateLiveLyricsContainer(current, karaokeLines, null, false);
                }
            } catch (Exception e) {
                logger.error("Error in karaoke task", e);
            }
        }, 300, 300, TimeUnit.MILLISECONDS);

        logger.debug("MusicManager created for guild: {}", guild.getName());
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public TrackScheduler getScheduler() {
        return scheduler;
    }

    public AudioPlayerSendHandler getSendHandler() {
        return sendHandler;
    }

    public FilterManager getFilterManager() {
        return filterManager;
    }

    public Guild getGuild() {
        return guild;
    }

    public void set247(boolean mode) {
        this.mode247 = mode;
    }

    public boolean is247() {
        return mode247;
    }

    /**
     * Start idle timeout - disconnect after IDLE_TIMEOUT_SECONDS if no track plays.
     */
    public void startIdleTimeout() {
        if (mode247)
            return; // Don't timeout in 24/7 mode
        cancelIdleTimeout();
        idleTask = PlayerManager.scheduledExecutor.schedule(() -> {
            try {
                logger.info("Idle timeout reached for guild: {}", guild.getName());

                // Send Bye Message as V2 Container
                sendSimpleEmbed(com.discord.musicbot.commands.framework.EmbedHelper.MSG_STOP
                        + " **Session Ended** — I've left the voice channel due to inactivity. Use `/play` to start a new session anytime!");

                disconnect();
            } catch (Exception e) {
                logger.error("Error in idle task", e);
            }
        }, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Cancel any pending idle timeout.
     */
    public void cancelIdleTimeout() {
        if (idleTask != null && !idleTask.isDone()) {
            idleTask.cancel(false);
        }
    }

    /**
     * Toggle 24/7 mode.
     */
    public boolean toggle247() {
        this.mode247 = com.discord.musicbot.data.DatabaseManager.getInstance().toggle247(guild.getId());
        if (mode247) {
            cancelIdleTimeout();
        }
        return mode247;
    }

    /**
     * Connect to voice channel without altering channel bitrate.
     */
    public void connectToVoiceChannel(net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion vc) {
        this.isDeliberateDisconnect = false;
        if (vc != null) {
            this.activeVoiceChannelId = vc.getId();
        }
        guild.getAudioManager().setSelfDeafened(true);
        guild.getAudioManager().openAudioConnection(vc);

        updateVoiceChannelStatus(com.discord.musicbot.config.EmojiConfig.getInstance().music + " Playing music!");
    }

    /**
     * Disconnect from voice channel.
     */
    public void disconnect() {
        markDeliberateDisconnect();
        updateVoiceChannelStatus();
        guild.getAudioManager().closeAudioConnection();
        scheduler.stop();
        if (mode247 && !PlayerManager.isShuttingDown) {
            mode247 = false;
            if (com.discord.musicbot.data.DatabaseManager.getInstance().is247(guild.getId())) {
                com.discord.musicbot.data.DatabaseManager.getInstance().toggle247(guild.getId());
            }
        }
        deleteNowPlayingMessage();
        com.discord.musicbot.data.SessionManager.getInstance().updateSnapshot(guild.getId(), null);
        PlayerManager.getInstance().removeMusicManager(guild.getIdLong());

        cancelIdleTimeout();
        if (aloneTask != null)
            aloneTask.cancel(true);
        if (watchdogTask != null)
            watchdogTask.cancel(true);
        if (karaokeTask != null)
            karaokeTask.cancel(true);
        player.destroy();
    }

    private volatile String nowPlayingChannelId;
    private volatile String nowPlayingMessageId;
    private final java.util.concurrent.atomic.AtomicBoolean isSendingNowPlaying = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger npUpdateVersion = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.ScheduledExecutorService npExecutor;

    public void setNowPlayingChannel(String channelId) {
        if (nowPlayingChannelId != null && !nowPlayingChannelId.equals(channelId)) {
            // Channel changed, delete old message to prevent 404 editing
            deleteNowPlayingMessage();
        }
        this.nowPlayingChannelId = channelId;
    }

    public String getNowPlayingChannelId() {
        return nowPlayingChannelId;
    }

    public synchronized net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel getAnnouncementChannel() {
        net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel tc = null;
        if (nowPlayingChannelId != null) {
            try {
                tc = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                        nowPlayingChannelId);
            } catch (Exception ignored) {
            }
        }
        if (tc == null) {
            try {
                com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager
                        .getInstance().getSettings(guild.getId());
                if (settings.getCommandChannelId() != null && !settings.getCommandChannelId().isEmpty()) {
                    tc = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                            settings.getCommandChannelId());
                }
            } catch (Exception ignored) {
            }
        }
        if (tc == null) {
            try {
                if (guild.getSystemChannel() != null && guild.getSelfMember().hasPermission(guild.getSystemChannel(),
                        net.dv8tion.jda.api.Permission.MESSAGE_SEND)) {
                    tc = guild.getSystemChannel();
                }
            } catch (Exception ignored) {
            }
        }
        if (tc == null) {
            try {
                for (net.dv8tion.jda.api.entities.channel.concrete.TextChannel ch : guild.getTextChannels()) {
                    if (guild.getSelfMember().hasPermission(ch, net.dv8tion.jda.api.Permission.MESSAGE_SEND,
                            net.dv8tion.jda.api.Permission.VIEW_CHANNEL)) {
                        tc = ch;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (tc != null && nowPlayingChannelId == null) {
            nowPlayingChannelId = tc.getId();
        }
        return tc;
    }

    // --- Now Playing Message ---

    private String formatTime(long duration) {
        long hours = duration / 3600000;
        long minutes = (duration / 60000) % 60;
        long seconds = (duration / 1000) % 60;
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private String getArtworkUrl(AudioTrack track) {
        if (track instanceof DeferredTrack def && def.getArtworkUrl() != null) {
            return def.getArtworkUrl();
        }
        if (track instanceof SpotifyResolvedTrack srt && srt.getArtworkUrl() != null) {
            return srt.getArtworkUrl();
        }
        if (track.getInfo().artworkUrl != null) {
            return track.getInfo().artworkUrl;
        }

        if (track.getInfo().uri.contains("youtube")) {
            return "https://img.youtube.com/vi/" + track.getInfo().identifier + "/mqdefault.jpg";
        }
        return "https://media.discordapp.net/attachments/12300000/12300000/icon.png";
    }

    public void sendNowPlayingMessage() {
        sendNowPlayingMessage(false, null);
    }

    public void sendNowPlayingMessage(boolean forceNew) {
        sendNowPlayingMessage(forceNew, null);
    }

    public void sendNowPlayingMessage(boolean forceNew,
            com.sedmelluq.discord.lavaplayer.track.AudioTrack trackOverride) {
        int version = npUpdateVersion.incrementAndGet();
        npExecutor.execute(() -> doSendNowPlayingMessage(forceNew, trackOverride, version));
    }

    private void doSendNowPlayingMessage(boolean forceNew,
            com.sedmelluq.discord.lavaplayer.track.AudioTrack trackOverride, int version) {
        if (!com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guild.getId()).isAnnounceTracks())
            return;
        if (!isSendingNowPlaying.compareAndSet(false, true))
            return;

        try {
            net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel channel = getAnnouncementChannel();
            if (channel == null) {
                isSendingNowPlaying.set(false);
                return;
            }

            com.sedmelluq.discord.lavaplayer.track.AudioTrack track = trackOverride != null ? trackOverride
                    : scheduler.getCurrentTrack();
            if (track == null) {
                isSendingNowPlaying.set(false);
                return;
            }

            java.util.List<net.dv8tion.jda.api.components.MessageTopLevelComponent> components = createNowPlayingContainer(
                    trackOverride);
            if (components == null || components.isEmpty()) {
                isSendingNowPlaying.set(false);
                return;
            }

            if (forceNew) {
                if (nowPlayingMessageId != null) {
                    final String oldId = nowPlayingMessageId;
                    nowPlayingMessageId = null;
                    channel.deleteMessageById(oldId).queue(null, e -> {});
                }
            }

            if (nowPlayingMessageId != null) {
                channel.editMessageComponentsById(nowPlayingMessageId, components)
                        .useComponentsV2()
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .queue(success -> {
                            isSendingNowPlaying.set(false);
                            if (npUpdateVersion.get() != version) {
                                doSendNowPlayingMessage(false, null, npUpdateVersion.get());
                            }
                        }, e -> {
                            nowPlayingMessageId = null;
                            channel.sendMessageComponents(components)
                                    .useComponentsV2()
                                    .setAllowedMentions(java.util.Collections.emptyList())
                                    .queue(msg -> {
                                        nowPlayingMessageId = msg.getId();
                                        isSendingNowPlaying.set(false);
                                        if (npUpdateVersion.get() != version) {
                                            doSendNowPlayingMessage(false, null, npUpdateVersion.get());
                                        }
                                    }, err -> isSendingNowPlaying.set(false));
                        });
            } else {
                channel.sendMessageComponents(components)
                        .useComponentsV2()
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .queue(msg -> {
                            nowPlayingMessageId = msg.getId();
                            isSendingNowPlaying.set(false);
                            if (npUpdateVersion.get() != version) {
                                doSendNowPlayingMessage(false, null, npUpdateVersion.get());
                            }
                        }, err -> isSendingNowPlaying.set(false));
            }
        } catch (Exception e) {
            isSendingNowPlaying.set(false);
        }
    }

    public java.util.List<net.dv8tion.jda.api.components.MessageTopLevelComponent> createNowPlayingContainer() {
        return createNowPlayingContainer(null);
    }

    public java.util.List<net.dv8tion.jda.api.components.MessageTopLevelComponent> createNowPlayingContainer(
            com.sedmelluq.discord.lavaplayer.track.AudioTrack trackOverride) {
        com.sedmelluq.discord.lavaplayer.track.AudioTrack track = trackOverride != null ? trackOverride
                : scheduler.getCurrentTrack();
        if (track == null)
            return null;

        String title = com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(track.getInfo().title);
        if (title.length() > 35) {
            title = title.substring(0, 35) + "...";
        }

        String requester = "Unknown";
        String requesterId = null;
        if (track.getUserData() instanceof net.dv8tion.jda.api.entities.User u) {
            requesterId = u.getId();
        } else if (track.getUserData() instanceof String) {
            String ud = (String) track.getUserData();
            if (ud.contains("\"requester\":\"")) {
                requesterId = ud.split("\"requester\":\"")[1].split("\"")[0];
            } else if (ud.matches("\\d+")) {
                requesterId = ud;
            } else {
                requester = ud;
            }
        }

        if (requesterId != null) {
            requester = "<@" + requesterId + ">";
        }

        boolean isPaused = scheduler.isPaused();
        String status = isPaused ? "Paused" : "Playing";

        boolean isMewsic = false;
        if (track.getUserData() instanceof String udStr) {
            if (udStr.contains("\"mewsic\":true")) {
                isMewsic = true;
            }
        }

        String authorName = isMewsic ? "Mewsic" : guild.getJDA().getSelfUser().getName();

        String desc = String.format(
                "**[%s](%s)**\n" + com.discord.musicbot.config.EmojiConfig.getInstance().queuedBy
                        + " **Queued by:** %s\n" + com.discord.musicbot.config.EmojiConfig.getInstance().duration
                        + " **Duration:** **%s**",
                title, track.getInfo().uri, requester, formatTime(track.getDuration()));

        String loopModeStr = scheduler.getLoopMode().name().charAt(0)
                + scheduler.getLoopMode().name().substring(1).toLowerCase();
        java.util.List<String> activeModes = new java.util.ArrayList<>();
        if (!loopModeStr.equals("Off"))
            activeModes.add(loopModeStr);
        if (scheduler.isAutoPlay())
            activeModes.add("Autoplay");
        if (scheduler.isRandomPlay())
            activeModes.add("Random");
        String loopStr = activeModes.isEmpty() ? "Off" : String.join(" + ", activeModes);

        StringBuilder footer = new StringBuilder();
        footer.append(String.format("Vol: %d%% | Loop: %s", player.getVolume(), loopStr));
        footer.append(String.format(" | Queue: %d tracks", scheduler.getQueue().size()));

        java.util.List<ContainerChildComponent> children = new java.util.ArrayList<>();

        String artworkUrl = getArtworkUrl(track);
        if (artworkUrl != null && !artworkUrl.isBlank()) {
            children.add(Section.of(
                    Thumbnail.fromUrl(artworkUrl),
                    TextDisplay.of("### " + authorName + " | " + status),
                    TextDisplay.of(desc),
                    TextDisplay.of("-# " + footer.toString())));
        } else {
            children.add(TextDisplay.of("### " + authorName + " | " + status));
            children.add(TextDisplay.of(desc));
            children.add(TextDisplay.of("-# " + footer.toString()));
        }

        Container container = Container.of(children)
                .withAccentColor(com.discord.musicbot.commands.framework.EmbedHelper.COLOR_MAIN);
        java.util.List<net.dv8tion.jda.api.components.MessageTopLevelComponent> components = new java.util.ArrayList<>();
        components.add(container);
        components.addAll(com.discord.musicbot.commands.framework.EmbedHelper.createNowPlayingComponents(this));
        return components;
    }

    // --- Alone Mode Logic ---

    public void onBotAlone() {
        logger.info("Bot is alone in voice channel. Scheduling 3 minute timeout action.");

        // Cancel any existing alone timer first
        if (aloneTask != null && !aloneTask.isDone()) {
            aloneTask.cancel(false);
        }

        // If the bot was already paused by a user, we shouldn't auto-resume later.
        // If it wasn't paused, we pause it now and mark it for auto-resume.
        if (scheduler.getCurrentTrack() != null) {
            if (!scheduler.isPaused()) {
                wasAlonePaused = true;
                scheduler.pause();
            }

            // Update now playing embed and voice channel status
            sendNowPlayingMessage(false, null);
            updateVoiceChannelStatus();
        }

        aloneTask = PlayerManager.scheduledExecutor.schedule(() -> {
            try {
                boolean isPlayingOrQueued = scheduler.getCurrentTrack() != null || !scheduler.getQueue().isEmpty();

                if (mode247) {
                    if (isPlayingOrQueued) {
                        logger.info("Alone timeout reached (24/7 mode) for guild: {}", guild.getName());
                        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager
                                .getInstance().getSettings(guild.getId());
                        if (settings.isMode247Locked()) {
                            logger.info(
                                    "Alone timeout reached (24/7 mode) for guild: {} - Session is LOCKED, ignoring.",
                                    guild.getName());
                        } else {
                            sendSimpleEmbed(com.discord.musicbot.commands.framework.EmbedHelper.MSG_STOP
                                    + " Stopped playback and cleared the queue because I was left alone for 3 minutes.");
                            scheduler.getQueue().clear();
                            scheduler.stop();
                            updateVoiceChannelStatus();
                        }
                    } else {
                        logger.info("Alone timeout reached (24/7 mode) for guild: {} - Already stopped, staying quiet.",
                                guild.getName());
                    }
                } else {
                    logger.info("Alone timeout reached for guild: {}", guild.getName());
                    if (isPlayingOrQueued) {
                        sendSimpleEmbed(com.discord.musicbot.commands.framework.EmbedHelper.MSG_STOP
                                + " Disconnected because I was left alone for 3 minutes.");
                    }
                    disconnect();
                }
            } catch (Exception e) {
                logger.error("Error in alone task", e);
            }
        }, 3, java.util.concurrent.TimeUnit.MINUTES);
    }

    public void onHumanJoined() {
        if (aloneTask != null && !aloneTask.isDone()) {
            aloneTask.cancel(false);
            logger.info("Human joined. Cancelled alone timer.");
        }

        if (wasAlonePaused) {
            logger.info("Auto-resuming playback.");
            scheduler.resume();
            wasAlonePaused = false;

            // Update now playing embed and voice channel status
            sendNowPlayingMessage(false, null);
            updateVoiceChannelStatus();
        }
    }

    public void stopButStayConnected() {
        scheduler.stop();
        deleteNowPlayingMessage();
        logger.info("Stopped playback but stayed connected (24/7 mode).");
    }

    /**
     * Send a simple embed message to the last known text channel.
     */
    private void sendSimpleEmbed(String message) {
        try {
            net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel ch = getAnnouncementChannel();
            if (ch != null) {
                var container = net.dv8tion.jda.api.components.container.Container.of(
                        net.dv8tion.jda.api.components.textdisplay.TextDisplay.of(message))
                        .withAccentColor(com.discord.musicbot.commands.framework.EmbedHelper.COLOR_MAIN);
                ch.sendMessageComponents(container).useComponentsV2().queue();
            }
        } catch (Exception e) {
            logger.debug("Failed to send simple embed", e);
        }
    }

    /**
     * Update voice channel status to show current track (matches JS bot behavior)
     * NOTE: Requires JDA version with setStatus() support - currently commented out
     */
    /**
     * Update voice channel status to show current track (matches JS bot behavior)
     * NOTE: Requires JDA version with setStatus() support.
     */
    public void updateVoiceChannelStatus() {
        if (!com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guild.getId())
                .isUpdateVcStatus()) {
            clearVoiceChannelStatus();
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (now - lastVCStatusUpdate < VC_STATUS_COOLDOWN) {
                return; // Skip update if too soon
            }
            lastVCStatusUpdate = now;

            AudioTrack currentTrack = scheduler.getCurrentTrack();

            // Get voice channel
            net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel voiceChannel = null;
            if (guild.getSelfMember().getVoiceState() != null
                    && guild.getSelfMember().getVoiceState().getChannel() != null) {
                if (guild.getSelfMember().getVoiceState()
                        .getChannel() instanceof net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel) {
                    voiceChannel = (net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel) guild.getSelfMember()
                            .getVoiceState().getChannel();
                }
            }

            if (voiceChannel == null)
                return;

            // Check permissions
            if (!guild.getSelfMember().hasPermission(voiceChannel,
                    net.dv8tion.jda.api.Permission.MANAGE_CHANNEL)) {
                return;
            }

            String status = "";
            if (currentTrack != null && !scheduler.isPaused()) {
                String title = com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(currentTrack.getInfo().title);
                boolean isMewsic = false;
                if (currentTrack.getUserData() instanceof String udStr) {
                    if (udStr.contains("\"mewsic\":true")) {
                        isMewsic = true;
                    }
                }

                String prefix = isMewsic ? com.discord.musicbot.config.EmojiConfig.getInstance().mewsic + " Mewsic: "
                        : "";
                status = prefix + title;
                if (status.length() > 500) {
                    status = status.substring(0, 497) + "...";
                }
            }

            // Update voice channel status (silent fail on error)
            // Note: JDA 5.0.0-beta.13 might use differnet method signature or require
            // specific cast
            voiceChannel.modifyStatus(status).queue(null, e -> {
            });

        } catch (Exception e) {
            // Silent fail for voice status updates
        }
    }

    public void clearVoiceChannelStatus() {
        try {
            net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel voiceChannel = null;
            if (guild.getSelfMember().getVoiceState() != null
                    && guild.getSelfMember().getVoiceState().getChannel() != null) {
                if (guild.getSelfMember().getVoiceState()
                        .getChannel() instanceof net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel) {
                    voiceChannel = (net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel) guild.getSelfMember()
                            .getVoiceState().getChannel();
                }
            }

            if (voiceChannel == null)
                return;

            if (!guild.getSelfMember().hasPermission(voiceChannel,
                    net.dv8tion.jda.api.Permission.MANAGE_CHANNEL)) {
                return;
            }

            voiceChannel.modifyStatus("").queue(null, e -> {
            });
        } catch (Exception e) {
        }
    }

    private long lastNPUpdate = 0;

    public void refreshLastNPUpdate() {
        lastNPUpdate = System.currentTimeMillis();
    }

    public void updateNowPlayingMessage() {
        long now = System.currentTimeMillis();
        if (now - lastNPUpdate < 500)
            return;
        lastNPUpdate = now;
        sendNowPlayingMessage();
    }

    public void deleteNowPlayingMessage() {
        deleteNowPlayingMessage(false);
    }

    public void deleteNowPlayingMessage(boolean blocking) {
        if (nowPlayingChannelId != null && nowPlayingMessageId != null) {
            net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel channel = null;
            try {
                channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                        nowPlayingChannelId);
            } catch (Exception ignored) {
            }

            if (channel != null) {
                String msgId = nowPlayingMessageId;
                nowPlayingMessageId = null;
                if (blocking) {
                    channel.deleteMessageById(msgId).queue(null, e -> {});
                } else {
                    channel.deleteMessageById(msgId).queue(null, e -> {
                    });
                }
            } else {
                nowPlayingMessageId = null;
            }
        }
    }

    /**
     * Clean up resources.
     */
    public SessionSnapshot toSessionSnapshot() {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.guildId = guild.getId();
        var voiceState = guild.getSelfMember().getVoiceState();
        if (voiceState != null && voiceState.getChannel() != null) {
            activeVoiceChannelId = voiceState.getChannel().getId();
        } else if (guild.getAudioManager().getConnectedChannel() != null) {
            activeVoiceChannelId = guild.getAudioManager().getConnectedChannel().getId();
        } else if (activeVoiceChannelId == null) {
            var settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guild.getId());
            if (settings.getLockedVoiceChannelId() != null && !settings.getLockedVoiceChannelId().isEmpty()) {
                activeVoiceChannelId = settings.getLockedVoiceChannelId();
            }
        }
        snapshot.voiceChannelId = activeVoiceChannelId;
        if (nowPlayingChannelId == null) {
            var settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guild.getId());
            if (settings.getCommandChannelId() != null && !settings.getCommandChannelId().isEmpty()) {
                nowPlayingChannelId = settings.getCommandChannelId();
            }
        }
        snapshot.textChannelId = nowPlayingChannelId;

        AudioTrack current = scheduler.getCurrentTrack();
        if (current != null) {
            snapshot.currentTrackEncoded = PlayerManager.getInstance().encodeAudioTrack(current);
            snapshot.currentPosition = current.getPosition();
            // Preserve requester info
            if (current.getUserData() instanceof String) {
                snapshot.currentRequester = (String) current.getUserData();
            }
        }

        List<String> encodedQueue = new ArrayList<>();
        List<String> queueRequesters = new ArrayList<>();
        for (AudioTrack t : scheduler.getQueue()) {
            String encoded = PlayerManager.getInstance().encodeAudioTrack(t);
            if (encoded != null) {
                encodedQueue.add(encoded);
                if (t.getUserData() instanceof String) {
                    queueRequesters.add((String) t.getUserData());
                } else {
                    queueRequesters.add(null);
                }
            }
        }
        snapshot.queueEncoded = encodedQueue;
        snapshot.queueRequesters = queueRequesters;

        snapshot.volume = scheduler.getVolume();
        snapshot.loopMode = scheduler.getLoopMode().name();
        snapshot.autoplay = scheduler.getAutoplay();
        snapshot.randomPlay = scheduler.isRandomPlay();
        snapshot.mode247 = mode247;
        snapshot.isPaused = scheduler.isPaused();
        snapshot.wasAlonePaused = wasAlonePaused;

        return snapshot;
    }

    public void restoreFromSnapshot(SessionSnapshot snapshot) {
        this.activeVoiceChannelId = snapshot.voiceChannelId;
        if (snapshot.voiceChannelId != null) {
            net.dv8tion.jda.api.entities.channel.middleman.AudioChannel vc = guild
                    .getChannelById(net.dv8tion.jda.api.entities.channel.middleman.AudioChannel.class,
                            snapshot.voiceChannelId);
            if (vc == null)
                vc = guild.getVoiceChannelById(snapshot.voiceChannelId);
            if (vc == null)
                vc = guild.getStageChannelById(snapshot.voiceChannelId);
            if (vc != null) {
                if (guild.getSelfMember().getVoiceState() != null
                        && guild.getSelfMember().getVoiceState().inAudioChannel()) {
                    markDeliberateDisconnect();
                    guild.getAudioManager().closeAudioConnection();
                }
                try {
                    Thread.sleep(100);
                } catch (Exception ignored) {
                }
                guild.getAudioManager().setSelfDeafened(true);
                guild.getAudioManager().openAudioConnection(vc);
            }
        }

        this.nowPlayingChannelId = snapshot.textChannelId;
        this.mode247 = snapshot.mode247;

        scheduler.setVolume(snapshot.volume);
        try {
            scheduler.setLoopMode(TrackScheduler.LoopMode.valueOf(snapshot.loopMode));
        } catch (Exception ignored) {
        }
        if (snapshot.autoplay != scheduler.getAutoplay()) {
            scheduler.toggleAutoplay();
        }
        scheduler.setRandomPlay(snapshot.randomPlay);
        scheduler.setPaused(snapshot.isPaused);

        this.wasAlonePaused = snapshot.wasAlonePaused;

        if (snapshot.currentTrackEncoded != null) {
            AudioTrack current = PlayerManager.getInstance().decodeAudioTrack(snapshot.currentTrackEncoded);
            if (current != null) {
                current.setPosition(snapshot.currentPosition);
                // Restore requester userData
                if (snapshot.currentRequester != null) {
                    current.setUserData(snapshot.currentRequester);
                }
                scheduler.restoreCurrentTrack(current);
            }
        }

        if (snapshot.queueEncoded != null) {
            for (int i = 0; i < snapshot.queueEncoded.size(); i++) {
                AudioTrack track = PlayerManager.getInstance().decodeAudioTrack(snapshot.queueEncoded.get(i));
                if (track != null) {
                    // Restore requester userData for queued tracks
                    if (snapshot.queueRequesters != null && i < snapshot.queueRequesters.size()
                            && snapshot.queueRequesters.get(i) != null) {
                        track.setUserData(snapshot.queueRequesters.get(i));
                    }
                    scheduler.getQueueRaw().add(track);
                }
            }
        }

        if (scheduler.getCurrentTrack() == null && !scheduler.getQueueRaw().isEmpty()) {
            scheduler.nextTrack();
        }
        if (snapshot.isPaused) {
            scheduler.pause();
        }

        // Force the Now Playing panel to re-render so the user knows it recovered
        if (scheduler.getCurrentTrack() != null) {
            updateNowPlayingMessage();
        }
        updateVoiceChannelStatus();
    }

    public void notifySessionChanged() {
        try {
            SessionManager.getInstance().updateSnapshot(guild.getId(), toSessionSnapshot());
        } catch (Exception e) {
            logger.debug("Failed to save session snapshot", e);
        }
    }

    public void destroy() {
        markDeliberateDisconnect();
        if (!PlayerManager.isShuttingDown) {
            // Reset persisted states so they don't leak into the next session
            try {
                com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager
                        .getInstance().getSettings(guild.getId());
                if (mode247) {
                    mode247 = false;
                    settings.setMode247(false);
                    if (com.discord.musicbot.data.DatabaseManager.getInstance().is247(guild.getId())) {
                        com.discord.musicbot.data.DatabaseManager.getInstance().toggle247(guild.getId());
                    }
                }
                if (settings.isAutoplay() || settings.isRandomPlay()) {
                    settings.setAutoplay(false);
                    settings.setRandomPlay(false);
                }
                com.discord.musicbot.data.GuildSettingsManager.getInstance().markDirty();
            } catch (Exception e) {
                logger.warn("Failed to reset persisted states on destroy: {}", e.getMessage());
            }

            try {
                updateVoiceChannelStatus(""); // Clear the voice channel status
                SessionManager.getInstance().updateSnapshot(guild.getId(), null);
            } catch (Exception ignored) {
            }
        }
        cancelIdleTimeout();
        if (aloneTask != null)
            aloneTask.cancel(true);
        if (watchdogTask != null)
            watchdogTask.cancel(true);
        if (karaokeTask != null)
            karaokeTask.cancel(true);

        try {
            deleteNowPlayingMessage(true); // Blocking delete
        } catch (Exception ignored) {
        }
        player.destroy();
        logger.debug("MusicManager destroyed for guild: {}", guild.getName());
    }

    /**
     * Graceful cleanup for bot shutdown.
     */
    public void cleanup() {
        markDeliberateDisconnect();
        try {
            SessionManager.getInstance().updateSnapshot(guild.getId(), toSessionSnapshot()); // Force save before
                                                                                             // shutdown (must be done
                                                                                             // before
                                                                                             // closeAudioConnection)
        } catch (Exception e) {
            logger.warn("Failed to force save session: {}", e.getMessage());
        }

        // Send voice disconnection opcode immediately over WebSocket before blocking on
        // REST API calls
        try {
            guild.getAudioManager().closeAudioConnection();
            guild.getJDA().getDirectAudioController().disconnect(guild);
        } catch (Exception ignored) {
        }

        try {
            var voiceState = guild.getSelfMember().getVoiceState();
            if (voiceState != null && voiceState
                    .getChannel() instanceof net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel vc) {
                vc.modifyStatus("").submit().get(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.warn("Failed to clear VC status on shutdown: {}", e.getMessage());
        }

        try {
            if (nowPlayingMessageId != null && nowPlayingChannelId != null) {
                net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel ch = guild
                        .getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                                nowPlayingChannelId);
                if (ch != null) {
                    ch.deleteMessageById(nowPlayingMessageId).submit().get(2, java.util.concurrent.TimeUnit.SECONDS);
                }
                nowPlayingMessageId = null;
            }
        } catch (Exception e) {
            logger.warn("Failed to delete NP message on shutdown: {}", e.getMessage());
        }

        cancelIdleTimeout();
        if (aloneTask != null)
            aloneTask.cancel(true);
        if (watchdogTask != null)
            watchdogTask.cancel(true);
        if (karaokeTask != null)
            karaokeTask.cancel(true);

        scheduler.cleanup();
        player.destroy();
        logger.debug("MusicManager cleanly shutdown for guild: {}", guild.getName());
    }

    private String lastVCStatusString = "";

    public void updateVoiceChannelStatus(String status) {
        if (!com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guild.getId()).isUpdateVcStatus())
            return;

        String cleanStatus = status == null ? "" : status;
        if (cleanStatus.length() > 500) {
            cleanStatus = cleanStatus.substring(0, 497) + "...";
        }

        long now = System.currentTimeMillis();
        // Drop update if under cooldown AND the status hasn't changed.
        // If the status HAS changed (e.g., quick pause -> resume), allow it through.
        if (now - lastVCStatusUpdate < VC_STATUS_COOLDOWN && cleanStatus.equals(lastVCStatusString)) {
            return;
        }

        try {
            var voiceState = guild.getSelfMember().getVoiceState();
            if (voiceState == null)
                return;
            net.dv8tion.jda.api.entities.channel.middleman.AudioChannel channel = voiceState.getChannel();
            if (channel instanceof net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel vc) {
                lastVCStatusUpdate = now;
                lastVCStatusString = cleanStatus;
                vc.modifyStatus(cleanStatus).queue(null, e -> logger.warn("Failed to update VC status", e));
            }
        } catch (Exception e) {
            logger.debug("VC status update failed", e);
        }
    }
}
