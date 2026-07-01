package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.data.HistoryManager;
import com.discord.musicbot.data.PlaylistManager;
import com.discord.musicbot.data.HistoryManager.HistoryEntry;
import com.discord.musicbot.data.model.PlaylistData;
import com.discord.musicbot.data.model.PlaylistTrack;
import com.discord.musicbot.audio.PlayerManager;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;
import java.util.Random;

public class PlayRandomCommand extends SlashCommand {
    @Override
    public String getName() {
        return "playrandom";
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.getMusicManager().setNowPlayingChannel(ctx.getChannel().getId());
        boolean continuous = ctx.getOption("continuous") == null || ctx.getOption("continuous").getAsBoolean();
        ctx.getScheduler().setRandomPlay(continuous);
        String source = ctx.getOption("source") != null ? ctx.getOption("source").getAsString() : "history";
        
        String trackQuery = null;
        String trackTitle = null;
        Random random = new Random();

        if (source.equalsIgnoreCase("favorites")) {
            PlaylistData favs = PlaylistManager.getInstance().getFavorites(ctx.getUser().getId());
            if (favs == null || favs.getTracks().isEmpty()) {
                ctx.replyError("You don't have any tracks in your favorites!");
                return;
            }
            PlaylistTrack pt = favs.getTracks().get(random.nextInt(favs.getTracks().size()));
            trackQuery = pt.getUri() != null ? pt.getUri() : "ytsearch:" + pt.getAuthor() + " " + pt.getTitle();
            trackTitle = pt.getTitle();
        } else {
            List<HistoryEntry> history = HistoryManager.getInstance().getRecent(200);
            if (history.isEmpty()) {
                ctx.replyError("The bot history is empty!");
                return;
            }
            HistoryEntry he = history.get(random.nextInt(history.size()));
            trackQuery = he.uri != null ? he.uri : "ytsearch:" + he.author + " " + he.title;
            trackTitle = he.title;
        }

        final String finalQuery = trackQuery;
        
        ctx.getEvent().reply(EmbedHelper.MSG_SUCCESS + " Found random track: **" + trackTitle + "**. Loading...").queue(
            hook -> {
                String requesterId = ctx.getUser().getId();
                PlayerManager.getInstance().loadItemOrdered(ctx.getGuild(), finalQuery, new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        track.setUserData("{\"requester\":\"" + requesterId + "\"}");
                        ctx.getScheduler().queue(track);
                        hook.editOriginal(EmbedHelper.MSG_SUCCESS + " Queued random track: **" + track.getInfo().title + "**").queue();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        if (!playlist.getTracks().isEmpty()) {
                            trackLoaded(playlist.getTracks().get(0));
                        } else {
                            noMatches();
                        }
                    }

                    @Override
                    public void noMatches() {
                        hook.editOriginal(EmbedHelper.MSG_ERROR + " Could not resolve the selected random track.").queue();
                    }

                    @Override
                    public void loadFailed(FriendlyException exception) {
                        hook.editOriginal(EmbedHelper.MSG_ERROR + " Failed to load random track: " + exception.getMessage()).queue();
                    }
                });
            }
        );
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Play a random track from your favorites or bot history")
            .addOptions(
                new OptionData(OptionType.STRING, "source", "Where to pick the random track from", false)
                    .addChoice("Bot History", "history")
                    .addChoice("My Favorites", "favorites"),
                new OptionData(OptionType.BOOLEAN, "continuous", "Keep playing random songs continuously (default: true)", false)
            );
    }
}
