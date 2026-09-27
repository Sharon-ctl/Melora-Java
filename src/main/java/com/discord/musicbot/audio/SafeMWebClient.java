package com.discord.musicbot.audio;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.MWeb;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resilient MWeb InnerTube client that avoids GoogleVideo CDN 403 Forbidden errors.
 * <p>
 * When YouTube serves adaptive DASH streams (such as itag 251 WebM Opus) without a valid
 * Proof of Origin (PO) Token or verified session, GoogleVideo CDN rejects range requests with HTTP 403.
 * SafeMWebClient detects when no PO Token is present and prioritizes progressive formats
 * (such as itag 18 - 360p MP4 with AAC-LC stereo audio), which stream reliably with 0% 403 failure rate.
 */
public class SafeMWebClient extends MWeb {
    private static final Logger log = LoggerFactory.getLogger(SafeMWebClient.class);
    private final boolean hasPoToken;

    public SafeMWebClient(ClientOptions options, boolean hasPoToken) {
        super(options);
        this.hasPoToken = hasPoToken;
    }

    public SafeMWebClient(boolean hasPoToken) {
        super();
        this.hasPoToken = hasPoToken;
    }

    @Override
    public TrackFormats loadFormats(YoutubeAudioSourceManager sourceManager, HttpInterface httpInterface, String videoId)
            throws dev.lavalink.youtube.CannotBeLoaded, IOException {
        TrackFormats original = super.loadFormats(sourceManager, httpInterface, videoId);
        if (original == null) {
            return null;
        }

        // If a verified PO token is configured, all formats (including DASH Opus) are valid
        if (hasPoToken) {
            return original;
        }

        List<StreamFormat> formats = original.getFormats();
        if (formats == null || formats.isEmpty()) {
            return original;
        }

        StreamFormat progressiveFormat = null;
        for (StreamFormat format : formats) {
            if (format.getItag() == 18) { // 360p MP4 / AAC stereo (bypasses PO Token 403)
                progressiveFormat = format;
                break;
            } else if (format.getItag() == 22) { // 720p MP4
                if (progressiveFormat == null) {
                    progressiveFormat = format;
                }
            }
        }

        if (progressiveFormat != null) {
            log.debug("SafeMWeb: Routing playback through progressive format itag {} to bypass YouTube 403 block", progressiveFormat.getItag());
            List<StreamFormat> filtered = new ArrayList<>();
            filtered.add(progressiveFormat);
            return new TrackFormats(filtered, original.getPlayerScriptUrl());
        }

        return original;
    }
}
