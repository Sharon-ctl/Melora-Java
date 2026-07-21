package com.discord.musicbot.data;

import com.discord.musicbot.data.model.PlaylistTrack;
import com.discord.musicbot.data.model.SavedQueue;
import com.discord.musicbot.data.model.UserSavedQueuesStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class SavedQueueManager {
    private static final Logger logger = LoggerFactory.getLogger(SavedQueueManager.class);
    private static SavedQueueManager instance;

    private static final String DIR = "saved_queues";
    public static final int MAX_SAVES_PER_USER = 20;

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, UserSavedQueuesStore> cache;
    private final ConcurrentHashMap<String, ReentrantLock> userLocks;
    private final File dir;
    private final java.util.concurrent.ExecutorService saveExecutor;

    private SavedQueueManager() {
        this.saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("SavedQueue-Save-Thread");
            return t;
        });
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.cache = new ConcurrentHashMap<>();
        this.userLocks = new ConcurrentHashMap<>();
        this.dir = new File(DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static synchronized SavedQueueManager getInstance() {
        if (instance == null) {
            instance = new SavedQueueManager();
        }
        return instance;
    }

    private ReentrantLock getLock(String userId) {
        return userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
    }

    private File getFile(String userId) {
        return new File(dir, userId + ".json");
    }

    private UserSavedQueuesStore getStore(String userId) {
        ReentrantLock lock = getLock(userId);
        lock.lock();
        try {
            if (cache.containsKey(userId)) {
                return cache.get(userId);
            }
            File file = getFile(userId);
            if (file.exists()) {
                try {
                    UserSavedQueuesStore store = mapper.readValue(file, UserSavedQueuesStore.class);
                    cache.put(userId, store);
                    return store;
                } catch (IOException e) {
                    logger.error("Failed to load saved queues for " + userId, e);
                }
            }
            UserSavedQueuesStore newStore = new UserSavedQueuesStore(userId);
            cache.put(userId, newStore);
            return newStore;
        } finally {
            lock.unlock();
        }
    }

    private void saveStore(UserSavedQueuesStore store) {
        String userId = store.getUserId();
        saveExecutor.submit(() -> {
            ReentrantLock lock = getLock(userId);
            lock.lock();
            try {
                File file = getFile(userId);
                File temp = new File(dir, userId + ".tmp");
                mapper.writeValue(temp, store);
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.error("Failed to save saved queues for " + userId, e);
            } finally {
                lock.unlock();
            }
        });
    }

    public List<SavedQueue> getSavedQueues(String userId) {
        return getStore(userId).getSavedQueues();
    }

    public String saveQueue(String userId, String name, List<PlaylistTrack> tracks) {
        UserSavedQueuesStore store = getStore(userId);
        ReentrantLock lock = getLock(userId);
        lock.lock();
        try {
            for (SavedQueue sq : store.getSavedQueues()) {
                if (sq.getName().equalsIgnoreCase(name)) {
                    sq.setTracks(tracks);
                    sq.setCreatedAt(System.currentTimeMillis());
                    saveStore(store);
                    return "updated";
                }
            }
            if (store.getSavedQueues().size() >= MAX_SAVES_PER_USER) {
                return "limit";
            }
            store.getSavedQueues().add(new SavedQueue(name, System.currentTimeMillis(), tracks));
            saveStore(store);
            return "created";
        } finally {
            lock.unlock();
        }
    }

    public SavedQueue getSavedQueue(String userId, String name) {
        for (SavedQueue sq : getStore(userId).getSavedQueues()) {
            if (sq.getName().equalsIgnoreCase(name)) {
                return sq;
            }
        }
        return null;
    }

    public boolean deleteQueue(String userId, String name) {
        UserSavedQueuesStore store = getStore(userId);
        ReentrantLock lock = getLock(userId);
        lock.lock();
        try {
            boolean removed = store.getSavedQueues().removeIf(sq -> sq.getName().equalsIgnoreCase(name));
            if (removed) {
                saveStore(store);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveExecutor.shutdownNow();
        }
    }
}
