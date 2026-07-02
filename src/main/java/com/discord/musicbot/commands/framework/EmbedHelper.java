package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.data.model.PlaylistData;
import com.discord.musicbot.data.model.PlaylistTrack;
import com.discord.musicbot.data.model.GuildSettings;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.components.actionrow.ActionRow;

import net.dv8tion.jda.api.components.buttons.Button;

import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.ArrayList;
import java.util.List;

public class EmbedHelper {

    // --- Message Emojis (Text Replies) ---
    public static final String MSG_SUCCESS = com.discord.musicbot.config.EmojiConfig.getInstance().success;
    public static final String MSG_ERROR = com.discord.musicbot.config.EmojiConfig.getInstance().error;
    public static final String MSG_PAUSE = com.discord.musicbot.config.EmojiConfig.getInstance().pause;
    public static final String MSG_PLAY = com.discord.musicbot.config.EmojiConfig.getInstance().play;
    public static final String MSG_SKIP = com.discord.musicbot.config.EmojiConfig.getInstance().skip;
    public static final String MSG_STOP = com.discord.musicbot.config.EmojiConfig.getInstance().stop;

    public static final String MSG_PREVIOUS = com.discord.musicbot.config.EmojiConfig.getInstance().previous;
    public static final String MSG_VOLUME = com.discord.musicbot.config.EmojiConfig.getInstance().volume;
    public static final String MSG_TIME = com.discord.musicbot.config.EmojiConfig.getInstance().time;
    public static final String MSG_SHUFFLE = com.discord.musicbot.config.EmojiConfig.getInstance().shuffle;
    public static final String MSG_REPEAT = com.discord.musicbot.config.EmojiConfig.getInstance().repeat;
    public static final String BOT_LOGO_URL = "https://cdn.discordapp.com/attachments/1438305127898550342/1519635008686391435/mewsic.png?ex=6a3e45e3&is=6a3cf463&hm=3b6da7f8e57917e7acd32b900182cb31cded451961c3c114c08e9ceadcb5e185&";

    public static final int COLOR_MAIN;

    static {
        int color = 0x2f3136;
        try {
            io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.load();
            String envColor = dotenv.get("EMBED_COLOR");
            if (envColor != null && !envColor.isEmpty()) {
                if (envColor.startsWith("#")) {
                    envColor = envColor.substring(1);
                } else if (envColor.startsWith("0x")) {
                    envColor = envColor.substring(2);
                }
                color = Integer.parseInt(envColor, 16);
            }
        } catch (Exception e) {
            // Ignore and use default
        }
        COLOR_MAIN = color;
    }

    public static String escapeMarkdown(String text) {
        return text.replaceAll("([*_`~>|])", "\\\\$1");
    }

