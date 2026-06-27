package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class Mode247Command extends SlashCommand {
    @Override
    public String getName() {
        return "247";
    }

    public void execute(CommandContext ctx) {
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());
        
        net.dv8tion.jda.api.interactions.commands.OptionMapping lockOption = ctx.getOption("lock");
        if (lockOption != null) {
            boolean lock = lockOption.getAsBoolean();
            if (!com.discord.musicbot.commands.framework.CommandRegistry.isAuthorizedForLock(ctx)) {
                ctx.replyError("You do not have permission to modify 24/7 Lock. Only Administrators and configured DJs can do this.");
                return;
            }
            if (lock) {
                if (!settings.isMode247()) {
                    ctx.replyError("You must enable 24/7 mode before locking it.");
                    return;
                }
                settings.setMode247Locked(true);
                var botVoice = ctx.getGuild().getSelfMember().getVoiceState();
                if (botVoice != null && botVoice.inAudioChannel()) {
                    settings.setLockedVoiceChannelId(botVoice.getChannel().getId());
                }
                com.discord.musicbot.data.GuildSettingsManager.getInstance().markDirty();
                ctx.replySuccess("24/7 session is now **LOCKED**. Only authorized users can disconnect me.");
            } else {
                settings.setMode247Locked(false);
                settings.setLockedVoiceChannelId(null);
                com.discord.musicbot.data.GuildSettingsManager.getInstance().markDirty();
                ctx.replySuccess("24/7 session is now **UNLOCKED**.");
            }
            return;
        }

        if (settings.isMode247Locked() && settings.isMode247()) {
            if (!com.discord.musicbot.commands.framework.CommandRegistry.isAuthorizedForLock(ctx)) {
                ctx.replyError("The 24/7 session is locked. You do not have permission to disable it.");
                return;
            }
        }

        boolean enabled = com.discord.musicbot.data.DatabaseManager.getInstance().toggle247(ctx.getGuild().getId());
        ctx.getMusicManager().set247(enabled);
        settings.setMode247(enabled);
        if (!enabled) {
            settings.setMode247Locked(false);
            settings.setLockedVoiceChannelId(null);
        }
        com.discord.musicbot.data.GuildSettingsManager.getInstance().markDirty();
        ctx.getMusicManager().updateNowPlayingMessage();
        ctx.replySuccess("24/7 mode is now " + (enabled ? "**ON**" : "**OFF**"));
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    public CommandData getCommandData() {
        return Commands.slash(getName(), "Toggles 24/7 mode")
                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.BOOLEAN, "lock", "Lock the 24/7 session to prevent unauthorized users from disconnecting", false);
    }
}

