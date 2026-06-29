package com.discord.musicbot.commands.interaction;

import com.discord.musicbot.audio.VoteManager;
import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public class VoteCommand extends SlashCommand {

    @Override
    public String getName() {
        return "vote";
    }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null) { ctx.replyError("Invalid subcommand."); return; }

        VoteManager.VoteType type;
        int defaultThreshold = 60; // Usually loaded from GuildSettingsManager but passed as fallback

        switch (sub) {
            case "skip": type = VoteManager.VoteType.SKIP; break;
            case "clear": type = VoteManager.VoteType.CLEAR; break;
            case "disconnect": type = VoteManager.VoteType.DISCONNECT; break;
            default: ctx.replyError("Unknown vote type."); return;
        }

        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());
        switch (type) {
            case SKIP: defaultThreshold = settings.getVoteSkipThreshold(); break;
            case CLEAR: defaultThreshold = settings.getVoteClearThreshold(); break;
            case DISCONNECT: defaultThreshold = settings.getVoteDisconnectThreshold(); break;
        }

        String result = VoteManager.getInstance().startVote(ctx.getGuild(), ctx.getMember(), type, defaultThreshold);

        if ("passed".equals(result)) {
            ctx.replySuccess("Vote automatically passed (Not enough listeners to require a vote).");
        } else if ("started".equals(result)) {
            int required = (int) Math.ceil((VoteManager.getInstance().getActiveListenersCount(ctx.getGuild()) * defaultThreshold) / 100.0);
            var container = EmbedHelper.createVoteContainer(type.name(), 1, required);
            java.util.List<net.dv8tion.jda.api.components.container.ContainerChildComponent> comps = new java.util.ArrayList<>(container.getComponents());
            comps.add(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                   net.dv8tion.jda.api.components.buttons.Button.success("vote_yes", "Yes"),
                   net.dv8tion.jda.api.components.buttons.Button.danger("vote_no", "No")
            ));
            var finalContainer = net.dv8tion.jda.api.components.container.Container.of(comps);
            ctx.getEvent().replyComponents(finalContainer).useComponentsV2().queue(hook -> {
                   hook.retrieveOriginal().queue(msg -> {
                       VoteManager.getInstance().registerVoteMessage(ctx.getGuild().getId(), msg.getChannel().getId(), msg.getId());
                   });
               });
        } else {
            ctx.replyError(result);
        }
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Start a vote")
                .addSubcommands(
                        new SubcommandData("skip", "Vote to skip the current track"),
                        new SubcommandData("clear", "Vote to clear the queue"),
                        new SubcommandData("disconnect", "Vote to disconnect the bot")
                );
    }
}
