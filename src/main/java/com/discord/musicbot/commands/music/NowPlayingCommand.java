package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class NowPlayingCommand extends SlashCommand {
    @Override
    public String getName() {
        return "nowplaying";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx.getScheduler().getCurrentTrack() == null) {
            ctx.replyEphemeral("Nothing is playing right now.");
            return;
        }
        ctx.getMusicManager().setNowPlayingChannel(ctx.getChannel().getId());
        ctx.getMusicManager().sendNowPlayingMessage(true);
        ctx.replyEphemeral("Now playing widget created.");
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "NowPlaying command");
    }
}

