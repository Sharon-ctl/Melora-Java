package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class ResumeCommand extends SlashCommand {
    @Override
    public String getName() {
        return "resume";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.getScheduler().isPaused()) {
            ctx.replyError("Already playing.");
            return;
        }
        ctx.getScheduler().resume();
        ctx.replySuccess("Resumed the track.");
        ctx.getMusicManager().updateNowPlayingMessage();
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Resume command");
    }
}

