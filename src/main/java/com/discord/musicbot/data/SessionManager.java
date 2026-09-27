package com.discord.musicbot.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final String SESSION_FILE = "sessions.json";
    private static final String SESSION_FILE_TMP = "sessions.json.tmp";
    private static SessionManager instance;

    private final ObjectMapper mapper;
    private final Map<String, SessionSnapshot> snapshots = new HashMap<>();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private SessionManager() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        load();

        executor.scheduleWithFixedDelay(() -> {
            if (dirty.getAndSet(false)) {
                save();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public static synchronized SessionManager getInstance() {
        if (instance == null) {
            instance = new SessionManager();
        }
        return instance;
    }

    public static class SessionSnapshot {
        public String guildId;
        public String voiceChannelId;
        public String textChannelId;
        public String currentTrackEncoded;
        public String currentRequester;
        public long currentPosition;
        public List<String> queueEncoded;
        public List<String> queueRequesters;
        public int volume;
        public String loopMode;
        public boolean autoplay;
        public boolean randomPlay;
        public boolean mode247;
        public boolean isPaused;
        public boolean wasAlonePaused;
    }

    public void updateSnapshot(String guildId, SessionSnapshot snapshot) {
        synchronized (snapshots) {
            if (snapshot == null) {
                snapshots.remove(guildId);
            } else {
                snapshots.put(guildId, snapshot);
            }
        }
        dirty.set(true);
    }

    public void saveAllNow() {
        if (dirty.getAndSet(false)) {
            save();
        }
    }

    private synchronized void save() {
        try {
            File tempFile = new File(SESSION_FILE_TMP);
            File actualFile = new File(SESSION_FILE);
            
            Map<String, SessionSnapshot> copy;
            synchronized (snapshots) {
                copy = new HashMap<>(snapshots);
            }

            mapper.writeValue(tempFile, copy);
            try {
                Files.move(tempFile.toPath(), actualFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ex) {
                Files.move(tempFile.toPath(), actualFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            logger.debug("Sessions saved to disk.");
        } catch (IOException e) {
            logger.error("Failed to save sessions", e);
        }
    }

    private synchronized void load() {
        File actualFile = new File(SESSION_FILE);
        if (!actualFile.exists()) return;

        try {
            com.fasterxml.jackson.core.type.TypeReference<Map<String, SessionSnapshot>> typeRef = new com.fasterxml.jackson.core.type.TypeReference<>() {};
            Map<String, SessionSnapshot> loaded = mapper.readValue(actualFile, typeRef);
            if (loaded != null) {
                synchronized (snapshots) {
                    snapshots.putAll(loaded);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load sessions", e);
        }
    }

    public void restoreAll(JDA jda) {
        logger.info("Restoring sessions...");
        Map<String, SessionSnapshot> copy;
        synchronized (snapshots) {
            copy = new HashMap<>(snapshots);
        }

        for (Map.Entry<String, SessionSnapshot> entry : copy.entrySet()) {
            String guildId = entry.getKey();
            SessionSnapshot snapshot = entry.getValue();

            var guild = jda.getGuildById(guildId);
            if (guild == null) {
                updateSnapshot(guildId, null);
                continue;
            }

            if (snapshot.voiceChannelId == null) {
                var settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guildId);
                if (settings.getLockedVoiceChannelId() != null && !settings.getLockedVoiceChannelId().isEmpty()) {
                    snapshot.voiceChannelId = settings.getLockedVoiceChannelId();
                } else {
                    var selfVs = guild.getSelfMember().getVoiceState();
                    if (selfVs != null && selfVs.getChannel() != null) {
                        snapshot.voiceChannelId = selfVs.getChannel().getId();
                    }
                }
            }

            if (snapshot.voiceChannelId == null) {
                logger.warn("No voice channel ID found for session in guild {}. Discarding stale session.", guild.getName());
                updateSnapshot(guildId, null);
                continue;
            }

            net.dv8tion.jda.api.entities.channel.middleman.AudioChannel voiceChannel = guild
                    .getChannelById(net.dv8tion.jda.api.entities.channel.middleman.AudioChannel.class, snapshot.voiceChannelId);
            if (voiceChannel == null) voiceChannel = guild.getVoiceChannelById(snapshot.voiceChannelId);
            if (voiceChannel == null) voiceChannel = guild.getStageChannelById(snapshot.voiceChannelId);
            if (voiceChannel == null) {
                logger.warn("Voice channel {} not found for guild {}. Discarding stale session.", snapshot.voiceChannelId, guild.getName());
                updateSnapshot(guildId, null);
                continue;
            }

            try {
                com.discord.musicbot.audio.MusicManager manager = com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(guild);
                manager.restoreFromSnapshot(snapshot);
                logger.info("Restored session for guild {}", guild.getName());
            } catch (Exception e) {
                logger.error("Failed to restore session for guild " + guildId, e);
                updateSnapshot(guildId, null);
            }
        }
    }

    public void shutdown() {
        saveAllNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
