package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.data.GuildSettingsManager;
import com.discord.musicbot.data.model.GuildSettings;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public class CrossfadeCommand extends SlashCommand {
    @Override
    public String getName() {
        return "crossfade";
    }

    @Override
    public void execute(CommandContext ctx) {
        GuildSettings settings = GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());
        
        Boolean enableOpt = ctx.getOption("enable") != null ? ctx.getOption("enable").getAsBoolean() : null;
        Long durationOpt = ctx.getOption("duration") != null ? ctx.getOption("duration").getAsLong() : null;

        if (enableOpt == null && durationOpt == null) {
            ctx.replySuccess("Crossfade is currently **" + (settings.isCrossfadeEnabled() ? "ON" : "OFF") + "** with a duration of **" + settings.getCrossfadeDuration() + "** seconds.");
            return;
        }

        if (enableOpt != null) {
            settings.setCrossfadeEnabled(enableOpt);
        }
        if (durationOpt != null) {
            int dur = Math.max(1, Math.min(15, durationOpt.intValue()));
            settings.setCrossfadeDuration(dur);
        }

        GuildSettingsManager.getInstance().markDirty();
        ctx.replySuccess("Crossfade is now **" + (settings.isCrossfadeEnabled() ? "ON" : "OFF") + "** with a duration of **" + settings.getCrossfadeDuration() + "** seconds.");
    }

    @Override
    public boolean requiresDj() {
        return true;
    }

    @Override
    public boolean requiresVoice() {
        return false;
    }

    @Override
    public boolean requiresBotInVoice() {
        return false;
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Configure persistent audio crossfading between tracks")
            .addOptions(
                new OptionData(OptionType.BOOLEAN, "enable", "Enable or disable crossfading", false),
                new OptionData(OptionType.INTEGER, "duration", "Duration of crossfade in seconds (1-15)", false)
            );
    }
}
