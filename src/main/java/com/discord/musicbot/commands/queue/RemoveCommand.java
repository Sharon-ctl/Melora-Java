package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class RemoveCommand extends SlashCommand {
    @Override
    public String getName() {
        return "remove";
    }

    @Override
    public void execute(CommandContext ctx) {
        int pos = ctx.getOption("position").getAsInt() - 1;
        var track = ctx.getScheduler().remove(pos);
        if (track != null) {
            ctx.replySuccess("Removed: " + track.getInfo().title);
        } else {
            ctx.replyError("Invalid position.");
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
        return Commands.slash(getName(), "Remove track").addOption(OptionType.INTEGER, "position", "Queue position", true);
    }
}

