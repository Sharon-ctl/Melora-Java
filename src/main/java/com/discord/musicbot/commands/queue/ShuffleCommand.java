package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class ShuffleCommand extends SlashCommand {
    @Override
    public String getName() {
        return "shuffle";
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.getScheduler().shuffle();
        ctx.replySuccess("Queue shuffled.");
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Shuffle command");
    }
}

