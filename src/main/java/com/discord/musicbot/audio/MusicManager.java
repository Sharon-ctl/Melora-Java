package com.discord.musicbot.audio;

import com.discord.musicbot.data.SessionManager;
import com.discord.musicbot.data.SessionManager.SessionSnapshot;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.EmbedBuilder;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * MusicManager - Per-guild audio player and queue manager.
 */
public class MusicManager {

    private static final Logger logger = LoggerFactory.getLogger(MusicManager.class);
    private static final long IDLE_TIMEOUT_SECONDS = 180; // 3 minutes

    private final AudioPlayer player;
    private final TrackScheduler scheduler;
    private final AudioPlayerSendHandler sendHandler;
    private final Guild guild;

    private final ScheduledExecutorService idleExecutor;
    private ScheduledFuture<?> idleTask;

    // Alone Mode
    private final ScheduledExecutorService aloneExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> aloneTask;
    private boolean wasAlonePaused = false;
    private boolean mode247 = false;

    // Voice channel status cooldown (matches JS bot)
    private long lastVCStatusUpdate = 0;
    private static final long VC_STATUS_COOLDOWN = 3000; // 3 seconds

    // Stall Watchdog
    private final ScheduledExecutorService watchdogExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> watchdogTask;
    private long lastWatchdogAction = 0;
    private int stallCount = 0;

