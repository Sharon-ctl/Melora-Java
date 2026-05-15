package com.discord.musicbot.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages persistent history using database.json
 */
public class HistoryManager {
    private static final Logger logger = LoggerFactory.getLogger(HistoryManager.class);
    private static final String DB_FILE = "history.json";
    private final ObjectMapper mapper;
    private final File file;
    private final List<HistoryEntry> cache = new ArrayList<>();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private static HistoryManager instance;

    private HistoryManager() {
        this.mapper = new ObjectMapper();
        this.file = new File(DB_FILE);
        ensureFileExists();
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.executor.scheduleWithFixedDelay(() -> {
            if (dirty.getAndSet(false)) {
                save();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public static synchronized HistoryManager getInstance() {
        if (instance == null) {
            instance = new HistoryManager();
        }
        return instance;
    }

    private void ensureFileExists() {
        if (!file.exists()) {
            try {
                ObjectNode root = mapper.createObjectNode();
                root.putArray("history");
                mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
            } catch (IOException e) {
                logger.error("Failed to create database.json", e);
            }
        } else {
            loadToCache();
        }
    }

    private synchronized void loadToCache() {
        try {
            JsonNode root = mapper.readTree(file);
            if (root.has("history")) {
                ArrayNode history = (ArrayNode) root.get("history");
                for (JsonNode node : history) {
                    cache.add(new HistoryEntry(
                            node.get("title").asText(),
                            node.get("uri").asText(),
                            node.get("author").asText(),
                            node.get("length").asLong(),
                            node.get("userId").asText()));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load history cache, recreating...", e);
            file.delete();
            ensureFileExists();
        }
    }

    private synchronized void save() {
        try {
            ObjectNode root = mapper.createObjectNode();
            ArrayNode history = root.putArray("history");
            for (HistoryEntry e : cache) {
                ObjectNode node = mapper.createObjectNode();
                node.put("title", e.title);
                node.put("uri", e.uri);
                node.put("author", e.author);
                node.put("length", e.length);
                node.put("userId", e.userId);
                node.put("timestamp", System.currentTimeMillis());
                history.add(node);
            }
            File tempFile = new File(DB_FILE + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, root);
            java.nio.file.Files.move(tempFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Failed to write to history.json", e);
        }
    }

    public synchronized void addEntry(String title, String uri, String author, long length, String userId) {
        if (!cache.isEmpty() && cache.get(cache.size() - 1).uri.equals(uri)) {
            return;
        }
        if (cache.size() >= 100) {
            cache.remove(0);
        }
        cache.add(new HistoryEntry(title, uri, author, length, userId));
        dirty.set(true);
    }

    public synchronized List<HistoryEntry> getRecent(int limit) {
        List<HistoryEntry> result = new ArrayList<>();
        for (int i = cache.size() - 1; i >= 0 && result.size() < limit; i--) {
            result.add(cache.get(i));
        }
        return result;
    }

    public synchronized List<HistoryEntry> search(String query) {
        List<HistoryEntry> result = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (int i = cache.size() - 1; i >= 0; i--) {
            if (cache.get(i).title.toLowerCase().contains(lowerQuery)) {
                result.add(cache.get(i));
                if (result.size() >= 25) break;
            }
        }
        return result;
    }

    public static class HistoryEntry {
        public final String title;
        public final String uri;
        public final String author;
        public final long length;
        public final String userId;

        public HistoryEntry(String title, String uri, String author, long length, String userId) {
            this.title = title;
            this.uri = uri;
            this.author = author;
            this.length = length;
            this.userId = userId;
        }
    }
}
