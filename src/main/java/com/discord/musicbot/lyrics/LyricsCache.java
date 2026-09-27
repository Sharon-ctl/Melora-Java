package com.discord.musicbot.lyrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LyricsCache {
    private static final int MAX_CACHE_SIZE = 250;
    private static final Map<String, LyricsData> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, LyricsData>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LyricsData> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });
    private static final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("LyricsCache-Cleaner");
        return t;
    });

    public static class LyricsData {
        public String query;
        public List<String> pages;
        public String source;
        public boolean isLive;
        public long timestamp;

        public LyricsData(String query, List<String> pages, String source, boolean isLive) {
            this.query = query;
            this.pages = pages;
            this.source = source;
            this.isLive = isLive;
            this.timestamp = System.currentTimeMillis();
        }
    }

    static {
        // Clean up cache entries older than 30 minutes
        cleaner.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                synchronized (cache) {
                    cache.entrySet().removeIf(entry -> now - entry.getValue().timestamp > 30 * 60 * 1000);
                }
            } catch (Exception ignored) {}
        }, 30, 30, TimeUnit.MINUTES);
    }

    public static void put(String id, LyricsData data) {
        if (id == null || data == null) return;
        cache.put(id, data);
    }

    public static LyricsData get(String id) {
        if (id == null) return null;
        return cache.get(id);
    }
}
