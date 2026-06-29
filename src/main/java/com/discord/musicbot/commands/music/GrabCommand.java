package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.framework.EmbedHelper;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.Color;

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

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(new Color(EmbedHelper.COLOR_MAIN));
        eb.setTitle("Saved Song: " + track.getInfo().title, track.getInfo().uri);
        eb.setAuthor(track.getInfo().author);
        eb.addField("Duration", EmbedHelper.formatDuration(track.getDuration()), true);
        
        if (track.getInfo().uri.contains("youtube.com") || track.getInfo().uri.contains("youtu.be")) {
            eb.setThumbnail("https://img.youtube.com/vi/" + track.getIdentifier() + "/hqdefault.jpg");
        }

        ctx.getMember().getUser().openPrivateChannel().queue(
            channel -> {
                channel.sendMessageEmbeds(eb.build()).queue(
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
