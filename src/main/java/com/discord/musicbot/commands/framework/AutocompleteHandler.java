package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.data.HistoryManager;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AutocompleteHandler {

    public static void handle(CommandAutoCompleteInteractionEvent event) {
        String command = event.getName();
        String value = event.getFocusedOption().getValue().trim();

        if (command.equals("play") || command.equals("insert") || command.equals("playinstant")) {
            List<Command.Choice> choices = new ArrayList<>();

            // Show history results (filtered by query if present)
            List<HistoryManager.HistoryEntry> history = HistoryManager.getInstance().search(value.toLowerCase());
            for (HistoryManager.HistoryEntry h : history) {
                if (choices.size() >= 5) break;
                String label = "🕛 " + (h.title.length() > 90 ? h.title.substring(0, 90) + "..." : h.title);
                choices.add(new Command.Choice(label, h.uri));
            }

            // If user typed something, search YouTube for live results
            if (value.length() >= 2 && !value.startsWith("http")) {
                try {
                    final int historyCount = choices.size();
                    CompletableFuture<List<Command.Choice>> searchFuture = new CompletableFuture<>();
                    PlayerManager.getInstance().getPlayerManager().loadItem("ytsearch:" + value, new AudioLoadResultHandler() {
                        @Override
                        public void trackLoaded(AudioTrack track) {
                            List<Command.Choice> results = new ArrayList<>();
                            String title = track.getInfo().title;
                            if (title.length() > 90) title = title.substring(0, 90) + "...";
                            results.add(new Command.Choice("🔎 " + title, track.getInfo().uri));
                            searchFuture.complete(results);
                        }

                        @Override
                        public void playlistLoaded(AudioPlaylist playlist) {
                            List<Command.Choice> results = new ArrayList<>();
                            for (AudioTrack track : playlist.getTracks()) {
                                if (results.size() >= (10 - historyCount)) break;
                                String title = track.getInfo().title;
                                if (title.length() > 90) title = title.substring(0, 90) + "...";
                                results.add(new Command.Choice("🔎 " + title, track.getInfo().uri));
                            }
                            searchFuture.complete(results);
                        }

                        @Override
                        public void noMatches() {
                            searchFuture.complete(new ArrayList<>());
                        }

                        @Override
                        public void loadFailed(FriendlyException exception) {
                            searchFuture.complete(new ArrayList<>());
                        }
                    });

                    // Wait up to 2.5 seconds for YouTube results (Discord autocomplete timeout is 3s)
                    List<Command.Choice> searchResults = searchFuture.get(2500, TimeUnit.MILLISECONDS);
                    choices.addAll(searchResults);
                } catch (Exception e) {
                    // Timeout or error — just show history results
                }
            }

            // Cap at 25 (Discord limit)
            if (choices.size() > 25) choices = choices.subList(0, 25);

            event.replyChoices(choices).queue();
        }
    }
}
