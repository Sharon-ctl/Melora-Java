package com.discord.musicbot.data;

import com.discord.musicbot.data.model.GuildSettings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuildSettingsManager {
    private static final Logger logger = LoggerFactory.getLogger(GuildSettingsManager.class);
    private static final String DB_FILE = "guild_settings.json";
    
    private static GuildSettingsManager instance;
    private final ObjectMapper mapper;
    private final File file;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, GuildSettings> cache;

    private GuildSettingsManager() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.file = new File(DB_FILE);
        this.cache = new ConcurrentHashMap<>();
        
        load();

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        this.executor.scheduleWithFixedDelay(() -> {
            if (dirty.getAndSet(false)) {
                save();
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    public static synchronized GuildSettingsManager getInstance() {
        if (instance == null) {
            instance = new GuildSettingsManager();
        }
        return instance;
    }

    private synchronized void load() {
        if (file.exists()) {
            try {
                ConcurrentHashMap<String, GuildSettings> loaded = mapper.readValue(file, new TypeReference<>() {});
                if (loaded != null) {
                    cache.putAll(loaded);
                }
            } catch (Exception e) {
                logger.error("Failed to load guild settings", e);
            }
        }
    }

    public synchronized void save() {
        try {
            File tempFile = new File(DB_FILE + ".tmp");
            mapper.writeValue(tempFile, cache);
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ex) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Failed to save guild settings", e);
        }
    }

    public GuildSettings getSettings(String guildId) {
        return cache.computeIfAbsent(guildId, k -> {
            dirty.set(true);
            return new GuildSettings();
        });
    }

    public void markDirty() {
        dirty.set(true);
    }

    public void shutdown() {
        save();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
