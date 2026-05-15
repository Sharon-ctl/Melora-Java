package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class StopCommand extends SlashCommand {
    @Override
    public String getName() {
        return "stop";
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.getScheduler().stop();
        ctx.replySuccess("Stopped playback and cleared the queue.");
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Stop command");
    }
}

