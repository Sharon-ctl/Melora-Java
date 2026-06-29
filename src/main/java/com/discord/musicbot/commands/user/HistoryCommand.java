package com.discord.musicbot.commands.user;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.data.HistoryManager;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;

public class HistoryCommand extends SlashCommand {

    @Override
    public String getName() {
        return "history";
    }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null || sub.equals("view")) {
            String userId = ctx.getUser().getId();
            List<HistoryManager.HistoryEntry> history = HistoryManager.getInstance().getUserHistory(userId);
            if (history.isEmpty()) {
                ctx.replyError("You have no listening history.");
                return;
            }

            int page = 1;
            int maxPages = Math.max(1, (int) Math.ceil(history.size() / 10.0));
            var container = com.discord.musicbot.commands.framework.EmbedHelper.createHistoryContainer(history, page);
            
            java.util.List<net.dv8tion.jda.api.components.container.ContainerChildComponent> comps = new java.util.ArrayList<>(container.getComponents());
            comps.add(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                    com.discord.musicbot.commands.framework.EmbedHelper.createPaginationButtons("history", page, maxPages)
            ));
            var finalContainer = net.dv8tion.jda.api.components.container.Container.of(comps);
            ctx.getEvent().reply("").setComponents(finalContainer).useComponentsV2().queue();
            return;
        }

        if (sub.equals("clear")) {
            String userId = ctx.getUser().getId();
            HistoryManager.getInstance().clearHistory(userId);
            com.discord.musicbot.data.StatsManager.getInstance().clearStats(userId);
            com.discord.musicbot.data.PlaylistManager.getInstance().deleteAllUserData(userId);
            ctx.replySuccess("All of your user data (history, stats, wrapped, playlists, and favorites) has been permanently cleared.");
        }
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "View or clear your listening history")
                .addSubcommands(
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("view", "View your recent history"),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("clear", "Clear your listening history")
                );
    }
}
