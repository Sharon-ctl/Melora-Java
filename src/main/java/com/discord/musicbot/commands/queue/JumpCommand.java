package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class JumpCommand extends SlashCommand {
    @Override
    public String getName() {
        return "jump";
    }

    @Override
    public void execute(CommandContext ctx) {
        int pos = ctx.getOption("position").getAsInt() - 1;
        if (ctx.getScheduler().jump(pos)) {
            ctx.replySuccess("Jumped to position " + (pos + 1));
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
        return Commands.slash(getName(), "Jump to track").addOption(OptionType.INTEGER, "position", "Queue position", true);
    }
}

