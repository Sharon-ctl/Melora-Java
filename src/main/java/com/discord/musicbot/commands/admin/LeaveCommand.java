package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.framework.EmbedHelper;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class LeaveCommand extends SlashCommand {
    @Override
    public String getName() { return "leave"; }

    public void execute(CommandContext ctx) {
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());
        if (settings.isMode247Locked() && !com.discord.musicbot.commands.framework.CommandRegistry.isAuthorizedForLock(ctx)) {
            ctx.replyError("The 24/7 session is locked. You do not have permission to disconnect me.");
            return;
        }
        ctx.getMusicManager().disconnect();
        ctx.reply(EmbedHelper.MSG_SUCCESS + " Stopped playback and left the voice channel.");
    }

    @Override public boolean requiresDj() { return true; }
    @Override public boolean requiresVoice() { return false; }
    @Override public boolean requiresBotInVoice() { return true; }
    @Override public boolean requiresSameChannel() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Stops playback and leaves the voice channel");
    }
}