    public MusicManager(AudioPlayerManager manager, Guild guild) {
        this.guild = guild;
        this.player = manager.createPlayer();
        this.scheduler = new TrackScheduler(player, this);
        this.sendHandler = new AudioPlayerSendHandler(player);
        this.idleExecutor = Executors.newSingleThreadScheduledExecutor();

        player.addListener(scheduler);

        // Load 24/7 setting
        this.mode247 = com.discord.musicbot.data.DatabaseManager.getInstance().is247(guild.getId());

        // Start Watchdog
        this.watchdogTask = watchdogExecutor.scheduleAtFixedRate(() -> {
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
        }, 5, 5, TimeUnit.SECONDS);

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
        idleTask = idleExecutor.schedule(() -> {
            logger.info("Idle timeout reached for guild: {}", guild.getName());

            // Send Bye Message
            if (nowPlayingChannelId != null) {
                try {
                    net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel ch = guild.getChannelById(
                            net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                            nowPlayingChannelId);
                    if (ch != null) {
                        ch.sendMessage(com.discord.musicbot.commands.framework.EmbedHelper.MSG_STOP
                                + " Disconnected due to inactivity. Bye!").queue();
                    }
                } catch (Exception e) {
                }
            }

            disconnect();
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
     * Disconnect from voice channel.
     */
    public void disconnect() {
        updateVoiceChannelStatus(""); // Clear the voice channel status
        guild.getAudioManager().closeAudioConnection();
        scheduler.stop();
        deleteNowPlayingMessage();
        com.discord.musicbot.data.SessionManager.getInstance().updateSnapshot(guild.getId(), null);
        PlayerManager.getInstance().removeMusicManager(guild.getIdLong());
    }

    private String nowPlayingChannelId;
    private String nowPlayingMessageId;
    private boolean isSendingNowPlaying = false;

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

    // createProgressBar removed as it is no longer used for the JS-parity embed

    private String getArtworkUrl(AudioTrack track) {
        if (track.getInfo().artworkUrl != null) {
            return track.getInfo().artworkUrl;
        }

        if (track.getInfo().uri.contains("youtube")) {
            return "https://img.youtube.com/vi/" + track.getInfo().identifier + "/mqdefault.jpg";
        }
        return "https://media.discordapp.net/attachments/12300000/12300000/icon.png";
    }

    public void sendNowPlayingMessage() {
        sendNowPlayingMessage(false);
    }

    public void sendNowPlayingMessage(boolean forceNew) {
        if (nowPlayingChannelId == null)
            return;

        AudioTrack track = scheduler.getCurrentTrack();
        if (track == null)
            return;

        net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel channel = null;
        try {
            channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                    nowPlayingChannelId);
        } catch (Exception e) {
        }

        if (channel == null)
            return;

        final net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel finalChannel = channel;
        net.dv8tion.jda.api.entities.MessageEmbed embed = createNowPlayingEmbed();
        if (embed == null)
            return;

        List<net.dv8tion.jda.api.components.actionrow.ActionRow> components = com.discord.musicbot.commands.framework.EmbedHelper
                .createNowPlayingComponents(this);

        if (forceNew) {
            if (nowPlayingMessageId != null) {
                finalChannel.deleteMessageById(nowPlayingMessageId).queue(null, e -> {
                });
                nowPlayingMessageId = null;
            }
        }

        // Message Recovery: If ID is missing but we want to EDIT (forceNew=false), try
        // to find an existing message
        if (nowPlayingMessageId == null && !forceNew) {
            if (isSendingNowPlaying)
                return; // Prevent concurrent duplicate sends
            isSendingNowPlaying = true;
            finalChannel.getHistory().retrievePast(10).queue(messages -> {
                for (net.dv8tion.jda.api.entities.Message msg : messages) {
                    if (msg.getAuthor().equals(guild.getJDA().getSelfUser()) && !msg.getEmbeds().isEmpty()) {
                        // Found a candidate, adopt it
                        nowPlayingMessageId = msg.getId();
                        break;
                    }
                }
                isSendingNowPlaying = false;
                // Proceed with edit/send
                finalizeNowPlayingMessage(finalChannel, embed, components);
            }, e -> {
                isSendingNowPlaying = false;
                // History fetch failed, just send new
                finalizeNowPlayingMessage(finalChannel, embed, components);
            });
            return;
        }

        finalizeNowPlayingMessage(finalChannel, embed, components);
    }

    private void finalizeNowPlayingMessage(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel channel,
            net.dv8tion.jda.api.entities.MessageEmbed embed,
            List<net.dv8tion.jda.api.components.actionrow.ActionRow> components) {
        if (nowPlayingMessageId != null) {
            channel.editMessageEmbedsById(nowPlayingMessageId, embed)
                    .setComponents(components)
                    .queue(null, e -> {
                        nowPlayingMessageId = null;
                        channel.sendMessageEmbeds(embed)
                                .setComponents(components)
                                .queue(msg -> nowPlayingMessageId = msg.getId());
                    });
        } else {
            channel.sendMessageEmbeds(embed)
                    .setComponents(components)
                    .queue(msg -> nowPlayingMessageId = msg.getId());
        }
    }

    public net.dv8tion.jda.api.entities.MessageEmbed createNowPlayingEmbed() {
        AudioTrack track = scheduler.getCurrentTrack();
        if (track == null)
            return null;

        // Truncate title to 35 chars (index.js logic)
        String title = track.getInfo().title;
        if (title.length() > 35) {
            title = title.substring(0, 35) + "...";
        }

        String requester = "Unknown";
        if (track.getUserData() instanceof net.dv8tion.jda.api.entities.User) {
            requester = ((net.dv8tion.jda.api.entities.User) track.getUserData()).getAsMention();
        } else if (track.getUserData() instanceof String) {
            String ud = (String) track.getUserData();
            if (ud.contains("\"requester\":\"")) {
                String id = ud.split("\"requester\":\"")[1].split("\"")[0];
                requester = "<@" + id + ">";
            } else if (ud.matches("\\d+")) {
                requester = "<@" + ud + ">";
            } else {
                requester = ud;
            }
        }

        boolean isPaused = scheduler.isPaused();
        String status = isPaused ? "Paused" : "Playing";

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(0x2f3136);
        embed.setAuthor(guild.getJDA().getSelfUser().getName() + " | " + status, null,
                guild.getJDA().getSelfUser().getAvatarUrl());

        String desc = String.format(
                "**[%s](%s)**\n> <:queuedby3:1504769635420082248> **Queued by:** %s\n> <:duration3:1504769632441991238> **Duration:** **%s**",
                title, track.getInfo().uri, requester, formatTime(track.getDuration()));
        embed.setDescription(desc);
        embed.setThumbnail(getArtworkUrl(track));

        String loopStr = scheduler.isAutoPlay() ? "Autoplay"
                : (scheduler.getLoopMode().name().charAt(0)
                        + scheduler.getLoopMode().name().substring(1).toLowerCase());

        embed.setFooter(String.format("Queue: %d tracks | Loop: %s | 24/7: %s",
                scheduler.getQueue().size(), loopStr, mode247 ? "On" : "Off"));

        return embed.build();
    }

    // --- Alone Mode Logic ---

    public void onAlone() {
        if (player.getPlayingTrack() == null)
            return;

        logger.info("Bot is alone in guild: " + guild.getName() + ". Starting alone timer.");

        // Cancel existing
        if (aloneTask != null && !aloneTask.isDone())
            aloneTask.cancel(false);

        // Instantly pause
        if (!scheduler.isPaused()) {
            scheduler.pause();
            wasAlonePaused = true;
            logger.info("Paused playback due to alone mode.");
        }

        aloneTask = aloneExecutor.schedule(() -> {
            logger.info("Alone timeout reached (3 min). Processing disconnect.");
            if (mode247) {
                stopButStayConnected();
            } else {
                disconnect();
            }
        }, 3, TimeUnit.MINUTES);
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
        }
    }

    public void stopButStayConnected() {
        scheduler.stop();
        scheduler.clear();
        player.destroy();
        deleteNowPlayingMessage();
        logger.info("Stopped playback but stayed connected (24/7 mode).");
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
                String title = currentTrack.getInfo().title;
                status = title.length() > 500 ? title.substring(0, 497) + "..." : title;
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
                final net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel finalChannel = channel;
                if (blocking) {
                    try {
                        finalChannel.deleteMessageById(nowPlayingMessageId).complete();
                    } catch (Exception ignored) {
                    }
                } else {
                    finalChannel.deleteMessageById(nowPlayingMessageId).queue(null, e -> {
                    });
                }
            }
            nowPlayingMessageId = null;
        }
    }

    /**
     * Clean up resources.
     */
    public SessionSnapshot toSessionSnapshot() {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.guildId = guild.getId();
        var voiceState = guild.getSelfMember().getVoiceState();
        snapshot.voiceChannelId = voiceState != null && voiceState.getChannel() != null
                ? voiceState.getChannel().getId()
                : null;
        snapshot.textChannelId = nowPlayingChannelId;

        AudioTrack current = scheduler.getCurrentTrack();
        if (current != null && !(current instanceof DeferredTrack)) {
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
            if (t instanceof DeferredTrack deferred) {
                String encoded = "DEFERRED:" + deferred.getQuery() + ":" + (deferred.getArtworkUrl() == null ? "null" : deferred.getArtworkUrl());
                encodedQueue.add(encoded);
                if (t.getUserData() instanceof String) {
                    queueRequesters.add((String) t.getUserData());
                } else {
                    queueRequesters.add(null);
                }
                continue;
            }
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
        snapshot.mode247 = mode247;

        return snapshot;
    }

    public void restoreFromSnapshot(SessionSnapshot snapshot) {
        if (snapshot.voiceChannelId != null) {
            net.dv8tion.jda.api.entities.channel.middleman.AudioChannel vc = guild
                    .getVoiceChannelById(snapshot.voiceChannelId);
            if (vc != null) {
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

        if (snapshot.currentTrackEncoded != null) {
            AudioTrack current = PlayerManager.getInstance().decodeAudioTrack(snapshot.currentTrackEncoded);
            if (current != null) {
                current.setPosition(snapshot.currentPosition);
                // Restore requester userData
                if (snapshot.currentRequester != null) {
                    current.setUserData(snapshot.currentRequester);
                }
                player.startTrack(current, false);
            }
        }

        if (snapshot.queueEncoded != null) {
            for (int i = 0; i < snapshot.queueEncoded.size(); i++) {
                if (snapshot.queueEncoded.get(i).startsWith("DEFERRED:")) {
                    String[] parts = snapshot.queueEncoded.get(i).split(":", 3);
                    if (parts.length >= 3) {
                        String query = parts[1];
                        String art = parts[2].equals("null") ? null : parts[2];
                        com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                                query.replace("ytsearch:", ""), "Spotify", 0, "spotify", true, query);
                        DeferredTrack track = new DeferredTrack(info, query, art);
                        if (snapshot.queueRequesters != null && i < snapshot.queueRequesters.size()
                                && snapshot.queueRequesters.get(i) != null) {
                            track.setUserData(snapshot.queueRequesters.get(i));
                        }
                        scheduler.getQueueRaw().add(track);
                    }
                    continue;
                }
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
    }

    public void notifySessionChanged() {
        try {
            SessionManager.getInstance().updateSnapshot(guild.getId(), toSessionSnapshot());
        } catch (Exception e) {
            logger.debug("Failed to save session snapshot", e);
        }
    }

    public void destroy() {
        try {
            updateVoiceChannelStatus(""); // Clear the voice channel status
            SessionManager.getInstance().updateSnapshot(guild.getId(), null);
        } catch (Exception ignored) {
        }
        cancelIdleTimeout();
        idleExecutor.shutdownNow();
        if (aloneTask != null)
            aloneTask.cancel(true);
        aloneExecutor.shutdownNow();

        if (watchdogTask != null)
            watchdogTask.cancel(true);
        watchdogExecutor.shutdownNow();

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
        try {
            if (guild.getAudioManager().isConnected()) {
                guild.getAudioManager().closeAudioConnection();
            }
        } catch (Exception ignored) {}
        
        try {
            notifySessionChanged(); // Force save before shutdown
        } catch (Exception ignored) {}

        try {
            updateVoiceChannelStatus("");
            deleteNowPlayingMessage(true);
        } catch (Exception ignored) {}

        cancelIdleTimeout();
        idleExecutor.shutdownNow();
        if (aloneTask != null) aloneTask.cancel(true);
        aloneExecutor.shutdownNow();
        if (watchdogTask != null) watchdogTask.cancel(true);
        watchdogExecutor.shutdownNow();

        player.destroy();
        logger.debug("MusicManager cleanly shutdown for guild: {}", guild.getName());
    }

    private String lastVCStatusString = "";

    public void updateVoiceChannelStatus(String status) {
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
