package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.audio.TrackScheduler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

public class CommandContext {
    private final SlashCommandInteractionEvent event;
    private MusicManager musicManager;

    public CommandContext(SlashCommandInteractionEvent event) {
        this.event = event;
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
        if (musicManager == null && event.getGuild() != null) {
            musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        }
        return musicManager;
    }

    public TrackScheduler getScheduler() {
        MusicManager manager = getMusicManager();
        return manager != null ? manager.getScheduler() : null;
    }

    public void reply(String message) {
        Container container = Container.of(TextDisplay.of(message)).withAccentColor(EmbedHelper.COLOR_MAIN);
        if (event.isAcknowledged()) event.getHook().sendMessageComponents(container).useComponentsV2().queue();
        else event.replyComponents(container).useComponentsV2().queue();
    }

    public void replyEphemeral(String message) {
        Container container = Container.of(TextDisplay.of(message)).withAccentColor(EmbedHelper.COLOR_MAIN);
        if (event.isAcknowledged()) event.getHook().sendMessageComponents(container).useComponentsV2().setEphemeral(true).queue();
        else event.replyComponents(container).useComponentsV2().setEphemeral(true).queue();
    }

    public void replySuccess(String message) {
        Container container = Container.of(TextDisplay.of(EmbedHelper.MSG_SUCCESS + " " + message)).withAccentColor(EmbedHelper.COLOR_MAIN);
        if (event.isAcknowledged()) event.getHook().sendMessageComponents(container).useComponentsV2().queue();
        else event.replyComponents(container).useComponentsV2().queue();
    }

    public void replyError(String message) {
        Container container = Container.of(TextDisplay.of(EmbedHelper.MSG_ERROR + " " + message)).withAccentColor(EmbedHelper.COLOR_MAIN);
        if (event.isAcknowledged()) event.getHook().sendMessageComponents(container).useComponentsV2().setEphemeral(true).queue();
        else event.replyComponents(container).useComponentsV2().setEphemeral(true).queue();
    }

    public void replyContainer(Container container) {
        if (event.isAcknowledged()) event.getHook().sendMessageComponents(container).useComponentsV2().queue();
        else event.replyComponents(container).useComponentsV2().queue();
    }

    public void replyContainerEphemeral(Container container) {
        if (event.isAcknowledged()) event.getHook().sendMessageComponents(container).useComponentsV2().setEphemeral(true).queue();
        else event.replyComponents(container).useComponentsV2().setEphemeral(true).queue();
    }

    public void deferReply() {
        event.deferReply().queue();
    }
}
