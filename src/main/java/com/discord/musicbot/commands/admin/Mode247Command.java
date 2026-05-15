package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class Mode247Command extends SlashCommand {
    @Override
    public String getName() {
        return "247";
    }

    @Override
    public void execute(CommandContext ctx) {
        boolean enabled = com.discord.musicbot.data.DatabaseManager.getInstance().toggle247(ctx.getGuild().getId());
        ctx.getMusicManager().set247(enabled);
        ctx.getMusicManager().updateNowPlayingMessage();
        ctx.replySuccess("24/7 mode is now " + (enabled ? "ON" : "OFF"));
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Mode247 command");
    }
}

