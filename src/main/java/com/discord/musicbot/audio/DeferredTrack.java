package com.discord.musicbot.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

public class DeferredTrack extends DelegatedAudioTrack {
    private final String query;
    private final String artworkUrl;

    public DeferredTrack(AudioTrackInfo trackInfo, String query, String artworkUrl) {
        super(trackInfo);
        this.query = query;
        this.artworkUrl = artworkUrl;
    }

    public String getQuery() {
        return query;
    }

    public String getArtworkUrl() {
        return artworkUrl;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        // In this implementation, DeferredTrack is handled explicitly by TrackScheduler
        // before playback starts. This process() shouldn't be called directly.
        throw new IllegalStateException("DeferredTrack must be resolved before playback.");
    }

    @Override
    public AudioTrack makeClone() {
        DeferredTrack clone = new DeferredTrack(trackInfo, query, artworkUrl);
        clone.setUserData(this.getUserData());
        return clone;
    }
}
