package com.discord.musicbot.data;

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

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static String DB_FILE = "database.json";
    private static DatabaseManager instance;
    private final ObjectMapper mapper;

    public static void setDbFile(String filePath) {
        DB_FILE = filePath;
        // Reset instance to reload from new file
        instance = null;
    }

    // Data structures mirroring index.js

    private Map<String, String> prefixes; // guildId -> prefix
    private Set<String> settings247; // guildId

    private DatabaseManager() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        this.prefixes = new ConcurrentHashMap<>();
        this.settings247 = Collections.synchronizedSet(new HashSet<>());
        load();
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    // --- Data Classes ---

    // --- Persistence ---

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private static class StorageWrapper {

        public Map<String, String> prefixes = new HashMap<>();
        public Set<String> settings247 = new HashSet<>();
    }

    private synchronized void load() {
        File file = new File(DB_FILE);
        if (!file.exists())
            return;

        try {
            StorageWrapper data = mapper.readValue(file, StorageWrapper.class);
            if (data != null) {

                if (data.prefixes != null)
                    this.prefixes = new ConcurrentHashMap<>(data.prefixes);
                if (data.settings247 != null)
                    this.settings247 = Collections.synchronizedSet(new HashSet<>(data.settings247));
                logger.info("Database loaded.");
            }
        } catch (IOException e) {
            logger.error("Failed to load database", e);
        }
    }

    private synchronized void save() {
        try {
            StorageWrapper wrapper = new StorageWrapper();

            wrapper.prefixes = new HashMap<>(this.prefixes);
            wrapper.settings247 = new HashSet<>(this.settings247);

            File tempFile = new File(DB_FILE + ".tmp");
            File actualFile = new File(DB_FILE);
            
            mapper.writeValue(tempFile, wrapper);
            Files.move(tempFile.toPath(), actualFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Failed to save database", e);
        }
    }

    // --- Settings ---
    public boolean toggle247(String guildId) {
        if (settings247.contains(guildId)) {
            settings247.remove(guildId);
            save();
            return false; // Disabled
        } else {
            settings247.add(guildId);
            save();
            return true; // Enabled
        }
    }

    public boolean is247(String guildId) {
        return settings247.contains(guildId);
    }

}
