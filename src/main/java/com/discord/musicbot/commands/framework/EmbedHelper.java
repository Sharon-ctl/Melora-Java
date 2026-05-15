package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.MusicManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.components.actionrow.ActionRow;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;

import java.util.ArrayList;
import java.util.List;

public class EmbedHelper {

    // --- Message Emojis (Text Replies) ---
    public static final String MSG_SUCCESS = "<:success1:1461351761607393453>";
    public static final String MSG_ERROR = "<:error1:1461351972924817552>";
    public static final String MSG_PAUSE = "<:pause1:1461362470370152509>";
    public static final String MSG_PLAY = "<:play1:1461362473130135746>";
    public static final String MSG_SKIP = "<:skip1:1461362487327981588>";
    public static final String MSG_STOP = "<:stop1:1461351823477833860>";

    public static final String MSG_PREVIOUS = "<:previous:1422048593614864477>";
    public static final String MSG_VOLUME = "<:volume1:1461352140160237713>";
    public static final String MSG_TIME = "<:time1:1461362493803855975>";
    public static final String MSG_SHUFFLE = "<:shuffle1:1461362482869174416>";
    public static final String MSG_REPEAT = "<:repeat1:1461362480566636544>";

    public static final int COLOR_MAIN = 0x2f3136;

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

    public static MessageEmbed createQueueEmbed(MusicManager manager, int page) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(COLOR_MAIN);
        embed.setTitle("Queue");
        embed.setTimestamp(java.time.Instant.now());

        // Footer
        int queueSize = manager.getScheduler().getQueueSize();
        long totalDuration = manager.getScheduler().getQueueDuration();
        int maxPages = (int) Math.ceil(queueSize / 10.0);
        maxPages = Math.max(1, maxPages);

        String loopMode = manager.getScheduler().getLoopMode().name();
        loopMode = loopMode.substring(0, 1).toUpperCase() + loopMode.substring(1).toLowerCase();

        StringBuilder footer = new StringBuilder();
        footer.append("Page ").append(page).append("/").append(maxPages);
        if (queueSize > 0) {
            footer.append(" | ").append(queueSize).append(" track").append(queueSize != 1 ? "s" : "");
            footer.append(" | ").append(formatDuration(totalDuration)).append(" total");
        }
        if (!loopMode.equalsIgnoreCase("Off")) {
            footer.append(" | Loop: ").append(loopMode);
        }
        if (manager.getScheduler().isPaused())
            footer.append(" | Paused");

        if (manager.getScheduler().getAutoplay())
            footer.append(" | Autoplay");

        embed.setFooter(footer.toString());

        StringBuilder description = new StringBuilder();

        AudioTrack current = manager.getPlayer().getPlayingTrack();
        if (current != null) {
            description.append("**Current Track**\n");
            description.append("[").append(current.getInfo().title).append("](").append(current.getInfo().uri)
                    .append(")\n");
            description.append(createProgressBar(current.getPosition(), current.getDuration())).append("\n");

            String requester = "Unknown";
            if (current.getUserData() instanceof net.dv8tion.jda.api.entities.User) {
                requester = ((net.dv8tion.jda.api.entities.User) current.getUserData()).getAsMention();
            } else if (current.getUserData() instanceof String) {
                String ud = (String) current.getUserData();
                if (ud.contains("\"requester\":\"")) {
                    String id = ud.split("\"requester\":\"")[1].split("\"")[0];
                    requester = "<@" + id + ">";
                } else {
                    requester = ud;
                }
            }

            description.append("`").append(formatTime(current.getPosition())).append(" / ")
                    .append(formatTime(current.getDuration())).append("` | Requested by ").append(requester)
                    .append("\n\n");
        }

        if (queueSize > 0) {
            description.append("**Queue**\n```md\n");
            List<AudioTrack> queue = new ArrayList<>(manager.getScheduler().getQueue());
            int start = (page - 1) * 10;
            int end = Math.min(start + 10, queue.size());

            for (int i = start; i < end; i++) {
                AudioTrack track = queue.get(i);
                String title = track.getInfo().title;
                if (title.length() > 60) {
                    title = title.substring(0, 57) + "...";
                }
                
                description.append(String.format("%d. %s\n", i + 1, title));
            }
            description.append("```\n");
        } else if (current == null) {
            description.append("No tracks in queue");
        }

