package com.discord.musicbot.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

public class SpotifyResolvedTrack extends DelegatedAudioTrack {
    private final AudioTrack delegate;
    private final String artworkUrl;

    public SpotifyResolvedTrack(AudioTrackInfo trackInfo, AudioTrack delegate, String artworkUrl) {
        super(trackInfo);
        this.delegate = delegate;
        this.artworkUrl = artworkUrl;
    }

    public String getArtworkUrl() {
        return artworkUrl != null ? artworkUrl : trackInfo.artworkUrl;
    }

    public AudioTrack getDelegate() {
        return delegate;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        if (delegate instanceof InternalAudioTrack internalTrack) {
            processDelegate(internalTrack, executor);
        } else {
            ((InternalAudioTrack) delegate).process(executor);
        }
    }

    @Override
    public long getDuration() {
        return trackInfo.length;
    }

    @Override
    public AudioTrack makeClone() {
        SpotifyResolvedTrack clone = new SpotifyResolvedTrack(trackInfo, delegate.makeClone(), artworkUrl);
        clone.setUserData(this.getUserData());
        return clone;
    }
}
