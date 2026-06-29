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
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.components.buttons.Button;


import java.util.List;

public class LyricsCommand extends SlashCommand {

    @Override
    public String getName() {
        return "lyrics";
    }

    @Override
    public void execute(CommandContext ctx) {
        String query = null;
        if (ctx.getEvent().getOption("query") != null) {
            query = ctx.getOption("query").getAsString();
        } else {
            com.discord.musicbot.audio.MusicManager mm = PlayerManager.getInstance().getMusicManager(ctx.getGuild().getIdLong());
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
                ctx.getEvent().getHook().editOriginal(EmbedHelper.MSG_ERROR + " Could not find lyrics for **" + finalQuery + "**").queue();
                return;
            }

            // Split into pages by verses using EmbedHelper
            List<String> pages = EmbedHelper.splitLyrics(result.text);

            // Store pages temporarily or pass them in the components? 
            // Better to cache them or since we don't have a database for lyrics, 
            // we can use a temporary in-memory cache for pagination.
            // But wait, the bot's InteractionHandler relies on state.
            // We can just add them to an in-memory map in EmbedHelper or LyricsManager.
            String lyricsId = java.util.UUID.randomUUID().toString();
            LyricsCache.put(lyricsId, new LyricsCache.LyricsData(finalQuery, pages, result.source, result.isLive));

            MessageEmbed embed = EmbedHelper.createLyricsEmbed(finalQuery, pages, 1, result.source, result.isLive);
            List<Button> buttons = EmbedHelper.createLyricsComponents(lyricsId, 1, pages.size());
            
            ctx.getEvent().getHook().editOriginalEmbeds(embed).setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(buttons)).queue();
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
        return Commands.slash(getName(), "Get lyrics for a song")
                .addOptions(new OptionData(OptionType.STRING, "query", "The song name (leave blank for current song)", false));
    }
}
