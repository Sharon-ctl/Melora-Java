package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class PreviousCommand extends SlashCommand {
    @Override
    public String getName() {
        return "previous";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx.getScheduler().previousTrack()) {
            ctx.replySuccess("Playing previous track.");
        } else {
            ctx.replyError("No previous track found in history.");
        }
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Previous command");
    }
}

