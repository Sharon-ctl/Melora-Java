package com.discord.musicbot.data.model;

import java.util.ArrayList;
import java.util.List;

public class GuildSettings {
    private int volume = 100;
    private boolean autoplay = false;
    private boolean mode247 = false;
    private boolean djMode = false;
    private String djRole = null;
    
    private int voteSkipThreshold = 60;
    private int voteClearThreshold = 60;
    private int voteDisconnectThreshold = 60;
    
    private List<String> blacklistTracks = java.util.Collections.synchronizedList(new ArrayList<>());
    private List<String> blacklistArtists = java.util.Collections.synchronizedList(new ArrayList<>());
    private List<String> blacklistDomains = java.util.Collections.synchronizedList(new ArrayList<>());

    private boolean updateVcStatus = true;
    private boolean announceTracks = true;
    private int defaultVolume = 100;
    private String commandChannelId = null;

    private boolean mode247Locked = false;
    private String lockedVoiceChannelId = null;
    private boolean crossfadeEnabled = false;
    private int crossfadeDuration = 5;

    public synchronized int getVolume() { return volume; }
    public synchronized void setVolume(int volume) { this.volume = volume; }

    public synchronized boolean isAutoplay() { return autoplay; }
    public synchronized void setAutoplay(boolean autoplay) { this.autoplay = autoplay; }

    public synchronized boolean isMode247() { return mode247; }
    public synchronized void setMode247(boolean mode247) { this.mode247 = mode247; }

    public synchronized boolean isDjMode() { return djMode; }
    public synchronized void setDjMode(boolean djMode) { this.djMode = djMode; }

    public synchronized String getDjRole() { return djRole; }
    public synchronized void setDjRole(String djRole) { this.djRole = djRole; }

    public synchronized int getVoteSkipThreshold() { return voteSkipThreshold; }
    public synchronized void setVoteSkipThreshold(int voteSkipThreshold) { this.voteSkipThreshold = voteSkipThreshold; }

    public synchronized int getVoteClearThreshold() { return voteClearThreshold; }
    public synchronized void setVoteClearThreshold(int voteClearThreshold) { this.voteClearThreshold = voteClearThreshold; }

    public synchronized int getVoteDisconnectThreshold() { return voteDisconnectThreshold; }
    public synchronized void setVoteDisconnectThreshold(int voteDisconnectThreshold) { this.voteDisconnectThreshold = voteDisconnectThreshold; }

    public List<String> getBlacklistTracks() { return blacklistTracks; }
    public void setBlacklistTracks(List<String> blacklistTracks) { this.blacklistTracks = java.util.Collections.synchronizedList(new ArrayList<>(blacklistTracks)); }

    public List<String> getBlacklistArtists() { return blacklistArtists; }
    public void setBlacklistArtists(List<String> blacklistArtists) { this.blacklistArtists = java.util.Collections.synchronizedList(new ArrayList<>(blacklistArtists)); }

    public List<String> getBlacklistDomains() { return blacklistDomains; }
    public void setBlacklistDomains(List<String> blacklistDomains) { this.blacklistDomains = java.util.Collections.synchronizedList(new ArrayList<>(blacklistDomains)); }

    public synchronized boolean isUpdateVcStatus() { return updateVcStatus; }
    public synchronized void setUpdateVcStatus(boolean updateVcStatus) { this.updateVcStatus = updateVcStatus; }

    public synchronized boolean isAnnounceTracks() { return announceTracks; }
    public synchronized void setAnnounceTracks(boolean announceTracks) { this.announceTracks = announceTracks; }

    public synchronized int getDefaultVolume() { return defaultVolume; }
    public synchronized void setDefaultVolume(int defaultVolume) { this.defaultVolume = defaultVolume; }

    public synchronized String getCommandChannelId() { return commandChannelId; }
    public synchronized void setCommandChannelId(String commandChannelId) { this.commandChannelId = commandChannelId; }

    public synchronized boolean isMode247Locked() { return mode247Locked; }
    public synchronized void setMode247Locked(boolean mode247Locked) { this.mode247Locked = mode247Locked; }

    public synchronized String getLockedVoiceChannelId() { return lockedVoiceChannelId; }
    public synchronized void setLockedVoiceChannelId(String lockedVoiceChannelId) { this.lockedVoiceChannelId = lockedVoiceChannelId; }

    public synchronized boolean isCrossfadeEnabled() { return crossfadeEnabled; }
    public synchronized void setCrossfadeEnabled(boolean crossfadeEnabled) { this.crossfadeEnabled = crossfadeEnabled; }

    public synchronized int getCrossfadeDuration() { return crossfadeDuration; }
    public synchronized void setCrossfadeDuration(int crossfadeDuration) { this.crossfadeDuration = crossfadeDuration; }
}