    public static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        if (hours > 0)
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }

    public static String formatTime(long duration) {
        long seconds = duration / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%02d:%02d", minutes, secs);
        }
    }

    public static String createProgressBar(long position, long duration) {
        int barLength = 15;
        int filled = (int) ((position * barLength) / Math.max(1, duration));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < barLength; i++) {
            if (i == filled)
                sb.append("◉"); // Custom circle
            else
                sb.append("▬");
        }
        return sb.toString();
    }

    public static Container createQueueContainer(MusicManager manager, int page, String filterUserId) {
        List<AudioTrack> queue = new ArrayList<>(manager.getScheduler().getQueue());
        if (filterUserId != null) {
            queue.removeIf(t -> {
                Object ud = t.getUserData();
                String id = "";
                if (ud instanceof net.dv8tion.jda.api.entities.User u) id = u.getId();
                else if (ud instanceof String s) {
                    if (s.contains("\"requester\":\"")) id = s.split("\"requester\":\"")[1].split("\"")[0];
                    else id = s;
                }
                return !id.equals(filterUserId);
            });
        }

        int queueSize = queue.size();
        if (queueSize == 0) {
            return Container.of(TextDisplay.of("No tracks in the queue.")).withAccentColor(COLOR_MAIN);
        }

        long totalDuration = queue.stream().mapToLong(AudioTrack::getDuration).sum();
        int maxPages = Math.max(1, (int) Math.ceil(queueSize / 10.0));

        String loopMode = manager.getScheduler().getLoopMode().name();
        loopMode = loopMode.substring(0, 1).toUpperCase() + loopMode.substring(1).toLowerCase();

        StringBuilder footer = new StringBuilder();
        footer.append("Page ").append(page).append("/").append(maxPages);
        footer.append(" | ").append(queueSize).append(" track").append(queueSize != 1 ? "s" : "");
        footer.append(" | ").append(formatDuration(totalDuration)).append(" total");

        if (!loopMode.equalsIgnoreCase("Off")) {
            footer.append(" | Loop: ").append(loopMode);
        }
        if (manager.getScheduler().isPaused())
            footer.append(" | Paused");
        if (manager.getScheduler().getAutoplay() && manager.getScheduler().isRandomPlay())
            footer.append(" | Autoplay + Random");
        else if (manager.getScheduler().getAutoplay())
            footer.append(" | Autoplay");
        else if (manager.getScheduler().isRandomPlay())
            footer.append(" | Random");

        StringBuilder content = new StringBuilder();
        String title = filterUserId != null ? "Queue (Filtered)" : "Queue";
        content.append("### ").append(title).append("\n\n");

        int start = (page - 1) * 10;
        int end = Math.min(start + 10, queueSize);

        for (int i = start; i < end; i++) {
            AudioTrack track = queue.get(i);
            String trackTitle = track.getInfo().title;
            if (trackTitle.length() > 30) {
                trackTitle = trackTitle.substring(0, 27) + "...";
            }
            
            String url = track.getInfo().uri;
            if (url == null) {
                url = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(trackTitle, java.nio.charset.StandardCharsets.UTF_8);
            } else if (url.startsWith("ytsearch:")) {
                url = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(url.substring(9), java.nio.charset.StandardCharsets.UTF_8);
            }
            trackTitle = escapeMarkdown(trackTitle);
            
            String requester = "";
            String requesterId = null;
            Object ud = track.getUserData();
            if (ud instanceof net.dv8tion.jda.api.entities.User u) {
                requesterId = u.getId();
            } else if (ud instanceof String s) {
                if (s.contains("\"requester\":\"")) {
                    requesterId = s.split("\"requester\":\"")[1].split("\"")[0];
                } else if (s.matches("\\d+")) {
                    requesterId = s;
                }
            }
            
            if (requesterId != null) {
                requester = " - <@" + requesterId + ">";
            }
            
            content.append(String.format("%d. [**%s**](%s)%s\n", i + 1, trackTitle, url, requester));
        }

        content.append("\n-# ").append(footer.toString());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(content.toString()));

        String prefix = filterUserId == null ? "queue" : "queue_" + filterUserId;
        List<Button> buttons = createPaginationButtons(prefix, page, maxPages);
        children.add(ActionRow.of(buttons));

        return Container.of(children).withAccentColor(COLOR_MAIN);
    }

    public static List<Button> createPaginationButtons(String idPrefix, int page, int maxPages) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.secondary(idPrefix + "_first_" + page, "<<").withDisabled(page <= 1));
        buttons.add(Button.secondary(idPrefix + "_prev_" + page, "<").withDisabled(page <= 1));
        buttons.add(Button.secondary(idPrefix + "_next_" + page, ">").withDisabled(page >= maxPages));
        buttons.add(Button.secondary(idPrefix + "_last_" + page, ">>").withDisabled(page >= maxPages));
        return buttons;
    }

    public static Container createLyricsContainer(String lyricsId, String query, List<String> pages, int pageNum, String source, boolean isLive) {
        StringBuilder content = new StringBuilder();
        content.append("### Lyrics: ").append(query).append("\n\n");
        content.append(pages.get(pageNum - 1));
        
        String footer = String.format("Page %d/%d | Source: %s", pageNum, pages.size(), source);
        content.append("\n\n-# ").append(footer);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(content.toString()));
        children.add(ActionRow.of(createLyricsComponents(lyricsId, pageNum, pages.size())));

        return Container.of(children).withAccentColor(COLOR_MAIN);
    }

    public static Container createVoteContainer(String type, int currentYes, int required) {
        StringBuilder content = new StringBuilder();
        content.append("### Vote to ").append(type).append("\n\n");
        content.append(String.format("Votes: **%d** / **%d** required", currentYes, required));
        content.append("\n\n-# Vote passes when requirements are met.");

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(content.toString()));
        children.add(ActionRow.of(
                Button.success("vote_yes", "Yes"),
                Button.danger("vote_no", "No")
        ));

        return Container.of(children).withAccentColor(COLOR_MAIN);
    }

    public static List<Button> createLyricsComponents(String lyricsId, int page, int maxPages) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.secondary("lyrics_prev_" + lyricsId + "_" + page, "<").withDisabled(page <= 1));
        buttons.add(Button.secondary("lyrics_next_" + lyricsId + "_" + page, ">").withDisabled(page >= maxPages));
        return buttons;
    }


    public static Container createHelpContainer(String category, String prefix, JDA jda) {
        StringBuilder description = new StringBuilder();

        if (category.equals("home")) {
            description.append("### ").append(jda.getSelfUser().getName()).append(" Music\n\n");
            description.append("A simple music bot supporting **Spotify**, **YouTube**, and **SoundCloud**.\n\n");
            description.append("**").append(com.discord.musicbot.config.EmojiConfig.getInstance().mewsic).append(" Official Mewsic Collaboration**\n");
            description.append("This bot is integrated with the offline Mewsic app. You can play your Mewsic playlists seamlessly using `/mewsic play` and `/mewsic import`.\n\n");
            description.append("Use the dropdown below to explore the available commands.");
        } else if (category.equals("playback")) {
            description.append("### Playback Commands\n\n");
            description.append(String.format("**%splay <song/url>** - Play a song or add it to the queue.\n\n", prefix));
            description.append(String.format("**%splayinstant <song/url>** - Play a song immediately, skipping the current track.\n\n", prefix));
            description.append(String.format("**%splaynext <song/url>** - Add a song to the top of the queue.\n\n", prefix));
            description.append(String.format("**%splayrandom [source]** - Play a random track from favorites or history.\n\n", prefix));
            description.append(String.format("**%ssearch <query>** - Search for a song and pick from a list.\n\n", prefix));
            description.append(String.format("**%spause** - Pause the currently playing track.\n\n", prefix));
            description.append(String.format("**%sresume** - Resume the paused track.\n\n", prefix));
            description.append(String.format("**%sskip** - Skip to the next track in the queue.\n\n", prefix));
            description.append(String.format("**%sprevious** - Play the previously played track from history.\n\n", prefix));
            description.append(String.format("**%sreplay** - Replay the currently playing track from the beginning.\n\n", prefix));
            description.append(String.format("**%sforward <seconds>** - Fast-forward the current track.\n\n", prefix));
            description.append(String.format("**%srewind <seconds>** - Rewind the current track.\n\n", prefix));
            description.append(String.format("**%sstop** - Stop playback and clear the entire queue.\n\n", prefix));
            description.append(String.format("**%sjoin** - Make the bot join your voice channel.\n\n", prefix));
            description.append(String.format("**%sleave** - Disconnect the bot from the voice channel.\n\n", prefix));
            description.append(String.format("**%sdisconnect** - Disconnect the bot from the voice channel.\n\n", prefix));
            description.append(String.format("**%sautoplay** - Toggle automatic recommendations.\n\n", prefix));
            description.append(String.format("**%skaraoke** - Toggle Live Karaoke mode.\n\n", prefix));
            description.append(String.format("**%sfilter <filter>** - Apply audio filters (bassboost, 8d, vaporwave, etc).\n\n", prefix));
            description.append(String.format("**%scrossfade [duration]** - Enable PCM crossfading between tracks.\n\n", prefix));
            description.append(String.format("**%s247 [lock]** - Keep the bot in the voice channel 24/7.\n", prefix));
        } else if (category.equals("queue")) {
            description.append("### Queue Commands\n\n");
            description.append(String.format("**%squeue show [page] [user]** - View all tracks in the current queue.\n\n", prefix));
            description.append(String.format("**%squeue search <query>** - Search for a specific track within the queue.\n\n", prefix));
            description.append(String.format("**%squeue deduplicate** - Scan and remove any duplicate entries.\n\n", prefix));
            description.append(String.format("**%squeue compact** - Group identical tracks together sequentially.\n\n", prefix));
            description.append(String.format("**%squeue reverse** - Invert the current play order of all queued tracks.\n\n", prefix));
            description.append(String.format("**%squeue sort <criteria>** - Organize queue by title or author.\n\n", prefix));
            description.append(String.format("**%squeue slice <start> <end>** - Remove a contiguous range of tracks.\n\n", prefix));
            description.append(String.format("**%squeue shufflefrom <position>** - Shuffle tracks after a specific point.\n\n", prefix));
            description.append(String.format("**%squeue swap <pos1> <pos2>** - Exchange the positions of two tracks.\n\n", prefix));
            description.append(String.format("**%squeue export** - Export the current queue into a downloadable JSON file.\n\n", prefix));
            description.append(String.format("**%squeue import <file>** - Import a JSON queue file to append to the active queue.\n\n", prefix));
            description.append(String.format("**%snowplaying** - View details about the currently playing track.\n\n", prefix));
            description.append(String.format("**%sshuffle** - Randomize the order of tracks in the queue.\n\n", prefix));
            description.append(String.format("**%sloop <mode>** - Set repeat mode: off, track, or queue.\n\n", prefix));
            description.append(String.format("**%sremove <number>** - Remove a specific track from the queue.\n\n", prefix));
            description.append(String.format("**%sinsert <song/url> <position>** - Insert a song at a specific position.\n\n", prefix));
            description.append(String.format("**%smove <from> <to>** - Move a track to a different position.\n\n", prefix));
            description.append(String.format("**%sclear** - Remove all tracks from the queue.\n\n", prefix));
            description.append(String.format("**%sjump <number>** - Skip directly to a specific track in the queue.\n\n", prefix));
            description.append(String.format("**%sremovedupes** - Remove duplicate tracks from the queue.\n", prefix));
        } else if (category.equals("playlists")) {
            description.append("### Playlist & Favorites Commands\n\n");
            description.append(String.format("**%splaylist create <name>** - Create a new playlist.\n\n", prefix));
            description.append(String.format("**%splaylist list** - View your playlists.\n\n", prefix));
            description.append(String.format("**%splaylist play <name>** - Play a playlist.\n\n", prefix));
            description.append(String.format("**%splaylist add <playlist> [query]** - Add tracks to a playlist.\n\n", prefix));
            description.append(String.format("**%splaylist tracks <name>** - View tracks in a playlist.\n\n", prefix));
            description.append(String.format("**%sfavorites add [query]** - Add to your favorites.\n\n", prefix));
            description.append(String.format("**%sfavorites list** - View your favorites.\n\n", prefix));
            description.append(String.format("**%sfavorites play** - Play your favorites.\n", prefix));
        } else if (category.equals("mewsic")) {
            description.append("### Mewsic Commands\n\n");
            description.append(String.format("**%smewsic import <file>** - Import a Mewsic playlist JSON file.\n\n", prefix));
            description.append(String.format("**%smewsic export <playlist>** - Export a Melora playlist to Mewsic JSON format.\n", prefix));
        } else if (category.equals("settings")) {
            description.append("### Settings & Utility Commands\n\n");
            description.append(String.format("**%svolume <1-200>** - Adjust the playback volume.\n\n", prefix));
            description.append(String.format("**%ssettings** - (Admin) Open the server configuration dashboard.\n\n", prefix));
            description.append(String.format("**%sseek <time>** - Jump to a specific timestamp in the track.\n\n", prefix));
            description.append(String.format("**%stime** - View the current playback time and progress bar.\n\n", prefix));
            description.append(String.format("**%slyrics [query]** - Get lyrics for the currently playing track or a search query.\n\n", prefix));
            description.append(String.format("**%sstats** - View your personal listening statistics.\n\n", prefix));
            description.append(String.format("**%shistory** - View your listening history.\n\n", prefix));
            description.append(String.format("**%ssavedqueue** - Manage your saved queues.\n\n", prefix));
            description.append(String.format("**%swrapped** - View your %s Wrapped stats.\n\n", prefix, jda.getSelfUser().getName()));
            description.append(String.format("**%svote** - Vote to skip, clear, or disconnect.\n\n", prefix));
            description.append(String.format("**%sdjmode** - (Admin/DJ) Configure DJ Mode and temporary access.\n\n", prefix));
            description.append(String.format("**%sblacklist** - (Admin) Manage guild music blacklist.\n\n", prefix));
            description.append(String.format("**%sgrab** - Send the currently playing track to your Direct Messages.\n\n", prefix));
            description.append(String.format("**%sping** - Check bot latency.\n", prefix));
        } else {
            description.append("### Error\n\nCommand not found. Use `" + prefix + "help` to see all commands.");
        }

        return Container.of(
                TextDisplay.of(description.toString()),
                createHelpMenu()
        ).withAccentColor(COLOR_MAIN);
    }

    public static Container createCommandHelpContainer(String commandName, String prefix, JDA jda) {
        String description;
        switch (commandName) {
            case "play": description = "Play a song or add it to the queue.\n\n**Usage:** `" + prefix + "play <song/url>`"; break;
            case "playinstant": description = "Play a song immediately, skipping the current track.\n\n**Usage:** `" + prefix + "playinstant <song/url>`"; break;
            case "playnext": description = "Add a track to the top of the queue (plays next).\n\n**Usage:** `" + prefix + "playnext <query>`"; break;
            case "playrandom": description = "Play a random track from your favorites or the bot's global history.\n\n**Usage:** `" + prefix + "playrandom [source]`"; break;
            case "search": description = "Searches for a song and gives you a menu to select from.\n\n**Usage:** `" + prefix + "search <query>`"; break;
            case "pause": description = "Pause the currently playing track.\n\n**Usage:** `" + prefix + "pause`"; break;
            case "resume": description = "Resume the paused track.\n\n**Usage:** `" + prefix + "resume`"; break;
            case "skip": description = "Skip to the next track in the queue.\n\n**Usage:** `" + prefix + "skip`"; break;
            case "previous": description = "Play the previously played track from history.\n\n**Usage:** `" + prefix + "previous`"; break;
            case "replay": description = "Replay the currently playing track from the beginning.\n\n**Usage:** `" + prefix + "replay`"; break;
            case "forward": description = "Fast-forwards by a specific amount of seconds.\n\n**Usage:** `" + prefix + "forward <seconds>`"; break;
            case "rewind": description = "Rewinds by a specific amount of seconds.\n\n**Usage:** `" + prefix + "rewind <seconds>`"; break;
            case "stop": description = "Stop playback and clear the entire queue.\n\n**Usage:** `" + prefix + "stop`"; break;
            case "join": description = "Make the bot join your voice channel.\n\n**Usage:** `" + prefix + "join`"; break;
            case "leave": description = "Disconnect the bot from the voice channel.\n\n**Usage:** `" + prefix + "leave`"; break;
            case "disconnect": description = "Disconnect the bot from the voice channel.\n\n**Usage:** `" + prefix + "disconnect`"; break;
            case "autoplay": description = "Toggle automatic recommendations after the queue finishes.\n\n**Usage:** `" + prefix + "autoplay`"; break;
            case "crossfade": description = "Enable and configure PCM crossfading between tracks.\n\n**Usage:** `" + prefix + "crossfade [duration]`"; break;
            case "247": description = "Keep the bot in the voice channel 24/7 even if no music is playing. Can optionally lock the bot to restrict dismissal.\n\n**Usage:** `" + prefix + "247 [lock]`"; break;
            case "queue":
            case "queue show": description = "View all tracks in the current queue. You can optionally filter to only see tracks queued by a specific user.\n\n**Usage:** `" + prefix + "queue show [page] [user]`"; break;
            case "queue search": description = "Search for a specific track currently within the queue.\n\n**Usage:** `" + prefix + "queue search <query>`"; break;
            case "queue deduplicate": description = "Scan the queue and remove any duplicate entries automatically.\n\n**Usage:** `" + prefix + "queue deduplicate`"; break;
            case "queue compact": description = "Group identical tracks together in the queue sequentially.\n\n**Usage:** `" + prefix + "queue compact`"; break;
            case "queue reverse": description = "Invert the current play order of all queued tracks.\n\n**Usage:** `" + prefix + "queue reverse`"; break;
            case "queue sort": description = "Organize the queue alphabetically by track title or track author.\n\n**Usage:** `" + prefix + "queue sort <criteria>`"; break;
            case "queue slice": description = "Remove a contiguous range of tracks from the queue.\n\n**Usage:** `" + prefix + "queue slice <start> <end>`"; break;
            case "queue shufflefrom": description = "Shuffle only the tracks that occur after a specific point in the queue.\n\n**Usage:** `" + prefix + "queue shufflefrom <position>`"; break;
            case "queue swap": description = "Exchange the positions of two specific tracks in the queue.\n\n**Usage:** `" + prefix + "queue swap <pos1> <pos2>`"; break;
            case "queue export": description = "Export the current queue into a downloadable JSON file.\n\n**Usage:** `" + prefix + "queue export`"; break;
            case "queue import": description = "Import an existing JSON queue file to append to the active queue.\n\n**Usage:** `" + prefix + "queue import <file>`"; break;
            case "nowplaying": description = "View details about the currently playing track.\n\n**Usage:** `" + prefix + "nowplaying`"; break;
            case "shuffle": description = "Randomize the order of tracks in the queue.\n\n**Usage:** `" + prefix + "shuffle`"; break;
            case "loop": description = "Set repeat mode: off, track, or queue.\n\n**Usage:** `" + prefix + "loop <mode>`"; break;
            case "remove": description = "Remove a specific track from the queue by its number.\n\n**Usage:** `" + prefix + "remove <number>`"; break;
            case "removedupes": description = "Remove all duplicate tracks from the queue.\n\n**Usage:** `" + prefix + "removedupes`"; break;
            case "insert": description = "Insert a song at a specific position in the queue.\n\n**Usage:** `" + prefix + "insert <song/url> <position>`"; break;
            case "move": description = "Move a track from one position to another.\n\n**Usage:** `" + prefix + "move <from> <to>`"; break;
            case "clear": description = "Remove all tracks from the queue but keep playing the current track. You can optionally filter to only clear tracks queued by a specific user.\n\n**Usage:** `" + prefix + "clear [user]`"; break;
            case "jump": description = "Skip directly to a specific track in the queue.\n\n**Usage:** `" + prefix + "jump <number>`"; break;
            case "playlist": description = "Manage custom playlists.\n\n**Usage:** `" + prefix + "playlist <subcommand>`"; break;
            case "playlist add": description = "Add a track to a playlist. If the playlist doesn't exist, it will be created automatically.\n\n**Usage:** `" + prefix + "playlist add <playlist> [query]`"; break;
            case "playlist create": description = "Create a new playlist.\n\n**Usage:** `" + prefix + "playlist create <name>`"; break;
            case "playlist delete": description = "Delete a playlist.\n\n**Usage:** `" + prefix + "playlist delete <name>`"; break;
            case "playlist rename": description = "Rename a playlist.\n\n**Usage:** `" + prefix + "playlist rename <old> <new>`"; break;
            case "playlist list": description = "View your playlists.\n\n**Usage:** `" + prefix + "playlist list [page]`"; break;
            case "playlist info": description = "View playlist info.\n\n**Usage:** `" + prefix + "playlist info <name>`"; break;
            case "playlist tracks": description = "View tracks in a playlist.\n\n**Usage:** `" + prefix + "playlist tracks <name> [page]`"; break;
            case "playlist addqueue": description = "Add current queue to a playlist.\n\n**Usage:** `" + prefix + "playlist addqueue <playlist>`"; break;
            case "playlist remove": description = "Remove a track from a playlist.\n\n**Usage:** `" + prefix + "playlist remove <playlist> <index>`"; break;
            case "playlist move": description = "Move a track in a playlist.\n\n**Usage:** `" + prefix + "playlist move <playlist> <from> <to>`"; break;
            case "playlist dedupe": description = "Remove duplicate tracks from a playlist.\n\n**Usage:** `" + prefix + "playlist dedupe <name>`"; break;
            case "playlist play": description = "Play a playlist.\n\n**Usage:** `" + prefix + "playlist play <playlist>`"; break;
            case "playlist instant": description = "Force-play a playlist immediately.\n\n**Usage:** `" + prefix + "playlist instant <playlist>`"; break;
            case "playlist export": description = "Export a playlist as JSON.\n\n**Usage:** `" + prefix + "playlist export <playlist>`"; break;
            case "playlist import": description = "Import a playlist from JSON.\n\n**Usage:** `" + prefix + "playlist import <file>`"; break;
            case "favorites": description = "Manage your favorite tracks.\n\n**Usage:** `" + prefix + "favorites <subcommand>`"; break;
            case "favorites add": description = "Add a track to your favorites.\n\n**Usage:** `" + prefix + "favorites add [query]`"; break;
            case "favorites remove": description = "Remove a track from your favorites.\n\n**Usage:** `" + prefix + "favorites remove <index>`"; break;
            case "favorites list": description = "View your favorites.\n\n**Usage:** `" + prefix + "favorites list [page]`"; break;
            case "favorites play": description = "Play your favorites.\n\n**Usage:** `" + prefix + "favorites play`"; break;
            case "favorites instant": description = "Force-play your favorites immediately.\n\n**Usage:** `" + prefix + "favorites instant`"; break;
            case "mewsic": description = "Integration with the offline Mewsic app.\n\n**Usage:** `" + prefix + "mewsic <subcommand>`"; break;
            case "mewsic import": description = "Import a Mewsic playlist JSON file.\n\n**Usage:** `" + prefix + "mewsic import <file>`"; break;
            case "mewsic export": description = "Export the current queue or a saved playlist to Mewsic JSON format.\n\n**Usage:** `" + prefix + "mewsic export <source> [playlist]`"; break;
            case "volume": description = "Adjust the playback volume (1-200).\n\n**Usage:** `" + prefix + "volume <1-200>`"; break;
            case "ping": description = "Check bot latency.\n\n**Usage:** `" + prefix + "ping`"; break;
            case "time": description = "View the current playback time and progress bar.\n\n**Usage:** `" + prefix + "time`"; break;
            case "lyrics": description = "Get lyrics for the currently playing track or a search query.\n\n**Usage:** `" + prefix + "lyrics [query]`"; break;
            case "stats": description = "View your personal listening statistics.\n\n**Usage:** `" + prefix + "stats`"; break;
            case "history": description = "View or clear your listening history.\n\n**Usage:** `" + prefix + "history <subcommand>`"; break;
            case "savedqueue": description = "Manage your saved queues.\n\n**Usage:** `" + prefix + "savedqueue <subcommand>`"; break;
            case "wrapped": description = "View your personalized " + jda.getSelfUser().getName() + " Wrapped stats.\n\n**Usage:** `" + prefix + "wrapped`"; break;
            case "karaoke": description = "Toggle Live Karaoke mode for the current session. Synced lyrics will appear in the Now Playing embed if available.\n\n**Usage:** `" + prefix + "karaoke`"; break;
            case "vote": description = "Start a vote.\n\n**Usage:** `" + prefix + "vote <skip/clear/disconnect>`"; break;
            case "djmode": description = "Configure DJ Mode settings and grant temporary access.\n\n**Usage:** `" + prefix + "djmode <on/off/role/grant/revoke>`\n\n- `on/off`: Enable or disable DJ mode (Requires Manage Server)\n- `role <role>`: Set the DJ role (Requires Manage Server)\n- `grant <user>`: Give a user temporary DJ access for the session\n- `revoke <user>`: Revoke temporary access"; break;
            case "blacklist": description = "Manage guild music blacklist.\n\n**Usage:** `" + prefix + "blacklist <subcommand>`"; break;
            case "grab": description = "Send the currently playing track to your Direct Messages.\n\n**Usage:** `" + prefix + "grab`"; break;
            case "filter": description = "Apply an audio filter to the playback.\n\n**Filters:** `bassboost`, `earrape`, `pop`, `rock`, `electronic`, `nightcore`, `vaporwave`, `8d`, `tremolo`, `vibrato`, `distortion`, `muffled`, `vocal_remove`, `mono`, `clear`\n\n**Usage:** `" + prefix + "filter <filter>`"; break;
            case "settings": description = "Open the server configuration dashboard to toggle bot behaviors (Admin only).\n\n**Usage:** `" + prefix + "settings`"; break;
            default: description = "Command not found. Use `" + prefix + "help` to see all commands."; break;
        }

        return Container.of(
                TextDisplay.of("### Command: " + commandName + "\n\n" + description)
        ).withAccentColor(COLOR_MAIN);
    }

    public static ActionRow createHelpMenu() {
        StringSelectMenu menu = StringSelectMenu.create("help_menu")
                .setPlaceholder("Select a category")
                .addOption("Home", "home", "Bot introduction")
                .addOption("Playback", "playback", "Playback control commands")
                .addOption("Queue", "queue", "Queue management commands")
                .addOption("Playlists", "playlists", "Playlist & favorites commands")
                .addOption("Mewsic", "mewsic", "Offline app integration")
                .addOption("Settings", "settings", "Player settings commands")
                .build();

        return ActionRow.of(menu);
    }
    public static List<ActionRow> createNowPlayingComponents(MusicManager manager) {
        List<Button> row1 = new ArrayList<>();
        List<Button> row2 = new ArrayList<>();
        boolean paused = manager.getScheduler().isPaused();

        // Row 1: Loop, Previous, Play/Pause, Skip, Shuffle
        row1.add(Button.secondary("np_loop", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnLoop)));
        row1.add(Button.secondary("np_previous", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnPrevious)));
        row1.add(Button.secondary("np_pause", Emoji.fromFormatted(paused ? com.discord.musicbot.config.EmojiConfig.getInstance().btnResume : com.discord.musicbot.config.EmojiConfig.getInstance().btnPause)));
        row1.add(Button.secondary("np_skip", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnSkip)));
        row1.add(Button.secondary("np_shuffle", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnShuffle)));

        // Row 2: Queue, Volume Down, Stop, Volume Up, Favorite
        row2.add(Button.secondary("np_queue", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnQueue)));
        row2.add(Button.secondary("np_voldown", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnVolumeDown)));
        row2.add(Button.danger("np_stop", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnStop)));
        row2.add(Button.secondary("np_volup", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnVolumeUp)));
        row2.add(Button.secondary("np_fav", Emoji.fromFormatted(com.discord.musicbot.config.EmojiConfig.getInstance().btnFavorite)));

        return List.of(ActionRow.of(row1), ActionRow.of(row2));
    }

    // ======================== PLAYLIST / FAVORITES / HISTORY EMBEDS ========================

    public static Container createHistoryContainer(java.util.List<com.discord.musicbot.data.HistoryManager.HistoryEntry> history, int page) {
        int totalTracks = history.size();
        int maxPages = Math.max(1, (int) Math.ceil(totalTracks / 10.0));
        page = Math.max(1, Math.min(page, maxPages));

        StringBuilder content = new StringBuilder();
        content.append("### Your Listening History\n\n");

        if (totalTracks > 0) {
            int start = (page - 1) * 10;
            int end = Math.min(start + 10, totalTracks);
            for (int i = start; i < end; i++) {
                com.discord.musicbot.data.HistoryManager.HistoryEntry t = history.get(i);
                String title = t.title;
                if (title.length() > 50) title = title.substring(0, 47) + "...";
                
                String url = t.uri;
                if (url != null && url.startsWith("ytsearch:")) {
                    url = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(url.substring(9), java.nio.charset.StandardCharsets.UTF_8);
                }
                
                content.append(String.format("`%d.` [**%s**](%s)\n", i + 1, escapeMarkdown(title), url));
            }
        } else {
            content.append("No listening history.");
        }

        String footer = "Page " + page + "/" + maxPages + " | " + totalTracks + " track" + (totalTracks != 1 ? "s" : "");
        content.append("\n-# ").append(footer);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(content.toString()));
        children.add(ActionRow.of(createPaginationButtons("history", page, maxPages)));

        return Container.of(children).withAccentColor(COLOR_MAIN);
    }

    public static Container createPlaylistTracksContainer(PlaylistData playlist, int page, String paginationPrefix) {
        List<PlaylistTrack> tracks = playlist.getTracks();
        int totalTracks = tracks.size();
        int maxPages = Math.max(1, (int) Math.ceil(totalTracks / 10.0));
        page = Math.max(1, Math.min(page, maxPages));

        StringBuilder content = new StringBuilder();
        content.append("### ").append(playlist.isFavorites() ? "Favorites" : playlist.getName()).append("\n\n");

        if (totalTracks > 0) {
            content.append("```md\n");
            int start = (page - 1) * 10;
            int end = Math.min(start + 10, totalTracks);
            for (int i = start; i < end; i++) {
                PlaylistTrack t = tracks.get(i);
                String title = t.getTitle();
                if (title.length() > 50) title = title.substring(0, 47) + "...";
                content.append(String.format("%d. %s [%s]\n", i + 1, title, formatDuration(t.getDuration())));
            }
            content.append("```");
        } else {
            content.append("No tracks");
        }

        StringBuilder footer = new StringBuilder();
        footer.append("Page ").append(page).append("/").append(maxPages);
        if (totalTracks > 0) {
            footer.append(" | ").append(totalTracks).append(" track").append(totalTracks != 1 ? "s" : "");
            footer.append(" | ").append(formatDuration(playlist.getTotalDuration())).append(" total");
        }
        content.append("\n-# ").append(footer);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(content.toString()));
        children.add(ActionRow.of(createPaginationButtons(paginationPrefix, page, maxPages)));

        return Container.of(children).withAccentColor(COLOR_MAIN);
    }

    public static Container createPlaylistListContainer(List<PlaylistData> playlists, int page, String paginationPrefix) {
        int total = playlists.size();
        int maxPages = Math.max(1, (int) Math.ceil(total / 10.0));
        page = Math.max(1, Math.min(page, maxPages));

        StringBuilder content = new StringBuilder();
        content.append("### Your Playlists\n\n");

        if (total > 0) {
            content.append("```md\n");
            int start = (page - 1) * 10;
            int end = Math.min(start + 10, total);
            for (int i = start; i < end; i++) {
                PlaylistData p = playlists.get(i);
                String name = p.getName();
                if (name.length() > 40) name = name.substring(0, 37) + "...";
                content.append(String.format("%d. %s (%d tracks)\n", i + 1, name, p.getTracks().size()));
            }
            content.append("```");
        } else {
            content.append("No playlists. Use `/playlist create` to get started!");
        }

        String footer = "Page " + page + "/" + maxPages + " | " + total + " playlist" + (total != 1 ? "s" : "");
        content.append("\n-# ").append(footer);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(content.toString()));
        children.add(ActionRow.of(createPaginationButtons(paginationPrefix, page, maxPages)));

        return Container.of(children).withAccentColor(COLOR_MAIN);
    }

    public static Container createPlaylistInfoContainer(PlaylistData playlist) {
        String title = playlist.isFavorites() ? "Favorites Info" : playlist.getName();
        StringBuilder desc = new StringBuilder();
        desc.append("### ").append(title).append("\n");
        if (!playlist.isFavorites()) {
            desc.append("**Owner:** <@").append(playlist.getUserId()).append(">\n");
        }
        desc.append("**Tracks:** ").append(playlist.getTracks().size()).append("\n");
        desc.append("**Duration:** ").append(formatDuration(playlist.getTotalDuration())).append("\n");
        desc.append("**Created:** <t:").append(playlist.getCreatedAt() / 1000).append(":R>\n");
        desc.append("**Updated:** <t:").append(playlist.getUpdatedAt() / 1000).append(":R>");
        return Container.of(TextDisplay.of(desc.toString())).withAccentColor(COLOR_MAIN);
    }

    public static List<String> splitLyrics(String lyrics) {
        List<String> pages = new ArrayList<>();
        // Split by double newline (verses)
        String[] verses = lyrics.split("\n\n");
        StringBuilder current = new StringBuilder();

        for (String verse : verses) {
            // If a single verse is massive (unlikely but possible), split it by line
            if (verse.length() > 1800) {
                String[] lines = verse.split("\n");
                for (String line : lines) {
                    if (current.length() + line.length() > 1800) {
                        pages.add(current.toString().trim());
                        current.setLength(0);
                    }
                    current.append(line).append("\n");
                }
            } else {
                if (current.length() + verse.length() > 1800) {
                    pages.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(verse).append("\n\n");
            }
        }
        if (current.length() > 0) {
            pages.add(current.toString().trim());
        }
        return pages;
    }

    public static Container createSettingsContainer(GuildSettings settings) {
        StringBuilder desc = new StringBuilder();
        desc.append("### ").append(com.discord.musicbot.config.EmojiConfig.getInstance().settings).append(" Server Settings\n");
        desc.append("Manage bot configurations for this server. Use the buttons below to toggle features.\n\n");

        String enabledEmoji = com.discord.musicbot.config.EmojiConfig.getInstance().enabled + " Enabled";
        String disabledEmoji = com.discord.musicbot.config.EmojiConfig.getInstance().disabled + " Disabled";

        desc.append("**Voice Channel Status:** ").append(settings.isUpdateVcStatus() ? enabledEmoji : disabledEmoji).append("\n");
        desc.append("**Announce Tracks:** ").append(settings.isAnnounceTracks() ? enabledEmoji : disabledEmoji).append("\n");
        desc.append("**Default Volume:** ").append(settings.getDefaultVolume()).append("%\n");
        desc.append("**24/7 Mode:** ").append(settings.isMode247() ? enabledEmoji : disabledEmoji).append("\n");
        desc.append("**Autoplay:** ").append(settings.isAutoplay() ? enabledEmoji : disabledEmoji).append("\n");
        desc.append("**Random Play:** ").append(settings.isRandomPlay() ? enabledEmoji : disabledEmoji).append("\n");
        desc.append("**DJ Mode:** ").append(settings.isDjMode() ? enabledEmoji : disabledEmoji).append("\n");
        String channel = settings.getCommandChannelId() != null ? "<#" + settings.getCommandChannelId() + ">" : "Any";
        desc.append("**Command Channel:** ").append(channel);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(desc.toString()));
        children.addAll(createSettingsComponents(settings));

        return Container.of(children).withAccentColor(COLOR_MAIN);
    }

    public static List<ActionRow> createSettingsComponents(GuildSettings settings) {
        List<ActionRow> rows = new ArrayList<>();
        
        Button statusBtn = Button.secondary("setting_status", "VC Status: " + (settings.isUpdateVcStatus() ? "ON" : "OFF"));
        Button announceBtn = Button.secondary("setting_announce", "Announce: " + (settings.isAnnounceTracks() ? "ON" : "OFF"));
        Button mode247Btn = Button.secondary("setting_247", "24/7: " + (settings.isMode247() ? "ON" : "OFF"));
        Button autoplayBtn = Button.secondary("setting_autoplay", "Autoplay: " + (settings.isAutoplay() ? "ON" : "OFF"));
        Button randomBtn = Button.secondary("setting_random", "Random: " + (settings.isRandomPlay() ? "ON" : "OFF"));
        Button djBtn = Button.secondary("setting_dj", "DJ Mode: " + (settings.isDjMode() ? "ON" : "OFF"));
        
        rows.add(ActionRow.of(statusBtn, announceBtn, mode247Btn));
        rows.add(ActionRow.of(autoplayBtn, randomBtn, djBtn));
        return rows;
    }
}
