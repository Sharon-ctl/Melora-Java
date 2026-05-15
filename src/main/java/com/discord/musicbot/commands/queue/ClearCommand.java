package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class ClearCommand extends SlashCommand {
    @Override
    public String getName() {
        return "clear";
    }

    @Override
    public void execute(CommandContext ctx) {
        int size = ctx.getScheduler().clear();
        ctx.replySuccess("Cleared " + size + " tracks from the queue.");
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Clear command");
    }
}

