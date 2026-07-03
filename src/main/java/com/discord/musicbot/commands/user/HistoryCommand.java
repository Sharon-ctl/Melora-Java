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

            var container = com.discord.musicbot.commands.framework.EmbedHelper.createHistoryContainer(history, 1);
            ctx.getEvent().replyComponents(container).useComponentsV2().queue();
            return;
        }

        if (sub.equals("clear")) {
            String userId = ctx.getUser().getId();
            net.dv8tion.jda.api.components.selections.StringSelectMenu menu =
                    net.dv8tion.jda.api.components.selections.StringSelectMenu.create("clear_data_" + userId)
                            .setPlaceholder("Select user data to delete (can select multiple)...")
                            .setMinValues(1)
                            .setMaxValues(6)
                            .addOption("Listening History", "history", "Delete your listening history")
                            .addOption("Playlists", "playlists", "Delete all your custom playlists")
                            .addOption("Favorites", "favorites", "Delete your liked/favorite tracks")
                            .addOption("Stats & Wrapped", "stats", "Delete your listening stats and wrapped data")
                            .addOption("Excluded Users", "excludes", "Clear your per-user exclusion list")
                            .addOption("All User Data", "all", "Permanently wipe all of your stored data")
                            .build();

            ctx.getEvent().reply(com.discord.musicbot.config.EmojiConfig.getInstance().error + " **Select the data you wish to permanently delete:**")
                    .addComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(menu))
                    .setEphemeral(true)
                    .queue();
        }
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "View or manage your user data and listening history")
                .addSubcommands(
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("view", "View your recent history"),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("clear", "Select user data (history, playlists, etc.) to clear")
                );
    }
}
