package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.data.GuildSettingsManager;
import com.discord.musicbot.data.model.GuildSettings;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class SettingsCommand extends SlashCommand {

    @Override
    public String getName() {
        return "settings";
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash("settings", "Configure bot settings for this server")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR));
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx.getEvent().getGuild() == null) {
            ctx.reply("This command can only be used in a server.");
            return;
        }

        // Defense-in-depth: Discord's DefaultMemberPermissions is client-side only
        // and can be overridden by server owners via role permission overrides.
        if (!ctx.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            ctx.replyError("You must be an Administrator to use this command.");
            return;
        }

        GuildSettings settings = GuildSettingsManager.getInstance().getSettings(ctx.getEvent().getGuild().getId());

        ctx.getEvent().replyEmbeds(EmbedHelper.createSettingsEmbed(settings))
                .setComponents(EmbedHelper.createSettingsComponents(settings))
                .setEphemeral(true)
                .queue();
    }
}
