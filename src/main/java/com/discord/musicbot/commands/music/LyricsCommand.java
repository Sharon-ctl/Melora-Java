package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class LyricsCommand extends SlashCommand {
    @Override
    public String getName() {
        return "lyrics";
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.replyEphemeral("Lyrics fetching not implemented natively yet.");
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return false; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Lyrics command");
    }
}

