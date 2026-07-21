package com.discord.musicbot.data;

import com.discord.musicbot.data.model.PlaylistData;
import com.discord.musicbot.data.model.PlaylistTrack;
import com.discord.musicbot.data.model.UserPlaylistStore;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * PlaylistManager — Singleton managing all playlist and favorites CRUD operations.
 * Uses per-user file storage with per-user locking for thread safety.
 */
public class PlaylistManager {
    private static final Logger logger = LoggerFactory.getLogger(PlaylistManager.class);
    private static PlaylistManager instance;

    private static final String PLAYLISTS_DIR = "playlists";
    public static final int MAX_PLAYLISTS_PER_USER = 25;
    public static final int MAX_TRACKS_PER_PLAYLIST = 500;
    public static final int MAX_FAVORITES = 500;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MIN_NAME_LENGTH = 1;
    public static final long MAX_IMPORT_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    // Matches invisible/control unicode characters (except normal space)
    private static final Pattern INVALID_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}\\x{200B}-\\x{200F}\\x{2028}-\\x{202F}\\x{2060}-\\x{206F}\\x{FEFF}]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private final ObjectMapper mapper;
    private final Map<String, UserPlaylistStore> cache;
    private final ConcurrentHashMap<String, ReentrantLock> userLocks;
    private final File playlistsDir;
    private final java.util.concurrent.ExecutorService saveExecutor;

    private PlaylistManager() {
        this.saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Playlist-Save-Thread");
            return t;
        });
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, UserPlaylistStore>(1000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, UserPlaylistStore> eldest) {
                if (size() > 1000) {
                    saveStoreData(eldest.getKey(), eldest.getValue());
                    return true;
                }
                return false;
            }
        });
        this.userLocks = new ConcurrentHashMap<>();
        this.playlistsDir = new File(PLAYLISTS_DIR);
        if (!playlistsDir.exists()) {
            playlistsDir.mkdirs();
        }
    }

    public static synchronized PlaylistManager getInstance() {
        if (instance == null) {
            instance = new PlaylistManager();
        }
        return instance;
    }

    private ReentrantLock getUserLock(String userId) {
        return userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
    }

    private File getUserFile(String userId) {
        if (userId == null || !userId.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid userId format");
        }
        return new File(playlistsDir, userId + ".json");
    }

    private UserPlaylistStore loadUserStore(String userId) {
        UserPlaylistStore store = cache.get(userId);
        if (store != null) return store;

        File file = getUserFile(userId);
        if (!file.exists()) {
            store = new UserPlaylistStore();
            cache.put(userId, store);
            return store;
        }

        try {
            store = mapper.readValue(file, UserPlaylistStore.class);
            if (store == null) store = new UserPlaylistStore();
            cache.put(userId, store);
            return store;
        } catch (IOException e) {
            logger.error("Failed to load playlist store for user {}", userId, e);
            store = new UserPlaylistStore();
            cache.put(userId, store);
            return store;
        }
    }

    private void saveStoreData(String userId, UserPlaylistStore store) {
        saveExecutor.submit(() -> {
            ReentrantLock lock = getUserLock(userId);
            lock.lock();
            try {
                File tempFile = new File(playlistsDir, userId + ".json.tmp");
                File actualFile = getUserFile(userId);
                mapper.writeValue(tempFile, store);
                Files.move(tempFile.toPath(), actualFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.error("Failed to save playlist store for user {}", userId, e);
            } finally {
                lock.unlock();
            }
        });
    }

    private void saveUserStore(String userId) {
        UserPlaylistStore store = cache.get(userId);
        if (store == null) return;
        saveStoreData(userId, store);
    }

    /**
     * Deletes all data for a user (playlists and favorites) and removes their file.
     */
    public void deleteAllUserData(String userId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            cache.remove(userId);
            File actualFile = getUserFile(userId);
            if (actualFile.exists()) {
                actualFile.delete();
            }
        } finally {
            lock.unlock();
            userLocks.remove(userId);
        }
    }

    /**
     * Flush all cached stores to disk. Called on shutdown.
     */
    public void flushAll() {
        for (String userId : cache.keySet()) {
            ReentrantLock lock = getUserLock(userId);
            lock.lock();
            try {
                saveUserStore(userId);
            } finally {
                lock.unlock();
            }
        }
        // Clean up userLocks for users no longer in cache
        userLocks.keySet().removeIf(userId -> !cache.containsKey(userId));
    }

    // ======================== NAME VALIDATION ========================

    /**
     * Validates and normalizes a playlist name.
     * @return normalized name, or null if invalid
     */
    public String validateName(String name) {
        if (name == null) return null;

        // Strip invisible/control characters
        name = INVALID_CHARS.matcher(name).replaceAll("");
        // Normalize whitespace
        name = MULTI_SPACE.matcher(name.trim()).replaceAll(" ");

        if (name.length() < MIN_NAME_LENGTH || name.length() > MAX_NAME_LENGTH) return null;
        if (name.startsWith("favorites_")) return null; // Reserved
        if (name.isBlank()) return null;

        return name;
    }

    // ======================== PLAYLIST CRUD ========================

    /**
     * Creates a new empty playlist.
     * @return the created PlaylistData, or null if failed (duplicate name, limit reached, invalid name)
     */
    public PlaylistData createPlaylist(String userId, String name) {
        String normalized = validateName(name);
        if (normalized == null) return null;

        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);

            // Count non-favorites playlists
            long count = store.getPlaylists().stream()
                    .filter(p -> !p.isFavorites())
                    .count();
            if (count >= MAX_PLAYLISTS_PER_USER) return null;

            // Check duplicate name
            boolean exists = store.getPlaylists().stream()
                    .filter(p -> !p.isFavorites())
                    .anyMatch(p -> p.getName().equalsIgnoreCase(normalized));
            if (exists) return null;

            PlaylistData playlist = new PlaylistData(UUID.randomUUID().toString(), normalized, userId);
            store.getPlaylists().add(playlist);
            saveUserStore(userId);
            return playlist;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes a playlist by ID.
     */
    public boolean deletePlaylist(String userId, String playlistId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            boolean removed = store.getPlaylists().removeIf(
                    p -> p.getId().equals(playlistId) && !p.isFavorites());
            if (removed) saveUserStore(userId);
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Renames a playlist. Preserves UUID.
     * @return the result code: "ok", "not_found", "duplicate", "invalid_name"
     */
    public String renamePlaylist(String userId, String playlistId, String newName) {
        String normalized = validateName(newName);
        if (normalized == null) return "invalid_name";

        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);

            PlaylistData target = store.getPlaylists().stream()
                    .filter(p -> p.getId().equals(playlistId) && !p.isFavorites())
                    .findFirst().orElse(null);
            if (target == null) return "not_found";

            // Check duplicate
            boolean exists = store.getPlaylists().stream()
                    .filter(p -> !p.getId().equals(playlistId) && !p.isFavorites())
                    .anyMatch(p -> p.getName().equalsIgnoreCase(normalized));
            if (exists) return "duplicate";

            target.setName(normalized);
            target.setUpdatedAt(System.currentTimeMillis());
            saveUserStore(userId);
            return "ok";
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get all user playlists (excluding favorites).
     */
    public List<PlaylistData> getPlaylists(String userId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            return store.getPlaylists().stream()
                    .filter(p -> !p.isFavorites())
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Find a non-favorites playlist by name (case-insensitive).
     */
    public PlaylistData findPlaylistByName(String userId, String name) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            return store.getPlaylists().stream()
                    .filter(p -> !p.isFavorites() && p.getName().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get a playlist by ID.
     */
    public PlaylistData getPlaylist(String userId, String playlistId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            return store.getPlaylists().stream()
                    .filter(p -> p.getId().equals(playlistId))
                    .findFirst().orElse(null);
        } finally {
            lock.unlock();
        }
    }

    // ======================== TRACK MANAGEMENT ========================

    /**
     * Add a track to a playlist.
     * @return "ok", "not_found", "limit", "duplicate"
     */
    public String addTrack(String userId, String playlistId, PlaylistTrack track) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            PlaylistData playlist = store.getPlaylists().stream()
                    .filter(p -> p.getId().equals(playlistId))
                    .findFirst().orElse(null);
            if (playlist == null) return "not_found";

            int maxTracks = playlist.isFavorites() ? MAX_FAVORITES : MAX_TRACKS_PER_PLAYLIST;
            if (playlist.getTracks().size() >= maxTracks) return "limit";

            // Check duplicate by URI
            if (track.getUri() != null) {
                boolean dup = playlist.getTracks().stream()
                        .anyMatch(t -> track.getUri().equals(t.getUri()));
                if (dup) return "duplicate";
            }

            playlist.getTracks().add(track);
            playlist.setUpdatedAt(System.currentTimeMillis());
            saveUserStore(userId);
            return "ok";
        } finally {
            lock.unlock();
        }
    }

    /**
     * Add multiple tracks to a playlist. Skips duplicates.
     * @return number of tracks actually added
     */
    public int addMultipleTracks(String userId, String playlistId, List<PlaylistTrack> tracks) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            PlaylistData playlist = store.getPlaylists().stream()
                    .filter(p -> p.getId().equals(playlistId))
                    .findFirst().orElse(null);
            if (playlist == null) return 0;

            int maxTracks = playlist.isFavorites() ? MAX_FAVORITES : MAX_TRACKS_PER_PLAYLIST;
            Set<String> existingUris = new HashSet<>();
            for (PlaylistTrack t : playlist.getTracks()) {
                if (t.getUri() != null) existingUris.add(t.getUri());
            }

            int added = 0;
            for (PlaylistTrack track : tracks) {
                if (playlist.getTracks().size() >= maxTracks) break;
                if (track.getUri() != null && existingUris.contains(track.getUri())) continue;
                playlist.getTracks().add(track);
                if (track.getUri() != null) existingUris.add(track.getUri());
                added++;
            }

            if (added > 0) {
                playlist.setUpdatedAt(System.currentTimeMillis());
                saveUserStore(userId);
            }
            return added;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove a track by index.
     * @return the removed track, or null if invalid index
     */
    public PlaylistTrack removeTrack(String userId, String playlistId, int index) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            PlaylistData playlist = store.getPlaylists().stream()
                    .filter(p -> p.getId().equals(playlistId))
                    .findFirst().orElse(null);
            if (playlist == null) return null;
            if (index < 0 || index >= playlist.getTracks().size()) return null;

            PlaylistTrack removed = playlist.getTracks().remove(index);
            playlist.setUpdatedAt(System.currentTimeMillis());
            saveUserStore(userId);
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Move a track within a playlist.
     */
    public boolean moveTrack(String userId, String playlistId, int from, int to) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            PlaylistData playlist = store.getPlaylists().stream()
                    .filter(p -> p.getId().equals(playlistId))
                    .findFirst().orElse(null);
            if (playlist == null) return false;

            List<PlaylistTrack> tracks = playlist.getTracks();
            if (from < 0 || from >= tracks.size() || to < 0 || to >= tracks.size()) return false;

            PlaylistTrack track = tracks.remove(from);
            tracks.add(to, track);
            playlist.setUpdatedAt(System.currentTimeMillis());
            saveUserStore(userId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deduplicate tracks by URI. Preserves first occurrence.
     * @return number of duplicates removed
     */
    public int deduplicateTracks(String userId, String playlistId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            PlaylistData playlist = store.getPlaylists().stream()
                    .filter(p -> p.getId().equals(playlistId))
                    .findFirst().orElse(null);
            if (playlist == null) return 0;

            Set<String> seen = new HashSet<>();
            int originalSize = playlist.getTracks().size();
            playlist.getTracks().removeIf(track -> {
                String key = track.getUri() != null ? track.getUri() : (track.getTitle() + "|" + track.getAuthor());
                return !seen.add(key);
            });

            int removed = originalSize - playlist.getTracks().size();
            if (removed > 0) {
                playlist.setUpdatedAt(System.currentTimeMillis());
                saveUserStore(userId);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    // ======================== FAVORITES ========================

    /**
     * Get or create the user's favorites playlist.
     */
    public PlaylistData getFavorites(String userId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            String favName = "favorites_" + userId;
            PlaylistData fav = store.getPlaylists().stream()
                    .filter(p -> p.getName().equals(favName))
                    .findFirst().orElse(null);

            if (fav == null) {
                fav = new PlaylistData(UUID.randomUUID().toString(), favName, userId);
                store.getPlaylists().add(fav);
                saveUserStore(userId);
            }
            return fav;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clear all favorites tracks.
     */
    public int clearFavorites(String userId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            PlaylistData fav = getFavoritesInternal(userId);
            if (fav == null) return 0;
            int count = fav.getTracks().size();
            fav.getTracks().clear();
            fav.setUpdatedAt(System.currentTimeMillis());
            saveUserStore(userId);
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clear all custom playlists for a user.
     */
    public int clearPlaylists(String userId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            int count = store.getPlaylists().size();
            store.getPlaylists().clear();
            saveUserStore(userId);
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Internal method to get favorites without re-acquiring lock.
     * Must be called while holding the user lock.
     */
    private PlaylistData getFavoritesInternal(String userId) {
        UserPlaylistStore store = loadUserStore(userId);
        String favName = "favorites_" + userId;
        PlaylistData fav = store.getPlaylists().stream()
                .filter(p -> p.getName().equals(favName))
                .findFirst().orElse(null);

        if (fav == null) {
            fav = new PlaylistData(UUID.randomUUID().toString(), favName, userId);
            store.getPlaylists().add(fav);
        }
        return fav;
    }

    // ======================== IMPORT / EXPORT ========================

    /**
     * Import a playlist from parsed JSON data. Does NOT check for name conflicts — 
     * caller must handle conflict resolution before calling this.
     */
    public PlaylistData importPlaylist(String userId, String name, List<PlaylistTrack> tracks) {
        String normalized = validateName(name);
        if (normalized == null) return null;

        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);

            // Check playlist limit
            long count = store.getPlaylists().stream()
                    .filter(p -> !p.isFavorites())
                    .count();
            if (count >= MAX_PLAYLISTS_PER_USER) return null;

            // Truncate tracks to limit
            List<PlaylistTrack> limitedTracks = tracks.size() > MAX_TRACKS_PER_PLAYLIST
                    ? new ArrayList<>(tracks.subList(0, MAX_TRACKS_PER_PLAYLIST))
                    : new ArrayList<>(tracks);

            PlaylistData playlist = new PlaylistData(UUID.randomUUID().toString(), normalized, userId);
            playlist.setTracks(limitedTracks);
            store.getPlaylists().add(playlist);
            saveUserStore(userId);
            return playlist;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Import favorites from parsed JSON data. Replaces existing favorites.
     */
    public PlaylistData importFavorites(String userId, List<PlaylistTrack> tracks) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            PlaylistData fav = getFavoritesInternal(userId);
            List<PlaylistTrack> limitedTracks = tracks.size() > MAX_FAVORITES
                    ? new ArrayList<>(tracks.subList(0, MAX_FAVORITES))
                    : new ArrayList<>(tracks);
            fav.setTracks(limitedTracks);
            fav.setUpdatedAt(System.currentTimeMillis());
            saveUserStore(userId);
            return fav;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Mark a playlist as a Mewsic import.
     */
    public void markAsMewsic(String userId, String playlistId) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            store.getPlaylists().stream()
                 .filter(p -> p.getId().equals(playlistId))
                 .findFirst()
                 .ifPresent(p -> {
                     p.setMewsic(true);
                     saveUserStore(userId);
                 });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replace an existing playlist with imported data.
     */
    public PlaylistData replacePlaylist(String userId, String playlistId, List<PlaylistTrack> tracks) {
        ReentrantLock lock = getUserLock(userId);
        lock.lock();
        try {
            UserPlaylistStore store = loadUserStore(userId);
            PlaylistData playlist = store.getPlaylists().stream()
                    .filter(p -> p.getId().equals(playlistId))
                    .findFirst().orElse(null);
            if (playlist == null) return null;

            List<PlaylistTrack> limitedTracks = tracks.size() > MAX_TRACKS_PER_PLAYLIST
                    ? new ArrayList<>(tracks.subList(0, MAX_TRACKS_PER_PLAYLIST))
                    : new ArrayList<>(tracks);
            playlist.setTracks(limitedTracks);
            playlist.setUpdatedAt(System.currentTimeMillis());
            saveUserStore(userId);
            return playlist;
        } finally {
            lock.unlock();
        }
    }

    // ======================== SANITIZATION ========================

    /**
     * Sanitize a string from imported JSON data.
     */
    public static String sanitizeString(String input) {
        if (input == null) return "";
        // Remove control characters
        String clean = INVALID_CHARS.matcher(input).replaceAll("");
        // Trim and limit length
        clean = clean.trim();
        if (clean.length() > 256) clean = clean.substring(0, 256);
        return clean;
    }

    public void shutdown() {
        flushAll();
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
