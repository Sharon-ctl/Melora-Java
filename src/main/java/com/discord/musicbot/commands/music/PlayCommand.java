package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import com.discord.musicbot.audio.PlayerManager;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class PlayCommand extends SlashCommand {
    @Override
    public String getName() {
        return "play";
    }

    @Override
    public void execute(CommandContext ctx) {
        String query = ctx.getOption("query").getAsString();
        ctx.deferReply();
        ctx.getMusicManager().setNowPlayingChannel(ctx.getChannel().getId());
        if (!query.startsWith("http") && !query.contains(":")) {
            query = "ytsearch:" + query;
        }
        PlayerManager.getInstance().loadAndPlay(ctx.getEvent(), query);
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Play a song").addOption(OptionType.STRING, "query", "Search query or URL", true, true);
    }
}

