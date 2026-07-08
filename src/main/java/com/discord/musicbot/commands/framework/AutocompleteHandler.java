package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.data.HistoryManager;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.ArrayList;
import java.util.List;

public class AutocompleteHandler {

    public static void handle(CommandAutoCompleteInteractionEvent event) {
        String command = event.getName();
        String focusedOption = event.getFocusedOption().getName();
        String value = event.getFocusedOption().getValue().trim();

        // Playlist name autocomplete
        if ((command.equals("playlist") || command.equals("mewsic"))
                && (focusedOption.equals("name") || focusedOption.equals("playlist") || focusedOption.equals("old"))) {
            String userId = event.getUser().getId();
            List<com.discord.musicbot.data.model.PlaylistData> playlists = com.discord.musicbot.data.PlaylistManager
                    .getInstance().getPlaylists(userId);
            List<Command.Choice> choices = new ArrayList<>();
            String lowerValue = value.toLowerCase();
            for (com.discord.musicbot.data.model.PlaylistData pl : playlists) {
                if (choices.size() >= 25)
                    break;
                if (lowerValue.isEmpty() || pl.getName().toLowerCase().contains(lowerValue)) {
                    String mewsicTag = pl.isMewsic() ? " (from mewsic)" : "";
                    String label = pl.getName() + mewsicTag + " (" + pl.getTracks().size() + " tracks)";
                    if (label.length() > 100)
                        label = label.substring(0, 97) + "...";
                    choices.add(new Command.Choice(label, pl.getName()));
                }
            }
            event.replyChoices(choices).queue();
            return;
        }

        // Help command autocomplete
        if (command.equals("help") && focusedOption.equals("command")) {
            List<String> commands = List.of(
                    "play", "playinstant", "playnext", "playrandom", "pause", "resume", "skip", "previous", "stop", "disconnect", "autoplay",
                    "247", "crossfade", "join", "leave", "replay", "forward", "rewind", "removedupes", "grab", "search",
                    "queue", "queue show", "queue search", "queue deduplicate", "queue compact", "queue reverse", "queue sort", "queue slice", "queue shufflefrom", "queue swap", "queue export", "queue import", 
                    "nowplaying", "shuffle", "loop", "remove", "insert", "move", "clear", "jump",
                    "playlist", "playlist create", "playlist delete", "playlist rename", "playlist list",
                    "playlist info",
                    "playlist tracks", "playlist add", "playlist addqueue", "playlist remove", "playlist move",
                    "playlist dedupe",
                    "playlist play", "playlist instant", "playlist export", "playlist import",
                    "favorites", "favorites add", "favorites remove", "favorites list", "favorites play",
                    "favorites instant",
                    "mewsic", "mewsic play", "mewsic import", "mewsic export",
                    "volume", "seek", "ping", "time", "lyrics", "stats", "history", "savedqueue", "vote", "djmode",
                    "blacklist", "settings", "filter", "karaoke", "grab", "wrapped");
            List<Command.Choice> choices = new ArrayList<>();
            String lowerValue = value.toLowerCase();
            for (String cmd : commands) {
                if (choices.size() >= 25)
                    break;
                if (lowerValue.isEmpty() || cmd.contains(lowerValue)) {
                    choices.add(new Command.Choice(cmd, cmd));
                }
            }
            event.replyChoices(choices).queue();
            return;
        }

        // Queue and Clear command user filter autocomplete
        if ((command.equals("queue") || command.equals("clear")) && focusedOption.equals("user")) {
            com.discord.musicbot.audio.MusicManager manager = PlayerManager.getInstance()
                    .getMusicManager(event.getGuild().getIdLong());
            if (manager == null) {
                event.replyChoices(new ArrayList<>()).queue();
                return;
            }
            java.util.Map<String, String> userNames = new java.util.HashMap<>();
            java.util.Map<String, Integer> userCounts = new java.util.HashMap<>();
            for (AudioTrack track : manager.getScheduler().getQueue()) {
                Object ud = track.getUserData();
                String id = "";
                String name = "Unknown";
                if (ud instanceof net.dv8tion.jda.api.entities.User u) {
                    id = u.getId();
                    name = u.getEffectiveName();
                } else if (ud instanceof String s) {
                    if (s.contains("\"requester\":\"")) {
                        id = s.split("\"requester\":\"")[1].split("\"")[0];
                    } else {
                        id = s;
                    }
                    net.dv8tion.jda.api.entities.User u = event.getJDA().getUserById(id);
                    name = u != null ? u.getEffectiveName() : id;
                }
                if (!id.isEmpty()) {
                    userNames.put(id, name);
                    userCounts.put(id, userCounts.getOrDefault(id, 0) + 1);
                }
            }
            List<Command.Choice> choices = new ArrayList<>();
            String lowerValue = value.toLowerCase();
            for (java.util.Map.Entry<String, String> entry : userNames.entrySet()) {
                if (choices.size() >= 25)
                    break;
                String label = entry.getValue() + " (" + userCounts.get(entry.getKey()) + " tracks)";
                if (lowerValue.isEmpty() || label.toLowerCase().contains(lowerValue)) {
                    if (label.length() > 100)
                        label = label.substring(0, 97) + "...";
                    choices.add(new Command.Choice(label, entry.getKey()));
                }
            }
            event.replyChoices(choices).queue();
            return;
        }

        // Queue autocomplete (remove, move, jump)
        if ((command.equals("remove") && focusedOption.equals("position")) ||
                (command.equals("move") && (focusedOption.equals("from") || focusedOption.equals("to"))) ||
                (command.equals("jump") && focusedOption.equals("position"))) {
            com.discord.musicbot.audio.MusicManager manager = PlayerManager.getInstance()
                    .getMusicManager(event.getGuild().getIdLong());
            if (manager == null) {
                event.replyChoices(new ArrayList<>()).queue();
                return;
            }
            List<AudioTrack> queue = new ArrayList<>(manager.getScheduler().getQueue());
            List<Command.Choice> choices = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                if (choices.size() >= 25)
                    break;
                String indexStr = String.valueOf(i + 1);
                if (value.isEmpty() || indexStr.startsWith(value)
                        || queue.get(i).getInfo().title.toLowerCase().contains(value.toLowerCase())) {
                    String title = (i + 1) + ". " + queue.get(i).getInfo().title;
                    if (title.length() > 100)
                        title = title.substring(0, 97) + "...";
                    choices.add(new Command.Choice(title, i + 1));
                }
            }
            event.replyChoices(choices).queue();
            return;
        }

