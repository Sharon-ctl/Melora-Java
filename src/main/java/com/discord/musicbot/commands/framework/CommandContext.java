package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.audio.TrackScheduler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

public class CommandContext {
    private final SlashCommandInteractionEvent event;
    private final MusicManager musicManager;

    public CommandContext(SlashCommandInteractionEvent event) {
        this.event = event;
        if (event.getGuild() != null) {
            this.musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        } else {
            this.musicManager = null;
        }
    }

    public SlashCommandInteractionEvent getEvent() {
        return event;
    }

    public Guild getGuild() {
        return event.getGuild();
    }

    public Member getMember() {
        return event.getMember();
    }

    public User getUser() {
        return event.getUser();
    }

    public MessageChannel getChannel() {
        return event.getChannel();
    }

    public OptionMapping getOption(String name) {
        return event.getOption(name);
    }

    public MusicManager getMusicManager() {
        return musicManager;
    }

    public TrackScheduler getScheduler() {
        return musicManager != null ? musicManager.getScheduler() : null;
    }

    public void reply(String message) {
        event.reply(message).queue();
    }

    public void replyEphemeral(String message) {
        event.reply(message).setEphemeral(true).queue();
    }

    public void replySuccess(String message) {
        event.reply(EmbedHelper.MSG_SUCCESS + " " + message).queue();
    }

    public void replyError(String message) {
        event.reply(EmbedHelper.MSG_ERROR + " " + message).setEphemeral(true).queue();
    }

    public void deferReply() {
        event.deferReply().queue();
    }
}
