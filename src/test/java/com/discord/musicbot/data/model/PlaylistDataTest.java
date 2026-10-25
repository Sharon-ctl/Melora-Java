package com.discord.musicbot.data.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistDataTest {

    @Test
    void newPlaylistStartsWithNoTracksAndZeroDuration() {
        PlaylistData playlist = new PlaylistData("playlist-1", "Road trip", "user-1");

        assertNotNull(playlist.getTracks());
        assertTrue(playlist.getTracks().isEmpty());
        assertEquals(0, playlist.getTotalDuration());
    }

    @Test
    void totalDurationSumsAllTrackDurations() {
        PlaylistData playlist = new PlaylistData("playlist-1", "Road trip", "user-1");
        playlist.setTracks(List.of(
                new PlaylistTrack("First", "Artist A", 125_000, "track:1", "youtube", null),
                new PlaylistTrack("Second", "Artist B", 240_000, "track:2", "soundcloud", null)
        ));

        assertEquals(365_000, playlist.getTotalDuration());
    }

    @Test
    void favoritesAreIdentifiedByReservedNamePrefix() {
        PlaylistData favorites = new PlaylistData("favorites-1", "favorites_12345", "12345");
        PlaylistData regular = new PlaylistData("playlist-1", "favorites for later", "12345");

        assertTrue(favorites.isFavorites());
        assertFalse(regular.isFavorites());
    }

    @Test
    void jacksonReadsPlaylistTracksAndIgnoresUnknownLegacyFields() throws Exception {
        String json = """
                {
                  "id": "playlist-1",
                  "name": "Road trip",
                  "userId": "user-1",
                  "tracks": [
                    {"title": "First", "author": "Artist", "duration": 90000, "uri": "track:1"}
                  ],
                  "legacyField": "ignored"
                }
                """;

        PlaylistData playlist = new ObjectMapper().readValue(json, PlaylistData.class);

        assertEquals("playlist-1", playlist.getId());
        assertEquals("Road trip", playlist.getName());
        assertEquals(1, playlist.getTracks().size());
        assertEquals(90_000, playlist.getTotalDuration());
    }
}
