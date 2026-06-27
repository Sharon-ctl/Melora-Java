package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class DisconnectCommand extends SlashCommand {
    @Override
    public String getName() {
        return "disconnect";
    }

    public void execute(CommandContext ctx) {
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());
        if (settings.isMode247Locked() && !com.discord.musicbot.commands.framework.CommandRegistry.isAuthorizedForLock(ctx)) {
            ctx.replyError("The 24/7 session is locked. You do not have permission to disconnect me.");
            return;
        }
        ctx.getMusicManager().disconnect();
        ctx.replySuccess("Disconnected.");
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Disconnect command");
    }
}

