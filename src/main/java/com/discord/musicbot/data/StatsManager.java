package com.discord.musicbot.data;

import com.discord.musicbot.data.model.UserStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class StatsManager {
    private static final Logger logger = LoggerFactory.getLogger(StatsManager.class);
    private static final String STATS_DIR = "stats";
    
    private static StatsManager instance;
    private final ObjectMapper mapper;
    private final File statsDir;
    private final Map<String, UserStats> cache;
    private final java.util.concurrent.ExecutorService saveExecutor;

    private StatsManager() {
        this.saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Stats-Save-Thread");
            return t;
        });
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.statsDir = new File(STATS_DIR);
        if (!statsDir.exists()) {
            statsDir.mkdirs();
        }
        
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, UserStats>(1000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, UserStats> eldest) {
                if (size() > 1000) {
                    saveUserStats(eldest.getKey(), eldest.getValue());
                    return true;
                }
                return false;
            }
        });
    }

    public static synchronized StatsManager getInstance() {
        if (instance == null) {
            instance = new StatsManager();
        }
        return instance;
    }

    private File getUserFile(String userId) {
        if (userId == null || !userId.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid userId format");
        }
        return new File(statsDir, userId + ".json");
    }

    private UserStats loadUserStats(String userId) {
        UserStats stats = cache.get(userId);
        if (stats != null) return stats;

        File file = getUserFile(userId);
        if (!file.exists()) {
            stats = new UserStats();
            cache.put(userId, stats);
            return stats;
        }

        try {
            stats = mapper.readValue(file, UserStats.class);
            if (stats == null) stats = new UserStats();
            cache.put(userId, stats);
            return stats;
        } catch (IOException e) {
            logger.error("Failed to load stats for user {}", userId, e);
            stats = new UserStats();
            cache.put(userId, stats);
            return stats;
        }
    }

    private void saveUserStats(String userId, UserStats stats) {
        try {
            File tempFile = new File(statsDir, userId + ".json.tmp");
            File actualFile = getUserFile(userId);
            mapper.writeValue(tempFile, stats);
            Files.move(tempFile.toPath(), actualFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Failed to save stats for user {}", userId, e);
        }
    }

    private void scheduleSave(String userId) {
        UserStats stats = cache.get(userId);
        if (stats != null) {
            saveExecutor.submit(() -> saveUserStats(userId, stats));
        }
    }

    public UserStats getStats(String userId) {
        return loadUserStats(userId);
    }

    public void incrementCommand(String userId, String command) {
        if (userId == null) return;
        getStats(userId).incrementCommand(command);
        scheduleSave(userId);
    }

    public void addListeningData(String userId, String trackName, String artist, long durationMs) {
        if (userId == null || trackName == null) return;
        UserStats stats = getStats(userId);
        stats.incrementTracksPlayed();
        stats.addListeningTime(durationMs);
        stats.incrementTrack(trackName);
        if (artist != null && !artist.isEmpty()) {
            stats.incrementArtist(artist);
        }
        scheduleSave(userId);
    }

    public void addDjPoints(String userId, long points) {
        if (userId == null) return;
        getStats(userId).addDjPoints(points);
        scheduleSave(userId);
    }

    public void flushAll() {
        for (String userId : cache.keySet()) {
            scheduleSave(userId);
        }
    }

    public void clearStats(String userId) {
        if (userId == null) return;
        cache.remove(userId);
        File f = getUserFile(userId);
        if (f.exists()) f.delete();
    }
}
