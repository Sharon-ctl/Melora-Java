package com.discord.musicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class AudioPlayerSendHandler implements AudioSendHandler {
    private final AudioPlayer primaryPlayer;
    private final AudioPlayer secondaryPlayer;
    
    private final ByteBuffer primaryBuffer;
    private final ByteBuffer secondaryBuffer;
    private final MutableAudioFrame primaryFrame;
    private final MutableAudioFrame secondaryFrame;
    
    private final ByteBuffer mixBuffer;
    private final AtomicLong lastFrameTime = new AtomicLong(System.currentTimeMillis());
    
    private boolean isCrossfading = false;
    private boolean primaryIsFadingOut = true;
    private long crossfadeStartTime = 0;
    private long crossfadeDurationMs = 0;

    public AudioPlayerSendHandler(AudioPlayer primary, AudioPlayer secondary) {
        this.primaryPlayer = primary;
        this.secondaryPlayer = secondary;
        
        this.primaryBuffer = ByteBuffer.allocate(3840);
        this.secondaryBuffer = ByteBuffer.allocate(3840);
        this.mixBuffer = ByteBuffer.allocate(3840);
        
        this.primaryFrame = new MutableAudioFrame();
        this.primaryFrame.setBuffer(primaryBuffer);
        
        this.secondaryFrame = new MutableAudioFrame();
        this.secondaryFrame.setBuffer(secondaryBuffer);
    }
    
    public void startCrossfade(long durationMs, boolean primaryIsFadingOut) {
        this.isCrossfading = true;
        this.crossfadeStartTime = System.currentTimeMillis();
        this.crossfadeDurationMs = durationMs;
        this.primaryIsFadingOut = primaryIsFadingOut;
    }
    
    public void stopCrossfade() {
        this.isCrossfading = false;
    }

    private boolean lastProvideP1 = false;
    private boolean lastProvideP2 = false;

    @Override
    public boolean canProvide() {
        lastProvideP1 = primaryPlayer.provide(primaryFrame);
        lastProvideP2 = secondaryPlayer.provide(secondaryFrame);
        return lastProvideP1 || lastProvideP2;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        lastFrameTime.set(System.currentTimeMillis());
        boolean p1HasAudio = lastProvideP1;
        boolean p2HasAudio = lastProvideP2;
        
        ((Buffer) mixBuffer).clear();
        
        if (p1HasAudio && !p2HasAudio) {
            mixBuffer.put(primaryBuffer.array(), 0, primaryFrame.getDataLength());
        } else if (!p1HasAudio && p2HasAudio) {
            mixBuffer.put(secondaryBuffer.array(), 0, secondaryFrame.getDataLength());
        } else if (p1HasAudio && p2HasAudio) {
            float p1Vol = 1.0f;
            float p2Vol = 1.0f;
            
            if (isCrossfading && crossfadeDurationMs > 0) {
                long elapsed = System.currentTimeMillis() - crossfadeStartTime;
                if (elapsed < crossfadeDurationMs) {
                    float fade = ((float) elapsed / crossfadeDurationMs);
                    if (primaryIsFadingOut) {
                        p1Vol = 1.0f - fade;
                        p2Vol = fade;
                    } else {
                        p1Vol = fade;
                        p2Vol = 1.0f - fade;
                    }
                } else {
                    isCrossfading = false;
                    if (primaryIsFadingOut) {
                        p1Vol = 0.0f;
                        p2Vol = 1.0f;
                    } else {
                        p1Vol = 1.0f;
                        p2Vol = 0.0f;
                    }
                }
            }
            
            byte[] p1Data = primaryBuffer.array();
            byte[] p2Data = secondaryBuffer.array();
            
            int maxLen = Math.min(primaryFrame.getDataLength(), secondaryFrame.getDataLength());
            for (int i = 0; i < maxLen; i += 2) {
                short sample1 = (short) ((p1Data[i] & 0xFF) << 8 | (p1Data[i + 1] & 0xFF));
                short sample2 = (short) ((p2Data[i] & 0xFF) << 8 | (p2Data[i + 1] & 0xFF));
                
                int mixed = (int) (sample1 * p1Vol + sample2 * p2Vol);
                
                if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
                if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
                
                mixBuffer.put((byte) ((mixed >> 8) & 0xFF));
                mixBuffer.put((byte) (mixed & 0xFF));
            }
        }
        
        ((Buffer) mixBuffer).flip();
        return mixBuffer;
    }

    @Override
    public boolean isOpus() {
        return false;
    }

    public long getLastFrameTime() {
        return lastFrameTime.get();
    }
}
