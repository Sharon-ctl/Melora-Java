package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import com.discord.musicbot.audio.PlayerManager;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class PlayNextCommand extends SlashCommand {
    @Override
    public String getName() {
        return "playnext";
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.getMusicManager().setNowPlayingChannel(ctx.getChannel().getId());
        String query = ctx.getOption("query").getAsString();
        ctx.deferReply();
        PlayerManager.getInstance().loadAndInsert(ctx.getEvent(), query, 0);
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Adds a track to the top of the queue (plays next)")
            .addOption(OptionType.STRING, "query", "Query/URL", true, true);
    }
}