        embed.setDescription(description.toString());
        return embed.build();
    }

    public static List<Button> createPaginationButtons(String idPrefix, int page, int maxPages) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.secondary(idPrefix + "_first_" + page, "<<").withDisabled(page <= 1));
        buttons.add(Button.secondary(idPrefix + "_prev_" + page, "<").withDisabled(page <= 1));
        buttons.add(Button.secondary(idPrefix + "_next_" + page, ">").withDisabled(page >= maxPages));
        buttons.add(Button.secondary(idPrefix + "_last_" + page, ">>").withDisabled(page >= maxPages));
        return buttons;
    }

    public static MessageEmbed createHelpEmbed(String category, String prefix, JDA jda) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(COLOR_MAIN)
                .setTitle("Music Commands")
                .setThumbnail(jda.getSelfUser().getAvatarUrl())
                .setTimestamp(java.time.Instant.now())
                .setFooter("Slash Commands Supported");

        StringBuilder description = new StringBuilder();

        if (category.equals("playback")) {
            description.append("**").append(prefix)
                    .append("play <song/url>**\nPlay a song or add it to the queue.\n\n");
            description.append("**").append(prefix)
                    .append("playinstant <song/url>**\nPlay a song immediately, skipping the current track.\n\n");
            description.append("**").append(prefix).append("pause**\nPause the currently playing track.\n\n");
            description.append("**").append(prefix).append("resume**\nResume the paused track.\n\n");
            description.append("**").append(prefix).append("skip**\nSkip to the next track in the queue.\n\n");
            description.append("**").append(prefix)
                    .append("previous**\nPlay the previously played track from history.\n\n");
            description.append("**").append(prefix).append("stop**\nStop playback and clear the entire queue.\n\n");
            description.append("**").append(prefix)
                    .append("disconnect**\nDisconnect the bot from the voice channel.\n\n");
            description.append("**").append(prefix).append("autoplay**\nToggle automatic recommendations.\n\n");
            description.append("**").append(prefix).append("247**\nKeep the bot in the voice channel 24/7.\n\n");
        } else if (category.equals("queue")) {
            description.append("**").append(prefix).append("queue [page]**\nView all tracks in the current queue.\n\n");
            description.append("**").append(prefix)
                    .append("nowplaying**\nView details about the currently playing track.\n\n");
            description.append("**").append(prefix)
                    .append("shuffle**\nRandomize the order of tracks in the queue.\n\n");
            description.append("**").append(prefix).append("loop <mode>**\nSet repeat mode: off, track, or queue.\n\n");
            description.append("**").append(prefix)
                    .append("remove <number>**\nRemove a specific track from the queue.\n\n");
            description.append("**").append(prefix)
                    .append("insert <song/url> <position>**\nInsert a song at a specific position in the queue.\n\n");
            description.append("**").append(prefix)
                    .append("move <from> <to>**\nMove a track to a different position.\n\n");
            description.append("**").append(prefix).append("clear**\nRemove all tracks from the queue.\n\n");
            description.append("**").append(prefix)
                    .append("jump <number>**\nSkip directly to a specific track in the queue.\n\n");
        } else if (category.equals("settings")) {
            description.append("**").append(prefix).append("volume <1-200>**\nAdjust the playback volume.\n\n");
            description.append("**").append(prefix)
                    .append("seek <time>**\nJump to a specific timestamp in the track.\n\n");
            description.append("**").append(prefix).append("ping**\nCheck bot latency.\n\n");
        }

        embed.setDescription(description.toString());
        return embed.build();
    }

    public static ActionRow createHelpMenu() {
        StringSelectMenu menu = StringSelectMenu.create("help_menu")
                .setPlaceholder("Select a category")
                .addOption("Playback", "playback", "Playback control commands")
                .addOption("Queue", "queue", "Queue management commands")
                .addOption("Settings", "settings", "Player settings commands")
                .build();

        return ActionRow.of(menu);
    }
    public static List<ActionRow> createNowPlayingComponents(MusicManager manager) {
        List<Button> buttons = new ArrayList<>();
        boolean paused = manager.getScheduler().isPaused();

        // 1. Loop
        buttons.add(Button.secondary("np_loop", Emoji.fromFormatted("<:loop3:1504784020020527105>")));

        // 2. Previous
        buttons.add(Button.secondary("np_previous", Emoji.fromFormatted("<:previous3:1504769078374694962>")));

        // 3. Play/Pause
        buttons.add(Button.secondary("np_pause", Emoji.fromFormatted(paused ? "<:resume3:1504769080346021898>" : "<:pause3:1504769082640171018>")));

        // 4. Skip
        buttons.add(Button.secondary("np_skip", Emoji.fromFormatted("<:skip3:1504769076189462619>")));

        // 5. Stop (danger)
        buttons.add(Button.danger("np_stop", Emoji.fromFormatted("<:stop3:1504769073517564015>")));

        return List.of(ActionRow.of(buttons));
    }
}
