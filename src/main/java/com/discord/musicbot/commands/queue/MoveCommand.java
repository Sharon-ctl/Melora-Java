package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class MoveCommand extends SlashCommand {
    @Override
    public String getName() {
        return "move";
    }

    @Override
    public void execute(CommandContext ctx) {
        int from = ctx.getOption("from").getAsInt() - 1;
        int to = ctx.getOption("to").getAsInt() - 1;
        var track = ctx.getScheduler().move(from, to);
        if (track != null) {
            ctx.replySuccess("Moved: " + track.getInfo().title);
        } else {
            ctx.replyError("Invalid positions.");
        }
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Move track").addOption(OptionType.INTEGER, "from", "Current position", true).addOption(OptionType.INTEGER, "to", "New position", true);
    }
}

