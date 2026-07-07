package com.discord.musicbot.commands.admin;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.data.HistoryManager;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class AutoplayCommand extends SlashCommand {
    private static final Logger logger = LoggerFactory.getLogger(AutoplayCommand.class);

    @Override
    public String getName() {
        return "autoplay";
    }

    @Override
    public void execute(CommandContext ctx) {
        MusicManager musicManager = ctx.getMusicManager();
        boolean ap = ctx.getScheduler().toggleAutoplay();
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());
        settings.setAutoplay(ap);
        com.discord.musicbot.data.GuildSettingsManager.getInstance().markDirty();
        ctx.replySuccess("Autoplay is now " + (ap ? "ON" : "OFF"));
        musicManager.updateNowPlayingMessage();

        // If autoplay was just enabled and nothing is playing, seed from user history
        if (ap && ctx.getScheduler().getCurrentTrack() == null && ctx.getScheduler().getQueue().isEmpty()) {
            musicManager.setNowPlayingChannel(ctx.getChannel().getId());
            seedFromHistory(ctx, musicManager);
        }
    }

    /**
     * Seeds autoplay by searching for a song similar to the user's most recent listening history.
     * Picks a recent track from the user's history and queues a search for it so autoplay has a seed.
     */
    private void seedFromHistory(CommandContext ctx, MusicManager musicManager) {
        String userId = ctx.getUser().getId();
        List<HistoryManager.HistoryEntry> history = HistoryManager.getInstance().getUserHistory(userId);

        if (history.isEmpty()) {
            try {
                ctx.getChannel().sendMessageComponents(
                    net.dv8tion.jda.api.components.container.Container.of(net.dv8tion.jda.api.components.textdisplay.TextDisplay.of(EmbedHelper.MSG_ERROR + " You have no listening history to seed autoplay from. Play a song first!")).withAccentColor(EmbedHelper.COLOR_MAIN)
                ).useComponentsV2().queue();
            } catch (Exception ignored) {}
            return;
        }

        // Pick a random track from the user's recent history (top 10) to use as seed
        int range = Math.min(10, history.size());
        HistoryManager.HistoryEntry seed = history.get(new java.util.Random().nextInt(range));

        logger.info("[AutoPlay] Seeding from user {} history: \"{}\" by {}", userId, seed.title, seed.author);

        // Build a search query from the history entry
        String query;
        if (seed.uri != null && seed.uri.startsWith("http")) {
            query = seed.uri;
        } else {
            query = seed.title + " " + seed.author;
        }

        PlayerManager.getInstance().loadAndPlay(ctx.getEvent(), query);
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public boolean requiresSameChannel() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Toggle autoplay - plays similar songs when the queue ends");
    }
}