        // Favorites and playlist query autocomplete — reuse existing search logic
        if ((command.equals("favorites") || command.equals("playlist")) && focusedOption.equals("query")) {
            // Fall through to existing search logic below
        } else if (command.equals("play") || command.equals("insert") || command.equals("playinstant")) {
            // Fall through to existing search logic below
        } else {
            return;
        }

        List<Command.Choice> choices = new ArrayList<>();

        // Show history results (filtered by query if present)
        String userId = event.getUser().getId();
        List<HistoryManager.HistoryEntry> history = HistoryManager.getInstance().search(userId, value.toLowerCase());
        for (HistoryManager.HistoryEntry h : history) {
            if (choices.size() >= 25)
                break;
            String cleanTitle = com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(h.title);
            String cleanAuthor = com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(h.author);
            String label = "🕛 " + (cleanTitle.length() > 90 ? cleanTitle.substring(0, 90) + "..." : cleanTitle);
            String choiceVal = (h.uri != null && (h.uri.startsWith("http://") || h.uri.startsWith("https://")))
                    ? h.uri
                    : cleanTitle + " " + cleanAuthor;
            if (choiceVal == null || choiceVal.trim().isEmpty()) choiceVal = cleanTitle;
            if (choiceVal.length() > 100) choiceVal = choiceVal.substring(0, 100);
            choices.add(new Command.Choice(label, choiceVal));
        }

        // If user typed something, search Spotify for exact studio song titles and artists
        if (value.length() >= 2 && !value.startsWith("http") && !value.startsWith("scsearch:") && !value.startsWith("ytsearch:") && !value.startsWith("ytmsearch:")) {
            final List<Command.Choice> historyChoices = new ArrayList<>(choices);
            com.discord.musicbot.audio.PlayerManager.getInstance().searchSpotify(value).thenAccept(results -> {
                List<Command.Choice> spotifyChoices = new ArrayList<>();
                for (com.discord.musicbot.audio.PlayerManager.SpotifyMetadata meta : results) {
                    if (spotifyChoices.size() >= 25) break;
                    String label = "🎵 " + meta.title() + " — " + meta.artist();
                    if (label.length() > 95) label = label.substring(0, 95) + "...";
                    String val = (meta.spotifyUrl() != null && (meta.spotifyUrl().contains("spotify.com") || meta.spotifyUrl().startsWith("http")))
                            ? meta.spotifyUrl()
                            : "https://open.spotify.com/search/" + java.net.URLEncoder.encode(meta.title() + " " + meta.artist(), java.nio.charset.StandardCharsets.UTF_8);
                    if (val.length() > 100) val = val.substring(0, 100);
                    spotifyChoices.add(new Command.Choice(label, val));
                }
                for (Command.Choice hc : historyChoices) {
                    if (spotifyChoices.size() >= 25) break;
                    boolean exists = spotifyChoices.stream().anyMatch(c -> c.getName().equals(hc.getName()) || c.getAsString().equals(hc.getAsString()));
                    if (!exists) {
                        spotifyChoices.add(hc);
                    }
                }
                event.replyChoices(spotifyChoices).queue();
            }).exceptionally(ex -> {
                event.replyChoices(historyChoices).queue();
                return null;
            });
            return; // Return early because reply is handled asynchronously
        }

        // Cap at 25 (Discord limit)
        if (choices.size() > 25)
            choices = choices.subList(0, 25);

        event.replyChoices(choices).queue();
    }
}
