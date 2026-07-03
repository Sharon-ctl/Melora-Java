package com.discord.musicbot.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HistoryManager {
    private static final Logger logger = LoggerFactory.getLogger(HistoryManager.class);
    private static final String HISTORY_DIR = "history";
    
    private final ObjectMapper mapper;
    private final File historyDir;
    private final Map<String, List<HistoryEntry>> cache;
    private final java.util.concurrent.ExecutorService saveExecutor;

    private static HistoryManager instance;

    private HistoryManager() {
        this.saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("History-Save-Thread");
            return t;
        });
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.historyDir = new File(HISTORY_DIR);
        if (!historyDir.exists()) {
            historyDir.mkdirs();
        }

        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, List<HistoryEntry>>(1000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<HistoryEntry>> eldest) {
                if (size() > 1000) {
                    saveUserHistory(eldest.getKey(), eldest.getValue());
                    return true;
                }
                return false;
            }
        });
    }

    public static synchronized HistoryManager getInstance() {
        if (instance == null) {
            instance = new HistoryManager();
        }
        return instance;
    }

    private File getUserFile(String userId) {
        if (userId == null || !userId.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid userId format");
        }
        return new File(historyDir, userId + ".json");
    }

    private List<HistoryEntry> loadUserHistory(String userId) {
        List<HistoryEntry> history = cache.get(userId);
        if (history != null) return history;

        File file = getUserFile(userId);
        if (!file.exists()) {
            history = new ArrayList<>();
            cache.put(userId, history);
            return history;
        }

        try {
            history = mapper.readValue(file, new TypeReference<List<HistoryEntry>>() {});
            if (history == null) history = new ArrayList<>();
            cache.put(userId, history);
            return history;
        } catch (IOException e) {
            logger.error("Failed to load history for user {} (JSON corrupted). Backing up corrupted file.", userId, e);
            try {
                Files.move(file.toPath(), new File(file.getParentFile(), file.getName() + ".corrupt." + System.currentTimeMillis()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
            history = new ArrayList<>();
            cache.put(userId, history);
            return history;
        }
    }

    private void saveUserHistory(String userId, List<HistoryEntry> history) {
        try {
            File tempFile = new File(historyDir, userId + ".json.tmp");
            File actualFile = getUserFile(userId);
            mapper.writeValue(tempFile, history);
            Files.move(tempFile.toPath(), actualFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Failed to save history for user {}", userId, e);
        }
    }

    private void scheduleSave(String userId) {
        List<HistoryEntry> history = cache.get(userId);
        if (history != null) {
            List<HistoryEntry> snapshot;
            synchronized (this) {
                snapshot = new ArrayList<>(history);
            }
            saveExecutor.submit(() -> saveUserHistory(userId, snapshot));
        }
    }

    public synchronized void addEntry(String title, String uri, String author, long length, String userId) {
        if (userId == null) return;
        List<HistoryEntry> userHistory = loadUserHistory(userId);
        
        // Prevent replay spam
        if (!userHistory.isEmpty() && java.util.Objects.equals(userHistory.get(userHistory.size() - 1).uri, uri)) {
            return;
        }
        
        // Add entry
        userHistory.add(new HistoryEntry(title, uri, author, length, userId));
        
        // Max 500 per user
        if (userHistory.size() > 500) {
            userHistory.remove(0);
        }
        scheduleSave(userId);
    }

    public void flushAll() {
        for (String userId : cache.keySet()) {
            scheduleSave(userId);
        }
    }

    public synchronized List<HistoryEntry> getUserHistory(String userId) {
        if (userId == null) return new ArrayList<>();
        List<HistoryEntry> original = loadUserHistory(userId);
        List<HistoryEntry> copy = new ArrayList<>();
        // Return reversed (newest first)
        for (int i = original.size() - 1; i >= 0; i--) {
            copy.add(original.get(i));
        }
        return copy;
    }

    public synchronized void clearHistory(String userId) {
        if (userId == null) return;
        cache.remove(userId);
        File f = getUserFile(userId);
        if (f.exists()) f.delete();
    }

    public synchronized List<HistoryEntry> getRecent(int limit) {
        List<HistoryEntry> all = new ArrayList<>();
        synchronized (cache) {
            for (List<HistoryEntry> list : cache.values()) {
                all.addAll(list);
            }
        }
        all.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        if (all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    public synchronized List<HistoryEntry> search(String userId, String query) {
        if (userId == null) return new ArrayList<>();
        List<HistoryEntry> result = new ArrayList<>();
        List<HistoryEntry> userHistory = loadUserHistory(userId);
        if (query == null || query.trim().isEmpty()) {
            List<HistoryEntry> copy = new ArrayList<>(userHistory);
            copy.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
            if (copy.size() > 25) return copy.subList(0, 25);
            return copy;
        }
        for (HistoryEntry e : userHistory) {
            if (e.title.toLowerCase().contains(query.toLowerCase()) || e.author.toLowerCase().contains(query.toLowerCase())) {
                result.add(e);
            }
        }
        result.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        // Remove duplicates by URI
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<HistoryEntry> unique = new ArrayList<>();
        for (HistoryEntry e : result) {
            if (seen.add(e.uri)) {
                unique.add(e);
            }
        }
        if (unique.size() > 25) {
            return unique.subList(0, 25);
        }
        return unique;
    }

    public static class HistoryEntry {
        public String title;
        public String uri;
        public String author;
        public long length;
        public String userId;
        public long timestamp;

        public HistoryEntry() {}

        public HistoryEntry(String title, String uri, String author, long length, String userId) {
            this.title = title;
            this.uri = uri;
            this.author = author;
            this.length = length;
            this.userId = userId;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
