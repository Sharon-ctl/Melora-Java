package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.commands.playlist.PlaylistCommand;
import com.discord.musicbot.data.model.PlaylistTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.List;
import java.util.ArrayList;

public class QueueCommand extends SlashCommand {
    @Override
    public String getName() {
        return "queue";
    }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null) sub = "show";

        switch (sub) {
            case "show": handleShow(ctx); break;
            case "search": handleSearch(ctx); break;
            case "deduplicate": handleDeduplicate(ctx); break;
            case "compact": handleCompact(ctx); break;
            case "reverse": handleReverse(ctx); break;
            case "sort": handleSort(ctx); break;
            case "slice": handleSlice(ctx); break;
            case "shufflefrom": handleShuffleFrom(ctx); break;
            case "swap": handleSwap(ctx); break;
            case "export": handleExport(ctx); break;
            case "import": handleImport(ctx); break;
            default: ctx.replyError("Unknown subcommand.");
        }
    }

    private void handleShow(CommandContext ctx) {
        var pageOpt = ctx.getOption("page");
        int page = pageOpt != null ? Math.max(1, (int) pageOpt.getAsLong()) : 1;
        var userOpt = ctx.getOption("user");
        String filterUserId = userOpt != null ? userOpt.getAsString() : null;
        
        long filteredSize = filterUserId == null ? ctx.getScheduler().getQueueSize() : 
            ctx.getScheduler().getQueue().stream().filter(t -> {
                Object ud = t.getUserData();
                String id = "";
                if (ud instanceof net.dv8tion.jda.api.entities.User u) id = u.getId();
                else if (ud instanceof String s) {
                    if (s.contains("\"requester\":\"")) id = s.split("\"requester\":\"")[1].split("\"")[0];
                    else id = s;
                }
                return id.equals(filterUserId);
            }).count();

        if (filteredSize == 0) {
            ctx.reply("No tracks in the queue.");
            return;
        }

        var container = EmbedHelper.createQueueContainer(ctx.getMusicManager(), page, filterUserId);
        ctx.getEvent().replyComponents(container).useComponentsV2().setAllowedMentions(java.util.Collections.emptyList()).queue();
    }

    private void handleSearch(CommandContext ctx) {
        String query = ctx.getOption("query").getAsString().toLowerCase();
        List<AudioTrack> queue = ctx.getScheduler().getQueue();
        
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < queue.size(); i++) {
            AudioTrack t = queue.get(i);
            if (t.getInfo().title.toLowerCase().contains(query) || t.getInfo().author.toLowerCase().contains(query) || (t.getInfo().uri != null && t.getInfo().uri.toLowerCase().contains(query))) {
                sb.append("`").append(i + 1).append(".` ").append(t.getInfo().title).append("\n");
                count++;
                if (count >= 15) break;
            }
        }
        
        String content;
        if (count == 0) content = "### Search Results in Queue\nNo tracks matched your query.";
        else {
            if (count >= 15) sb.append("*...and more*");
            content = "### Search Results in Queue\n" + sb.toString();
        }
        
        ctx.getEvent().replyComponents(Container.of(TextDisplay.of(content)).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
    }

    private void handleDeduplicate(CommandContext ctx) {
        if (!checkDj(ctx)) return;
        int deduped = ctx.getScheduler().dedupeQueue();
        ctx.replySuccess("Removed **" + deduped + "** duplicate tracks from the queue.");
    }

    private void handleCompact(CommandContext ctx) {
        if (!checkDj(ctx)) return;
        int cleaned = ctx.getScheduler().cleanupQueue();
        ctx.replySuccess("Cleaned up **" + cleaned + "** tracks (duplicates/invalid) from the queue.");
    }

    private void handleReverse(CommandContext ctx) {
        if (!checkDj(ctx)) return;
        ctx.getScheduler().reverseQueue();
        ctx.replySuccess("Queue reversed.");
    }

    private void handleSort(CommandContext ctx) {
        if (!checkDj(ctx)) return;
        String mode = ctx.getOption("mode").getAsString();
        ctx.getScheduler().sortQueue(mode);
        ctx.replySuccess("Queue sorted by **" + mode + "**.");
    }

    private void handleSlice(CommandContext ctx) {
        int start = (int) ctx.getOption("start").getAsLong();
        int end = (int) ctx.getOption("end").getAsLong();
        List<AudioTrack> queue = ctx.getScheduler().getQueue();
        
        if (start < 1 || start > queue.size() || end < start || end > queue.size()) {
            ctx.replyError("Invalid range.");
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = start - 1; i < Math.min(end, start + 14); i++) {
            AudioTrack t = queue.get(i);
            sb.append("`").append(i + 1).append(".` ").append(t.getInfo().title).append("\n");
        }
        if (end - start > 14) sb.append("*...and more*");
        
        ctx.getEvent().replyComponents(Container.of(TextDisplay.of("### Queue Slice (" + start + " to " + end + ")\n" + sb.toString())).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
    }

    private void handleShuffleFrom(CommandContext ctx) {
        if (!checkDj(ctx)) return;
        int index = (int) ctx.getOption("index").getAsLong();
        ctx.getScheduler().shuffleFrom(index - 1);
        ctx.replySuccess("Shuffled queue from position " + index + ".");
    }

    private void handleSwap(CommandContext ctx) {
        if (!checkDj(ctx)) return;
        int index1 = (int) ctx.getOption("pos1").getAsLong();
        int index2 = (int) ctx.getOption("pos2").getAsLong();
        boolean success = ctx.getScheduler().swap(index1 - 1, index2 - 1);
        if (success) {
            ctx.replySuccess("Swapped positions " + index1 + " and " + index2 + ".");
        } else {
            ctx.replyError("Invalid positions provided.");
        }
    }

    private void handleExport(CommandContext ctx) {
        List<AudioTrack> queue = ctx.getScheduler().getQueue();
        if (queue.isEmpty()) {
            ctx.replyError("The queue is empty.");
            return;
        }
        
        List<PlaylistTrack> exportData = new ArrayList<>();
        for (AudioTrack t : queue) {
            exportData.add(PlaylistCommand.audioTrackToPlaylistTrack(t));
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] json = mapper.writeValueAsBytes(exportData);
            ctx.getEvent().replyComponents(
                Container.of(TextDisplay.of("Here is your queue export:")).withAccentColor(EmbedHelper.COLOR_MAIN)
            ).useComponentsV2().addFiles(FileUpload.fromData(json, "queue_export.json")).queue();
        } catch (Exception e) {
            ctx.replyError("Failed to export queue.");
        }
    }

    private void handleImport(CommandContext ctx) {
        if (!checkDj(ctx)) return;
        net.dv8tion.jda.api.entities.Message.Attachment attachment = ctx.getOption("file").getAsAttachment();
        if (!attachment.getFileExtension().equalsIgnoreCase("json")) {
            ctx.replyError("Please upload a valid JSON file.");
            return;
        }
        
        ctx.getMusicManager().setNowPlayingChannel(ctx.getChannel().getId());
        ctx.deferReply();
        attachment.getProxy().download().thenAccept(inputStream -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<PlaylistTrack> tracks = mapper.readValue(inputStream, mapper.getTypeFactory().constructCollectionType(List.class, PlaylistTrack.class));
                
                int queued = 0;
                String requesterId = ctx.getUser().getId();
                for (PlaylistTrack pt : tracks) {
                    String cleanTitle = com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(pt.getTitle());
                    String cleanAuthor = com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(pt.getAuthor());
                    com.discord.musicbot.audio.PlayerManager.getInstance().loadItemWithFallback(ctx.getGuild(), pt.getUri() != null ? pt.getUri() : "ytsearch:" + cleanAuthor + " " + cleanTitle, new com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler() {
                        @Override
                        public void trackLoaded(AudioTrack track) {
                            track.setUserData("{\"requester\":\"" + requesterId + "\"}");
                            ctx.getScheduler().queue(track);
                        }
                        @Override public void playlistLoaded(com.sedmelluq.discord.lavaplayer.track.AudioPlaylist playlist) {
                            if (!playlist.getTracks().isEmpty()) trackLoaded(playlist.getTracks().get(0));
                        }
                        @Override public void noMatches() {}
                        @Override public void loadFailed(com.sedmelluq.discord.lavaplayer.tools.FriendlyException exception) {}
                    });
                    queued++;
                    if (queued >= 500) break;
                }
                ctx.replySuccess("Started importing " + tracks.size() + " tracks from the JSON file.");
            } catch (Exception e) {
                ctx.replyError("Failed to parse JSON file.");
            }
        });
    }

    private boolean checkDj(CommandContext ctx) {
        return com.discord.musicbot.commands.framework.CommandRegistry.checkDjRole(ctx.getGuild(), ctx.getMember(), ctx.getMusicManager(), ctx.getEvent());
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Manage the music queue")
                .addSubcommands(
                        new SubcommandData("show", "View the current queue")
                                .addOption(OptionType.INTEGER, "page", "Page number", false)
                                .addOption(OptionType.STRING, "user", "Filter queue by user", false, true),
                        new SubcommandData("search", "Search for a track in the queue")
                                .addOption(OptionType.STRING, "query", "Title or artist to search", true),
                        new SubcommandData("deduplicate", "Remove duplicate tracks from the queue"),
                        new SubcommandData("compact", "Remove invalid/dead tracks from the queue"),
                        new SubcommandData("reverse", "Reverse the order of the queue"),
                        new SubcommandData("sort", "Sort the queue")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "mode", "Sort criteria", true)
                                                .addChoice("Title", "title")
                                                .addChoice("Artist", "artist")
                                                .addChoice("Duration", "duration")
                                                .addChoice("Requester", "requester")
                                ),
                        new SubcommandData("slice", "View a specific segment of the queue")
                                .addOption(OptionType.INTEGER, "start", "Start position", true)
                                .addOption(OptionType.INTEGER, "end", "End position", true),
                        new SubcommandData("shufflefrom", "Shuffle the queue starting from a specific position")
                                .addOption(OptionType.INTEGER, "index", "Position to start shuffling from", true),
                        new SubcommandData("swap", "Swap the position of two tracks")
                                .addOption(OptionType.INTEGER, "pos1", "First track position", true)
                                .addOption(OptionType.INTEGER, "pos2", "Second track position", true),
                        new SubcommandData("export", "Export the current queue as a JSON file"),
                        new SubcommandData("import", "Import a queue from a JSON file")
                                .addOption(OptionType.ATTACHMENT, "file", "JSON file to import", true)
                );
    }
}
