package com.discord.musicbot.commands.framework;

import net.dv8tion.jda.api.interactions.commands.build.CommandData;

public abstract class SlashCommand {
    public abstract String getName();
    public abstract void execute(CommandContext ctx);
    public boolean requiresDj() { return false; }
    public boolean requiresVoice() { return false; }
    public boolean requiresBotInVoice() { return false; }
    public boolean requiresSameChannel() { return true; }
    public abstract CommandData getCommandData();
}
