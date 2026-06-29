package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.framework.EmbedHelper;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;


public class GrabCommand extends SlashCommand {
    @Override
    public String getName() { return "grab"; }

    @Override
    public void execute(CommandContext ctx) {
        AudioTrack track = ctx.getMusicManager().getPlayer().getPlayingTrack();
        if (track == null) {
            ctx.replyError("Nothing is currently playing!");
            return;
        }

        String title = "Saved Song: " + track.getInfo().title;
        StringBuilder desc = new StringBuilder();
        desc.append("**Author:** ").append(track.getInfo().author).append("\n");
        desc.append("**Duration:** ").append(EmbedHelper.formatDuration(track.getDuration())).append("\n");
        desc.append("**Link:** ").append(track.getInfo().uri).append("\n");
        
        java.util.List<net.dv8tion.jda.api.components.container.ContainerChildComponent> comps = new java.util.ArrayList<>();
        java.util.List<net.dv8tion.jda.api.components.textdisplay.TextDisplay> texts = new java.util.ArrayList<>();
        texts.add(net.dv8tion.jda.api.components.textdisplay.TextDisplay.of("### " + title));
        texts.add(net.dv8tion.jda.api.components.textdisplay.TextDisplay.of(desc.toString()));

        if (track.getInfo().uri.contains("youtube.com") || track.getInfo().uri.contains("youtu.be")) {
            comps.add(net.dv8tion.jda.api.components.section.Section.of(
                net.dv8tion.jda.api.components.thumbnail.Thumbnail.fromUrl("https://img.youtube.com/vi/" + track.getIdentifier() + "/hqdefault.jpg"),
                texts
            ));
        } else {
            comps.addAll(texts);
        }

        var container = net.dv8tion.jda.api.components.container.Container.of(comps);

        ctx.getMember().getUser().openPrivateChannel().queue(
            channel -> {
                channel.sendMessageComponents(container).useComponentsV2().queue(
                    success -> ctx.reply(EmbedHelper.MSG_SUCCESS + " I've sent you a DM with the current song!"),
                    error -> ctx.replyError("I couldn't send you a DM. Please check your privacy settings.")
                );
            },
            error -> ctx.replyError("I couldn't send you a DM. Please check your privacy settings.")
        );
    }

    @Override public boolean requiresDj() { return false; }
    @Override public boolean requiresVoice() { return false; }
    @Override public boolean requiresBotInVoice() { return true; }
    @Override public boolean requiresSameChannel() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "DMs you the currently playing track to save it for later");
    }
}
