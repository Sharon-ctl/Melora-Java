package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import com.discord.musicbot.audio.PlayerManager;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class InsertCommand extends SlashCommand {
    @Override
    public String getName() {
        return "insert";
    }

    @Override
    public void execute(CommandContext ctx) {
        String query = ctx.getOption("query").getAsString();
        int pos = ctx.getOption("position").getAsInt() - 1;
        ctx.deferReply();
        if (!query.startsWith("http") && !query.contains(":")) {
            query = "ytsearch:" + query;
        }
        PlayerManager.getInstance().loadAndInsert(ctx.getEvent(), query, pos);
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Insert track").addOption(OptionType.STRING, "query", "Query/URL", true, true).addOption(OptionType.INTEGER, "position", "Position", true);
    }
}

