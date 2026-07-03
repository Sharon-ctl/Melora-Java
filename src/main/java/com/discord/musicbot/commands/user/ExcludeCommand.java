package com.discord.musicbot.commands.user;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.data.UserExcludeManager;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.Set;

public class ExcludeCommand extends SlashCommand {
    @Override
    public String getName() {
        return "exclude";
    }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null) {
            ctx.replyError("Invalid subcommand.");
            return;
        }

        String requesterId = ctx.getUser().getId();
        boolean hasPerm = ctx.getGuild() != null
                && ctx.getGuild().getSelfMember().hasPermission(Permission.VOICE_DEAF_OTHERS);
        String permWarning = !hasPerm ? "\n\n" + com.discord.musicbot.config.EmojiConfig.getInstance().error
                + " **Note:** The bot currently lacks the **Deafen Members** permission in this server, so automatic deafening will not take effect until an admin grants it."
                : "";

        switch (sub) {
            case "add": {
                User target = ctx.getEvent().getOption("user") != null ? ctx.getEvent().getOption("user").getAsUser()
                        : null;
                if (target == null) {
                    ctx.replyError("You must specify a valid user.");
                    return;
                }
                if (target.isBot() || target.getId().equals(requesterId)) {
                    ctx.replyError("You cannot add yourself or a bot to your exclusion list.");
                    return;
                }
                boolean added = UserExcludeManager.getInstance().addExclude(requesterId, target.getId());
                if (added) {
                    ctx.replySuccess("Added **" + target.getEffectiveName()
                            + "** to your exclusion list. They will be automatically server-deafened when your requested tracks are playing."
                            + permWarning);
                } else {
                    ctx.replyError(
                            "That user is already in your exclusion list or you have reached the maximum limit of "
                                    + UserExcludeManager.MAX_EXCLUDES_PER_USER + " excluded users.");
                }
                break;
            }
            case "remove": {
                User target = ctx.getEvent().getOption("user") != null ? ctx.getEvent().getOption("user").getAsUser()
                        : null;
                if (target == null) {
                    ctx.replyError("You must specify a valid user.");
                    return;
                }
                boolean removed = UserExcludeManager.getInstance().removeExclude(requesterId, target.getId());
                if (removed) {
                    ctx.replySuccess("Removed **" + target.getEffectiveName() + "** from your exclusion list.");
                } else {
                    ctx.replyError("That user is not currently in your exclusion list.");
                }
                break;
            }
            case "list": {
                Set<String> excludes = UserExcludeManager.getInstance().getExcludes(requesterId);
                if (excludes.isEmpty()) {
                    ctx.replyError("Your exclusion list is currently empty. Use `/exclude add @user` to add someone.");
                    return;
                }
                StringBuilder sb = new StringBuilder("**Your Excluded Users:**\n\n");
                for (String id : excludes) {
                    sb.append("• <@").append(id).append(">\n");
                }
                sb.append("\n*These members are automatically server-deafened while your requested songs play.*")
                        .append(permWarning);
                ctx.replySuccess(sb.toString());
                break;
            }
            case "clear": {
                UserExcludeManager.getInstance().clearExcludes(requesterId);
                ctx.replySuccess("Cleared your exclusion list.");
                break;
            }
            default:
                ctx.replyError("Unknown subcommand.");
                break;
        }
    }

    @Override
    public boolean requiresDj() {
        return false;
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
        return Commands.slash(getName(), "Manage per-user audio exclusion (deafen specified users during your tracks)")
                .addSubcommands(
                        new SubcommandData("add", "Add a user to your exclusion list so they cannot hear your songs")
                                .addOption(OptionType.USER, "user", "The user to exclude", true),
                        new SubcommandData("remove", "Remove a user from your exclusion list")
                                .addOption(OptionType.USER, "user", "The user to remove", true),
                        new SubcommandData("list", "View your current exclusion list"),
                        new SubcommandData("clear", "Clear all users from your exclusion list"));
    }
}
