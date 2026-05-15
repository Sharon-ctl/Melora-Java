package com.discord.musicbot;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VoiceEventHandler - Handles voice channel events for auto-disconnect and bot
 * kick detection.
 */
public class VoiceEventHandler extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(VoiceEventHandler.class);

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Guild guild = event.getGuild();

        // Handle bot being kicked/disconnected
        if (event.getMember().equals(guild.getSelfMember())) {
            if (event.getChannelLeft() != null && event.getChannelJoined() == null) {
                // Bot was disconnected from voice channel
                logger.info("Bot was disconnected from voice in guild: {}", guild.getName());
                handleBotDisconnect(guild);
                return;
            } else if (event.getChannelJoined() != null) {
                MusicManager manager = PlayerManager.getInstance().getMusicManager(guild);
                if (manager.getPlayer().getPlayingTrack() == null) {
                    manager.updateVoiceChannelStatus("<:addmusic3:1504821095201505390> Use /play to queue a song");
                } else {
                    manager.updateVoiceChannelStatus("<:music3:1504821132044402699> " + manager.getPlayer().getPlayingTrack().getInfo().title);
                }
            }
        }

        // Check if bot is in a voice channel
        if (guild.getSelfMember().getVoiceState() == null)
            return;
        AudioChannelUnion botChannel = guild.getSelfMember().getVoiceState().getChannel();
        if (botChannel == null) {
            return;
        }

        MusicManager manager = PlayerManager.getInstance().getMusicManager(guild);

        // Count members in the bot's channel (excluding bots)
        long humanMembers = botChannel.getMembers().stream()
                .filter(member -> !member.getUser().isBot())
                .count();

        if (humanMembers == 0) {
            manager.onAlone();
        } else {
            manager.onHumanJoined();
        }
    }

    private void handleBotDisconnect(Guild guild) {
        try {
            guild.getAudioManager().closeAudioConnection();
            // Clean up resources when bot is forcefully disconnected
            com.discord.musicbot.audio.MusicManager manager = PlayerManager.getInstance()
                    .getMusicManager(guild.getIdLong());
            if (manager != null) {
                manager.destroy();
            }
            PlayerManager.getInstance().removeMusicManager(guild.getIdLong());
            logger.info("Cleaned up music resources for guild: {}", guild.getName());
        } catch (Exception e) {
            logger.error("Error cleaning up after disconnect: {}", e.getMessage());
        }
    }
}
