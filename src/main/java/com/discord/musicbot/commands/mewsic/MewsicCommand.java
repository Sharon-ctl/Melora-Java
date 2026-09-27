package com.discord.musicbot.commands.mewsic;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.data.PlaylistManager;
import com.discord.musicbot.data.model.PlaylistData;
import com.discord.musicbot.data.model.PlaylistTrack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MewsicCommand extends SlashCommand {
    private static final Logger logger = LoggerFactory.getLogger(MewsicCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "mewsic";
    }

    @Override
    public boolean requiresVoice() { return false; }

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash("mewsic", "Integration with the offline Mewsic app")
                .addSubcommands(
                        new SubcommandData("play", "Instantly play a Mewsic playlist JSON file")
                                .addOption(OptionType.ATTACHMENT, "file", "The Mewsic JSON playlist file", true),
                        new SubcommandData("import", "Import a Mewsic playlist JSON file")
                                .addOption(OptionType.ATTACHMENT, "file", "The Mewsic JSON playlist file", true),
                        new SubcommandData("export", "Export the current queue or a playlist to Mewsic JSON format")
                                .addOptions(
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(OptionType.STRING, "source", "Export from queue or a saved playlist", true)
                                                .addChoice("Current Queue", "queue")
                                                .addChoice("Saved Playlist", "playlist"),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(OptionType.STRING, "playlist", "Name of the playlist to export (if source is Playlist)", false, true)
                                )
                );
    }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null) { ctx.replyError("Invalid subcommand."); return; }

        switch (sub) {
            case "play" -> handlePlay(ctx);
            case "import" -> handleImport(ctx);
            case "export" -> handleExport(ctx);
            default -> ctx.replyError("Unknown subcommand.");
        }
    }

    private void handleExport(CommandContext ctx) {
        String source = ctx.getOption("source").getAsString();
        
        PlaylistData pl = null;
        com.discord.musicbot.audio.MusicManager mm = null;

        if (source.equals("playlist")) {
            var plOpt = ctx.getOption("playlist");
            if (plOpt == null) {
                ctx.replyError("You must specify a playlist name when exporting a saved playlist.");
                return;
            }
            String name = plOpt.getAsString();
            pl = PlaylistManager.getInstance().findPlaylistByName(ctx.getUser().getId(), name);
            if (pl == null) {
                ctx.replyError("Playlist **" + name + "** not found.");
                return;
            }
        } else {
            mm = ctx.getMusicManager();
            if (mm == null || (mm.getPlayer().getPlayingTrack() == null && mm.getScheduler().getQueue().isEmpty())) {
                ctx.replyError("The queue is empty.");
                return;
            }
        }

        ctx.deferReply();

        try {
            ObjectNode root = mapper.createObjectNode();
            String playlistId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            root.put("id", playlistId);
            
            String exportName = source.equals("playlist") ? pl.getName() : "Queue_Export_" + (System.currentTimeMillis() / 1000L);
            root.put("name", exportName);
            root.put("file_path", "");
            
            ArrayNode trackIds = root.putArray("track_ids");
            ArrayNode tracksArr = root.putArray("tracks");
            
            root.put("created_at", System.currentTimeMillis() / 1000L);

            if (source.equals("playlist")) {
                for (PlaylistTrack t : pl.getTracks()) {
                    ObjectNode tn = mapper.createObjectNode();
                    String trackId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    trackIds.add(trackId);
                    
                    tn.put("id", trackId);
                    tn.put("title", t.getTitle());
                    tn.put("artist", t.getAuthor());
                    tn.put("album", "Unknown Album");
                    tn.put("duration", t.getDuration() / 1000.0);
                    tn.put("file_path", "");
                    
                    String sourceId = null;
                    if (t.getSource() != null && t.getSource().equalsIgnoreCase("youtube") && t.getUri() != null) {
                        if (t.getUri().contains("v=")) {
                            String[] parts = t.getUri().split("v=");
                            if (parts.length > 1) {
                                sourceId = parts[1].split("&")[0];
                            }
                        }
                    }
                    tn.put("source_id", sourceId);
                    tracksArr.add(tn);
                }
            } else {
                List<com.sedmelluq.discord.lavaplayer.track.AudioTrack> allTracks = new ArrayList<>();
                if (mm.getPlayer().getPlayingTrack() != null) {
                    allTracks.add(mm.getPlayer().getPlayingTrack());
                }
                allTracks.addAll(mm.getScheduler().getQueue());

                for (com.sedmelluq.discord.lavaplayer.track.AudioTrack t : allTracks) {
                    ObjectNode tn = mapper.createObjectNode();
                    String trackId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    trackIds.add(trackId);
                    
                    tn.put("id", trackId);
                    tn.put("title", t.getInfo().title);
                    tn.put("artist", t.getInfo().author);
                    tn.put("album", "Unknown Album");
                    tn.put("duration", t.getDuration() / 1000.0);
                    tn.put("file_path", "");
                    
                    String sourceId = null;
                    String uri = t.getInfo().uri;
                    if (uri != null && uri.contains("youtube.com") && uri.contains("v=")) {
                        String[] parts = uri.split("v=");
                        if (parts.length > 1) {
                            sourceId = parts[1].split("&")[0];
                        }
                    }
                    tn.put("source_id", sourceId);
                    tracksArr.add(tn);
                }
            }

            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            String filename = exportName.replaceAll("[^a-zA-Z0-9_-]", "_") + ".mewsic.json";
            ctx.getEvent().getHook().sendMessageComponents(
                    net.dv8tion.jda.api.components.container.Container.of(
                            net.dv8tion.jda.api.components.textdisplay.TextDisplay.of("Exported **" + exportName + "** to Mewsic format.")
                    ).withAccentColor(EmbedHelper.COLOR_MAIN)
            ).useComponentsV2().addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(json, filename)).queue();
        } catch (Exception e) {
            logger.error("Mewsic Export failed", e);
            ctx.replyError("Export failed.");
        }
    }

    private void handleImport(CommandContext ctx) {
        var attachment = ctx.getOption("file").getAsAttachment();
        if (attachment.getSize() > PlaylistManager.MAX_IMPORT_FILE_SIZE) {
            ctx.replyError("File too large. Max 5MB.");
            return;
        }
        ctx.deferReply();
        attachment.getProxy().download().thenAccept(stream -> {
            try {
                byte[] data = stream.readAllBytes();
                stream.close();
                JsonNode root = mapper.readTree(data);

                if (!root.has("name") || !root.has("tracks") || !root.get("tracks").isArray()) {
                    ctx.replyError("Invalid Mewsic playlist file format.");
                    return;
                }

                String name = PlaylistManager.sanitizeString(root.get("name").asText(""));
                ArrayNode tracksNode = (ArrayNode) root.get("tracks");
                List<PlaylistTrack> tracks = new ArrayList<>();

                for (JsonNode tn : tracksNode) {
                    if (tracks.size() >= PlaylistManager.MAX_TRACKS_PER_PLAYLIST) break;
                    
                    PlaylistTrack pt = new PlaylistTrack();
                    String title = PlaylistManager.sanitizeString(tn.path("title").asText(""));
                    String artist = PlaylistManager.sanitizeString(tn.path("artist").asText(""));
                    double durationSecs = tn.path("duration").asDouble(0);
                    pt.setTitle(title);
                    pt.setAuthor(artist);
                    pt.setDuration((long) (durationSecs * 1000));
                    pt.setSource("youtube");
                    
                    String sourceId = tn.path("source_id").isNull() ? null : tn.path("source_id").asText();
                    if (sourceId != null && !sourceId.isEmpty()) {
                        pt.setUri("https://www.youtube.com/watch?v=" + sourceId);
                    } else {
                        pt.setUri("ytsearch:" + com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(artist) + " - " + com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(title));
                    }
                    
                    if (!pt.getTitle().isEmpty()) {
                        tracks.add(pt);
                    }
                }

                if (tracks.isEmpty()) {
                    ctx.replyError("No valid tracks found.");
                    return;
                }

                String userId = ctx.getUser().getId();
                PlaylistData existing = PlaylistManager.getInstance().findPlaylistByName(userId, name);
                
                if (existing != null) {
                    com.discord.musicbot.commands.playlist.PlaylistCommand.ImportCache.store(userId, name, tracks);
                    ctx.getEvent().getHook().sendMessageComponents(
                            net.dv8tion.jda.api.components.container.Container.of(
                                    net.dv8tion.jda.api.components.textdisplay.TextDisplay.of(EmbedHelper.MSG_ERROR + " Playlist **" + name + "** already exists.")
                            ).withAccentColor(EmbedHelper.COLOR_MAIN)
                    ).useComponentsV2().addComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                    net.dv8tion.jda.api.components.buttons.Button.danger("import_replace_mewsic_" + userId, "Replace"),
                                    net.dv8tion.jda.api.components.buttons.Button.primary("import_rename_" + userId, "Rename"),
                                    net.dv8tion.jda.api.components.buttons.Button.secondary("import_cancel_" + userId, "Cancel")
                            )).queue();
                } else {
                    PlaylistData imported = PlaylistManager.getInstance().importPlaylist(userId, name, tracks);
                    if (imported != null) {
                        PlaylistManager.getInstance().markAsMewsic(userId, imported.getId());
                        ctx.replySuccess("Imported Mewsic playlist **" + imported.getName() + "** with " + imported.getTracks().size() + " tracks");
                    } else {
                        ctx.replyError("Import failed. Playlist limit may be reached.");
                    }
                }
            } catch (Exception e) {
                logger.error("Mewsic Import failed", e);
                ctx.replyError("Failed to parse Mewsic import file.");
            }
        });
    }

    private void handlePlay(CommandContext ctx) {
        var userState = ctx.getMember().getVoiceState();
        if (userState == null || !userState.inAudioChannel()) {
            ctx.replyError("You need to be in a voice channel!");
            return;
        }

        var botState = ctx.getGuild().getSelfMember().getVoiceState();
        if (botState != null && botState.inAudioChannel() && !userState.getChannel().equals(botState.getChannel())) {
            ctx.replyError("You need to be in the same voice channel as me!");
            return;
        }

        if (botState == null || !botState.inAudioChannel()) {
            if (!com.discord.musicbot.commands.framework.CommandRegistry.checkBotVoicePermissions(ctx.getGuild().getSelfMember(), userState.getChannel(), ctx)) {
                return;
            }
            ctx.getMusicManager().connectToVoiceChannel(userState.getChannel());
        }

        var attachment = ctx.getOption("file").getAsAttachment();
        if (attachment.getSize() > PlaylistManager.MAX_IMPORT_FILE_SIZE) {
            ctx.replyError("File too large. Max 5MB.");
            return;
        }
        ctx.deferReply();

        attachment.getProxy().download().thenAccept(stream -> {
            try {
                byte[] data = stream.readAllBytes();
                stream.close();
                JsonNode root = mapper.readTree(data);

                if (!root.has("tracks") || !root.get("tracks").isArray()) {
                    ctx.replyError("Invalid Mewsic file format.");
                    return;
                }

                ArrayNode tracksNode = (ArrayNode) root.get("tracks");
                com.discord.musicbot.audio.MusicManager mm = ctx.getMusicManager();
                mm.setNowPlayingChannel(ctx.getChannel().getId());
                boolean wasPlaying = mm.getPlayer().getPlayingTrack() != null;
                int existingQueue = mm.getScheduler().getQueueSize();
                int loaded = 0;

                for (JsonNode tn : tracksNode) {
                    if (loaded >= PlaylistManager.MAX_TRACKS_PER_PLAYLIST) break;
                    
                    String title = PlaylistManager.sanitizeString(tn.path("title").asText(""));
                    String artist = PlaylistManager.sanitizeString(tn.path("artist").asText(""));
                    double durationSecs = tn.path("duration").asDouble(0);
                    long duration = (long) (durationSecs * 1000);
                    
                    String sourceId = tn.path("source_id").isNull() ? null : tn.path("source_id").asText();
                    String uri = null;
                    if (sourceId != null && !sourceId.isEmpty()) {
                        uri = "https://www.youtube.com/watch?v=" + sourceId;
                    }

                    String cleanTitle = com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(title);
                    String cleanArtist = com.discord.musicbot.audio.PlayerManager.cleanTrackTitle(artist);
                    com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                            cleanTitle,
                            cleanArtist,
                            duration,
                            uri != null ? uri : cleanTitle,
                            false,
                            uri,
                            null,
                            null
                    );
                    String query = uri != null ? uri : "ytsearch:" + cleanArtist + " - " + cleanTitle;
                    
                    com.sedmelluq.discord.lavaplayer.track.AudioTrack track = new com.discord.musicbot.audio.DeferredTrack(info, query, null);
                    track.setUserData("{\"requester\":\"" + ctx.getUser().getId() + "\", \"mewsic\":true}");
                    mm.getScheduler().queue(track);
                    loaded++;
                }

                if (loaded == 0) {
                    ctx.replyError("No valid tracks found.");
                    return;
                }

                mm.updateNowPlayingMessage();
                String name = root.has("name") ? root.get("name").asText() : "Mewsic Playlist";
                if (!wasPlaying) {
                    ctx.replySuccess("Playing **" + name + "** • `" + loaded + " tracks`");
                } else {
                    ctx.replySuccess("Queued **" + name + "** after " + existingQueue + " tracks • `" + loaded + " tracks`");
                }

            } catch (Exception e) {
                logger.error("Mewsic Play failed", e);
                ctx.replyError("Failed to parse Mewsic file.");
            }
        });
    }
}
