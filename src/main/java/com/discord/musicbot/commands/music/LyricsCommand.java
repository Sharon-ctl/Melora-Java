package com.discord.musicbot.commands.music;

import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.lyrics.LyricsManager;
import com.discord.musicbot.lyrics.LyricsCache;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;

public class LyricsCommand extends SlashCommand {

    @Override
    public String getName() {
        return "lyrics";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx.getEvent().getOption("live") != null) {
            boolean live = ctx.getEvent().getOption("live").getAsBoolean();
            com.discord.musicbot.audio.MusicManager mm = PlayerManager.getInstance()
                    .getMusicManager(ctx.getGuild().getIdLong());
            if (mm == null || mm.getPlayer().getPlayingTrack() == null) {
                ctx.replyError("There is no music playing right now to configure live synced lyrics for.");
                return;
            }
            if (live) {
                mm.enableInstantKaraoke(ctx.getChannel());
                ctx.replySuccess("**Live Instant Lyrics Enabled!** Synced real-time timestamped lyrics are now active and tracking in a dedicated container below!");
            } else {
                mm.setKaraokeMode(false);
                ctx.replySuccess("**Live Lyrics Disabled.** The live lyrics container has been cleaned up.");
            }
            return;
        }

        String query = null;
        if (ctx.getEvent().getOption("query") != null) {
            query = ctx.getOption("query").getAsString();
        } else {
            com.discord.musicbot.audio.MusicManager mm = PlayerManager.getInstance()
                    .getMusicManager(ctx.getGuild().getIdLong());
            if (mm != null && mm.getPlayer().getPlayingTrack() != null) {
                AudioTrack track = mm.getPlayer().getPlayingTrack();
                query = track.getInfo().author + " " + track.getInfo().title;
                // Remove some stuff that might ruin searches like "(Official Video)"
                query = query.replaceAll("(?i)\\b(official|music video|audio|lyric video|lyrics)\\b", "");
                query = query.replaceAll("[\\(\\[].*?[\\)\\]]", "").trim();
            }
        }

        if (query == null || query.isBlank()) {
            ctx.replyError("Please provide a song name or play a song first.");
            return;
        }
        if (query.length() > 256) {
            ctx.replyError("Search query is too long! Please keep it under 256 characters.");
            return;
        }

        ctx.deferReply();
        String finalQuery = query;

        LyricsManager.fetchLyrics(finalQuery).whenComplete((result, error) -> {
            if (error != null || result == null || result.text == null || result.text.isBlank()) {
                ctx.getEvent().getHook()
                        .editOriginal(EmbedHelper.MSG_ERROR + " Could not find lyrics for **" + finalQuery + "**")
                        .queue();
                return;
            }

            // Split into pages by verses using EmbedHelper
            List<String> pages = EmbedHelper.splitLyrics(result.text);

            String lyricsId = java.util.UUID.randomUUID().toString();
            LyricsCache.put(lyricsId, new LyricsCache.LyricsData(finalQuery, pages, result.source, result.isLive));

            var container = EmbedHelper.createLyricsContainer(lyricsId, finalQuery, pages, 1, result.source,
                    result.isLive);
            ctx.getEvent().getHook().sendMessageComponents(container).useComponentsV2().queue();
        });
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
        return Commands.slash(getName(), "Get lyrics for a song or enable live synced lyrics")
                .addOptions(
                        new OptionData(OptionType.STRING, "query", "The song name (leave blank for current song)", false),
                        new OptionData(OptionType.BOOLEAN, "live", "Enable instant live synced karaoke lyrics on Now Playing embed", false)
                );
    }
}
