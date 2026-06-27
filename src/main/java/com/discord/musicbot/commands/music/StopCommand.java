package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class StopCommand extends SlashCommand {
    @Override
    public String getName() {
        return "stop";
    }

    public void execute(CommandContext ctx) {
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());
        if (settings.isMode247Locked() && !com.discord.musicbot.commands.framework.CommandRegistry.isAuthorizedForLock(ctx)) {
            ctx.replyError("The 24/7 session is locked. You do not have permission to stop playback.");
            return;
        }

        if (ctx.getScheduler().getCurrentTrack() == null && ctx.getScheduler().getQueueSize() == 0) {
            ctx.replyError("Nothing is currently playing or queued!");
            return;
        }
        ctx.getScheduler().stop();
        ctx.replySuccess("Stopped playback and cleared the queue.");
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Stop command");
    }
}

