package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.framework.EmbedHelper;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class SearchCommand extends SlashCommand {
    public static final Map<String, List<AudioTrack>> searchCache = new ConcurrentHashMap<>();

    @Override
    public String getName() { return "search"; }

    @Override
    public void execute(CommandContext ctx) {
        String query = ctx.getOption("query").getAsString();
        ctx.deferReply();
        
        ctx.getMusicManager().setNowPlayingChannel(ctx.getChannel().getId());

        com.discord.musicbot.audio.PlayerManager.getInstance().loadItemWithFallback(ctx.getMusicManager(), "ytmsearch:" + query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                track.setUserData("{\"requester\":\"" + ctx.getMember().getId() + "\"}");
                ctx.getMusicManager().getScheduler().queue(track);
                var container = Container.of(
                    TextDisplay.of(EmbedHelper.MSG_SUCCESS + " Added **" + EmbedHelper.escapeMarkdown(track.getInfo().title) + "** to the queue.")
                ).withAccentColor(EmbedHelper.COLOR_MAIN);
                ctx.getEvent().getHook().sendMessageComponents(container).useComponentsV2().queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> tracks = playlist.getTracks();
                if (tracks.isEmpty()) {
                    noMatches();
                    return;
                }
                
                int count = Math.min(10, tracks.size());
                List<AudioTrack> results = tracks.subList(0, count);
                
                String searchId = UUID.randomUUID().toString();
                if (searchCache.size() >= 100) {
                    searchCache.clear();
                }
                searchCache.put(searchId, results);
                
                com.discord.musicbot.audio.PlayerManager.scheduledExecutor.schedule(() -> searchCache.remove(searchId), 5, java.util.concurrent.TimeUnit.MINUTES);

                StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("search_" + searchId + "_" + ctx.getMember().getId())
                    .setPlaceholder("Select a track to play...");
                    
                for (int i = 0; i < results.size(); i++) {
                    AudioTrack track = results.get(i);
                    String label = (i + 1) + ". " + track.getInfo().title;
                    if (label.length() > 100) label = label.substring(0, 97) + "...";
                    String desc = track.getInfo().author + " • " + EmbedHelper.formatDuration(track.getDuration());
                    if (desc.length() > 100) desc = desc.substring(0, 97) + "...";
                    menuBuilder.addOption(label, String.valueOf(i), desc);
                }
                
                var container = Container.of(
                    TextDisplay.of("### Search Results for: " + EmbedHelper.escapeMarkdown(query) + "\nPlease select a track from the dropdown menu below."),
                    ActionRow.of(menuBuilder.build())
                ).withAccentColor(EmbedHelper.COLOR_MAIN);
                
                ctx.getEvent().getHook().sendMessageComponents(container)
                    .useComponentsV2()
                    .queue();
            }

            @Override
            public void noMatches() {
                var container = Container.of(
                    TextDisplay.of(EmbedHelper.MSG_ERROR + " No results found for `" + query + "`.")
                ).withAccentColor(EmbedHelper.COLOR_MAIN);
                ctx.getEvent().getHook().sendMessageComponents(container).useComponentsV2().queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                var container = Container.of(
                    TextDisplay.of(EmbedHelper.MSG_ERROR + " Could not search: " + exception.getMessage())
                ).withAccentColor(EmbedHelper.COLOR_MAIN);
                ctx.getEvent().getHook().sendMessageComponents(container).useComponentsV2().queue();
            }
        });
    }

    @Override public boolean requiresDj() { return false; }
    @Override public boolean requiresVoice() { return true; }
    @Override public boolean requiresBotInVoice() { return false; }
    @Override public boolean requiresSameChannel() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Searches for a song and gives you a menu to select from")
            .addOption(OptionType.STRING, "query", "The search query", true, true);
    }
}
