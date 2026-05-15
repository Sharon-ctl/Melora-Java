package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class TimeCommand extends SlashCommand {
    @Override
    public String getName() {
        return "time";
    }

    @Override
    public void execute(CommandContext ctx) {
        var track = ctx.getScheduler().getCurrentTrack();
        if (track == null) {
            ctx.replyError("Nothing is playing.");
            return;
        }
        long pos = track.getPosition();
        long dur = track.getDuration();
        String msg = com.discord.musicbot.commands.framework.EmbedHelper.formatTime(pos) + " / " + com.discord.musicbot.commands.framework.EmbedHelper.formatTime(dur);
        ctx.replySuccess(msg);
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Time command");
    }
}

