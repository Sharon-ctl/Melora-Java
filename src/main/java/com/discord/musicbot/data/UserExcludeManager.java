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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UserExcludeManager {
    private static final Logger logger = LoggerFactory.getLogger(UserExcludeManager.class);
    private static final String EXCLUDES_FILE = "user_excludes.json";
    public static final int MAX_EXCLUDES_PER_USER = 20;

    private static UserExcludeManager instance;
    private final ObjectMapper mapper;
    private final File file;
    private final ConcurrentHashMap<String, Set<String>> excludes;
    private final ExecutorService saveExecutor;

    private UserExcludeManager() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.file = new File(EXCLUDES_FILE);
        this.excludes = new ConcurrentHashMap<>();
        this.saveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Exclude-Save-Thread");
            return t;
        });
        load();
    }

    public static synchronized UserExcludeManager getInstance() {
        if (instance == null) {
            instance = new UserExcludeManager();
        }
        return instance;
    }

    private synchronized void load() {
        if (file.exists()) {
            try {
                Map<String, Set<String>> loaded = mapper.readValue(file, new TypeReference<>() {});
                if (loaded != null) {
                    for (Map.Entry<String, Set<String>> entry : loaded.entrySet()) {
                        Set<String> set = ConcurrentHashMap.newKeySet();
                        set.addAll(entry.getValue());
                        excludes.put(entry.getKey(), set);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to load user excludes", e);
            }
        }
    }

    private void save() {
        Map<String, Set<String>> snapshot = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : excludes.entrySet()) {
            snapshot.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        saveExecutor.submit(() -> {
            try {
                File tempFile = new File(EXCLUDES_FILE + ".tmp");
                mapper.writeValue(tempFile, snapshot);
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.error("Failed to save user excludes", e);
            }
        });
    }

    public boolean addExclude(String requesterId, String targetUserId) {
        if (requesterId == null || targetUserId == null || requesterId.equals(targetUserId)) {
            return false;
        }
        Set<String> set = excludes.computeIfAbsent(requesterId, k -> ConcurrentHashMap.newKeySet());
        if (set.size() >= MAX_EXCLUDES_PER_USER) {
            return false;
        }
        boolean added = set.add(targetUserId);
        if (added) {
            save();
        }
        return added;
    }

    public boolean removeExclude(String requesterId, String targetUserId) {
        if (requesterId == null || targetUserId == null) return false;
        Set<String> set = excludes.get(requesterId);
        if (set != null && set.remove(targetUserId)) {
            if (set.isEmpty()) {
                excludes.remove(requesterId);
            }
            save();
            return true;
        }
        return false;
    }

    public Set<String> getExcludes(String requesterId) {
        if (requesterId == null) return Collections.emptySet();
        Set<String> set = excludes.get(requesterId);
        return set != null ? new HashSet<>(set) : Collections.emptySet();
    }

    public void clearExcludes(String requesterId) {
        if (requesterId == null) return;
        if (excludes.remove(requesterId) != null) {
            save();
        }
    }
}
