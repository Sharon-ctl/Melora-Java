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
        var commandOpt = ctx.getOption("command");
        if (commandOpt != null) {
            String commandName = commandOpt.getAsString().toLowerCase();
            var container = com.discord.musicbot.commands.framework.EmbedHelper.createCommandHelpContainer(commandName, "/", ctx.getEvent().getJDA());
            ctx.getEvent().replyComponents(container).useComponentsV2().queue();
        } else {
            var container = com.discord.musicbot.commands.framework.EmbedHelper.createHelpContainer("home", "/", ctx.getEvent().getJDA());
            ctx.getEvent().replyComponents(container).useComponentsV2().queue();
        }
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return false; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Help command")
                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "command", "Specific command to get help with", false, true);
    }
}

