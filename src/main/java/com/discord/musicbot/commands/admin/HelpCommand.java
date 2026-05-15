package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class HelpCommand extends SlashCommand {
    @Override
    public String getName() {
        return "help";
    }

    @Override
    public void execute(CommandContext ctx) {
        var embed = com.discord.musicbot.commands.framework.EmbedHelper.createHelpEmbed("playback", "/", ctx.getEvent().getJDA());
        ctx.getEvent().replyEmbeds(embed).setComponents(com.discord.musicbot.commands.framework.EmbedHelper.createHelpMenu()).queue();
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return false; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Help command");
    }
}

