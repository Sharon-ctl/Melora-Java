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
    public void onGuildLeave(net.dv8tion.jda.api.events.guild.GuildLeaveEvent event) {
        Guild guild = event.getGuild();
        logger.info("Bot left or was kicked from guild: {}. Cleaning up resources.", guild.getName());
        handleBotDisconnect(guild);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Guild guild = event.getGuild();

        // Handle bot being kicked/disconnected
        if (event.getMember().equals(guild.getSelfMember())) {
            if (PlayerManager.isShuttingDown) {
                return; // Ignore disconnects during shutdown sequence
            }

            if (event.getChannelLeft() != null && event.getChannelJoined() == null) {
                // Bot was disconnected from voice channel
                logger.info("Bot was disconnected from voice in guild: {}", guild.getName());
                
                // Clear the status in the channel it left
                if (event.getChannelLeft() instanceof net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel vc) {
                    try {
                        vc.modifyStatus("").queue(null, e -> {});
                    } catch (Exception ignored) {}
                }

                MusicManager manager = PlayerManager.getInstance().getMusicManager(guild.getIdLong());
                
                if (manager != null && manager.getNowPlayingChannelId() != null) {
                    net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel tc = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, manager.getNowPlayingChannelId());
                    if (tc != null) {
                        String desc = "☹️ I was forcefully disconnected! My queue has been cleared.";
                        var container = com.discord.musicbot.commands.framework.EmbedHelper.buildContainer(null, desc, null);
                        tc.sendMessage("").setComponents(container).useComponentsV2().queue(null, new net.dv8tion.jda.api.exceptions.ErrorHandler().ignore(net.dv8tion.jda.api.exceptions.ErrorResponseException.class));
                    }
                }
                
                com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guild.getId());
                if (settings.isMode247()) {
                    settings.setMode247(false);
                    settings.setMode247Locked(false);
                    settings.setLockedVoiceChannelId(null);
                    com.discord.musicbot.data.GuildSettingsManager.getInstance().markDirty();
                    com.discord.musicbot.data.DatabaseManager.getInstance().toggle247(guild.getId());
                }

                handleBotDisconnect(guild);
                return;
            } else if (event.getChannelJoined() != null) {
                MusicManager manager = PlayerManager.getInstance().getMusicManager(guild.getIdLong());
                if (manager != null) {
                    if (manager.getPlayer().getPlayingTrack() == null) {
                        manager.updateVoiceChannelStatus(com.discord.musicbot.config.EmojiConfig.getInstance().addMusic + " Use /play to queue a song");
                    } else {
                        manager.updateVoiceChannelStatus(com.discord.musicbot.config.EmojiConfig.getInstance().music + " " + manager.getPlayer().getPlayingTrack().getInfo().title);
                    }
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

        MusicManager manager = PlayerManager.getInstance().getMusicManager(guild.getIdLong());
        if (manager == null) return; // No active music session, nothing to do

        // Count members in the bot's channel (excluding bots)
        long humanMembers = botChannel.getMembers().stream()
                .filter(member -> !member.getUser().isBot())
                .count();

        if (humanMembers == 0) {
            manager.onBotAlone();
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
