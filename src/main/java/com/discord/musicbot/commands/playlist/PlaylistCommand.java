package com.discord.musicbot.commands.playlist;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
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
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PlaylistCommand extends SlashCommand {
    private static final Logger logger = LoggerFactory.getLogger(PlaylistCommand.class);

    @Override
    public String getName() { return "playlist"; }

    @Override
    public boolean requiresVoice() { return false; }
    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null) { ctx.replyError("Invalid subcommand."); return; }

        switch (sub) {
            case "create" -> handleCreate(ctx);
            case "delete" -> handleDelete(ctx);
            case "rename" -> handleRename(ctx);
            case "list" -> handleList(ctx);
            case "info" -> handleInfo(ctx);
            case "tracks" -> handleTracks(ctx);
            case "add" -> handleAdd(ctx);
            case "addqueue" -> handleAddQueue(ctx);
            case "remove" -> handleRemove(ctx);
            case "move" -> handleMove(ctx);
            case "dedupe" -> handleDedupe(ctx);
            case "play" -> handlePlay(ctx);
            case "instant" -> handleInstant(ctx);
            case "export" -> handleExport(ctx);
            case "import" -> handleImport(ctx);
            default -> ctx.replyError("Unknown subcommand.");
        }
    }

    // ======================== MANAGEMENT ========================

    private void handleCreate(CommandContext ctx) {
        String name = ctx.getOption("name").getAsString();
        PlaylistManager pm = PlaylistManager.getInstance();
        String normalized = pm.validateName(name);
        if (normalized == null) {
            ctx.replyError("Invalid playlist name. Must be 1-64 chars, no invisible characters.");
            return;
        }
        PlaylistData result = pm.createPlaylist(ctx.getUser().getId(), normalized);
        if (result == null) {
            // Could be duplicate or limit
            if (pm.findPlaylistByName(ctx.getUser().getId(), normalized) != null) {
                ctx.replyError("A playlist named **" + normalized + "** already exists.");
            } else {
                ctx.replyError("Playlist limit reached (" + PlaylistManager.MAX_PLAYLISTS_PER_USER + ").");
            }
            return;
        }
        ctx.replySuccess("Created playlist **" + normalized + "**");
    }

    private void handleDelete(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "name");
        if (pl == null) return;

        ctx.getEvent().reply(EmbedHelper.MSG_ERROR + " Delete **" + pl.getName() + "**? This cannot be undone.")
                .addComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                        net.dv8tion.jda.api.components.buttons.Button.danger("pl_delconfirm_" + pl.getId() + "_" + ctx.getUser().getId(), "Delete"),
                        net.dv8tion.jda.api.components.buttons.Button.secondary("pl_delcancel_" + ctx.getUser().getId(), "Cancel")
                )).queue();
    }

    private void handleRename(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "old");
        if (pl == null) return;
        String newName = ctx.getOption("new").getAsString();
        String result = PlaylistManager.getInstance().renamePlaylist(ctx.getUser().getId(), pl.getId(), newName);
        switch (result) {
            case "ok" -> ctx.replySuccess("Renamed to **" + PlaylistManager.getInstance().validateName(newName) + "**");
            case "duplicate" -> ctx.replyError("A playlist with that name already exists.");
            case "invalid_name" -> ctx.replyError("Invalid name.");
            default -> ctx.replyError("Playlist not found.");
        }
    }

    private void handleList(CommandContext ctx) {
        OptionMapping pageOpt = ctx.getOption("page");
        int page = pageOpt != null ? Math.max(1, (int) pageOpt.getAsLong()) : 1;
        List<PlaylistData> playlists = PlaylistManager.getInstance().getPlaylists(ctx.getUser().getId());
        var embed = EmbedHelper.createPlaylistListEmbed(playlists, page);
        int maxPages = Math.max(1, (int) Math.ceil(playlists.size() / 10.0));
        ctx.getEvent().replyEmbeds(embed).setComponents(
                net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                        EmbedHelper.createPaginationButtons("pllist", page, maxPages)
                )).queue();
    }

    private void handleInfo(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "name");
        if (pl == null) return;
        ctx.getEvent().replyEmbeds(EmbedHelper.createPlaylistInfoEmbed(pl)).queue();
    }

    private void handleTracks(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "name");
        if (pl == null) return;
        OptionMapping pageOpt = ctx.getOption("page");
        int page = pageOpt != null ? Math.max(1, (int) pageOpt.getAsLong()) : 1;
        var embed = EmbedHelper.createPlaylistTracksEmbed(pl, page);
        int maxPages = Math.max(1, (int) Math.ceil(pl.getTracks().size() / 10.0));
        ctx.getEvent().replyEmbeds(embed).setComponents(
                net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                        EmbedHelper.createPaginationButtons("pltracks_" + pl.getId() + "_" + ctx.getUser().getId(), page, maxPages)
                )).queue();
    }

    // ======================== TRACK MANAGEMENT ========================

    private void handleAdd(CommandContext ctx) {
        String name = ctx.getOption("playlist").getAsString();
        PlaylistData pl = PlaylistManager.getInstance().findPlaylistByName(ctx.getUser().getId(), name);
        if (pl == null) {
            String normalized = PlaylistManager.getInstance().validateName(name);
            if (normalized == null) {
                ctx.replyError("Invalid playlist name. Must be 1-64 chars, no invisible characters.");
                return;
            }
            pl = PlaylistManager.getInstance().createPlaylist(ctx.getUser().getId(), normalized);
            if (pl == null) {
                ctx.replyError("Playlist limit reached (" + PlaylistManager.MAX_PLAYLISTS_PER_USER + ").");
                return;
            }
        }
        OptionMapping queryOpt = ctx.getOption("query");

        if (queryOpt == null) {
            // Add currently playing track
            MusicManager mm = ctx.getMusicManager();
            AudioTrack current = mm != null ? mm.getPlayer().getPlayingTrack() : null;
            if (current == null) {
                ctx.replyError("Nothing is playing. Provide a URL/search or play a track first.");
                return;
            }
            PlaylistTrack pt = audioTrackToPlaylistTrack(current);
            String result = PlaylistManager.getInstance().addTrack(ctx.getUser().getId(), pl.getId(), pt);
            replyTrackAddResult(ctx, result, current.getInfo().title, pl.getName());
        } else {
            // Resolve track from query
            ctx.deferReply();
            String query = queryOpt.getAsString();
            if (!query.startsWith("http") && !query.contains(":")) query = "ytsearch:" + query;
            resolveAndAddTrack(ctx, pl, query);
        }
    }

    private void handleAddQueue(CommandContext ctx) {
        MusicManager mm = ctx.getMusicManager();
        if (mm == null || mm.getScheduler().getQueueSize() == 0) {
            ctx.replyError("The queue is empty.");
            return;
        }
        PlaylistData pl = resolvePlaylist(ctx, "playlist");
        if (pl == null) return;

        List<AudioTrack> queue = mm.getScheduler().getQueue();
        AudioTrack current = mm.getPlayer().getPlayingTrack();
        List<PlaylistTrack> tracks = new ArrayList<>();
        if (current != null) tracks.add(audioTrackToPlaylistTrack(current));
        for (AudioTrack t : queue) tracks.add(audioTrackToPlaylistTrack(t));

        int added = PlaylistManager.getInstance().addMultipleTracks(ctx.getUser().getId(), pl.getId(), tracks);
        ctx.replySuccess("Added **" + added + "** tracks to **" + pl.getName() + "**");
    }

    private void handleRemove(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "playlist");
        if (pl == null) return;
        int index = ctx.getOption("index").getAsInt() - 1;
        PlaylistTrack removed = PlaylistManager.getInstance().removeTrack(ctx.getUser().getId(), pl.getId(), index);
        if (removed != null) {
            ctx.replySuccess("Removed **" + removed.getTitle() + "** from **" + pl.getName() + "**");
        } else {
            ctx.replyError("Invalid index.");
        }
    }

    private void handleMove(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "playlist");
        if (pl == null) return;
        int from = ctx.getOption("from").getAsInt() - 1;
        int to = ctx.getOption("to").getAsInt() - 1;
        boolean ok = PlaylistManager.getInstance().moveTrack(ctx.getUser().getId(), pl.getId(), from, to);
        if (ok) ctx.replySuccess("Moved track in **" + pl.getName() + "**");
        else ctx.replyError("Invalid positions.");
    }

    private void handleDedupe(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "name");
        if (pl == null) return;
        int removed = PlaylistManager.getInstance().deduplicateTracks(ctx.getUser().getId(), pl.getId());
        ctx.replySuccess("Removed **" + removed + "** duplicate" + (removed != 1 ? "s" : "") + " from **" + pl.getName() + "**");
    }

    // ======================== PLAYBACK ========================

    private void handlePlay(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "playlist");
        if (pl == null) return;
        if (pl.getTracks().isEmpty()) { ctx.replyError("Playlist is empty."); return; }

        // Voice check
        if (!ensureVoice(ctx)) return;
        ctx.deferReply();
        enqueuePlaylistTracks(ctx, pl, false);
    }

    private void handleInstant(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "playlist");
        if (pl == null) return;
        if (pl.getTracks().isEmpty()) { ctx.replyError("Playlist is empty."); return; }
        if (!ensureVoice(ctx)) return;
        ctx.deferReply();

        // Stop current + clear queue
        MusicManager mm = ctx.getMusicManager();
        mm.getScheduler().stop();
        enqueuePlaylistTracks(ctx, pl, true);
    }

    // ======================== IMPORT / EXPORT ========================

    private void handleExport(CommandContext ctx) {
        PlaylistData pl = resolvePlaylist(ctx, "playlist");
        if (pl == null) return;
        ctx.deferReply();

        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("version", 1);
            root.put("name", pl.getName());
            ArrayNode tracksArr = root.putArray("tracks");
            for (PlaylistTrack t : pl.getTracks()) {
                ObjectNode tn = mapper.createObjectNode();
                tn.put("title", t.getTitle());
                tn.put("author", t.getAuthor());
                tn.put("duration", t.getDuration());
                tn.put("uri", t.getUri());
                tn.put("source", t.getSource());
                if (t.getEncodedTrack() != null) tn.put("encodedTrack", t.getEncodedTrack());
                tracksArr.add(tn);
            }
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            String filename = pl.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + ".json";
            ctx.getEvent().getHook().sendMessage("Exported **" + pl.getName() + "**")
                    .addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(json, filename)).queue();
        } catch (Exception e) {
            logger.error("Export failed", e);
            ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Export failed.").queue();
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
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(data);

                if (!root.has("version") || !root.has("name") || !root.has("tracks") || !root.get("tracks").isArray()) {
                    ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Invalid playlist file format.").queue();
                    return;
                }

                String name = PlaylistManager.sanitizeString(root.get("name").asText(""));
                ArrayNode tracksNode = (ArrayNode) root.get("tracks");
                List<PlaylistTrack> tracks = new ArrayList<>();
                for (JsonNode tn : tracksNode) {
                    if (tracks.size() >= PlaylistManager.MAX_TRACKS_PER_PLAYLIST) break;
                    PlaylistTrack pt = new PlaylistTrack();
                    pt.setTitle(PlaylistManager.sanitizeString(tn.path("title").asText("")));
                    pt.setAuthor(PlaylistManager.sanitizeString(tn.path("author").asText("")));
                    pt.setDuration(tn.path("duration").asLong(0));
                    pt.setUri(PlaylistManager.sanitizeString(tn.path("uri").asText("")));
                    pt.setSource(PlaylistManager.sanitizeString(tn.path("source").asText("")));
                    if (tn.has("encodedTrack")) pt.setEncodedTrack(tn.get("encodedTrack").asText());
                    if (!pt.getTitle().isEmpty()) tracks.add(pt);
                }

                if (tracks.isEmpty()) {
                    ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " No valid tracks found.").queue();
                    return;
                }

                String userId = ctx.getUser().getId();
                PlaylistData existing = PlaylistManager.getInstance().findPlaylistByName(userId, name);
                if (existing != null) {
                    // Conflict — show buttons
                    final List<PlaylistTrack> finalTracks = tracks;
                    // Store import data temporarily using button IDs
                    ImportCache.store(userId, name, finalTracks);
                    ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Playlist **" + name + "** already exists.")
                            .addComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                    net.dv8tion.jda.api.components.buttons.Button.danger("import_replace_" + userId, "Replace"),
                                    net.dv8tion.jda.api.components.buttons.Button.primary("import_rename_" + userId, "Rename"),
                                    net.dv8tion.jda.api.components.buttons.Button.secondary("import_cancel_" + userId, "Cancel")
                            )).queue();
                } else {
                    PlaylistData imported = PlaylistManager.getInstance().importPlaylist(userId, name, tracks);
                    if (imported != null) {
                        ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Imported **" + imported.getName() + "** with " + imported.getTracks().size() + " tracks").queue();
                    } else {
                        ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Import failed. Playlist limit may be reached.").queue();
                    }
                }
            } catch (Exception e) {
                logger.error("Import failed", e);
                ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Failed to parse import file.").queue();
            }
        });
    }

    // ======================== HELPERS ========================

    private PlaylistData resolvePlaylist(CommandContext ctx, String optionName) {
        String name = ctx.getOption(optionName).getAsString();
        PlaylistData pl = PlaylistManager.getInstance().findPlaylistByName(ctx.getUser().getId(), name);
        if (pl == null) ctx.replyError("Playlist **" + name + "** not found.");
        return pl;
    }

    public static PlaylistTrack audioTrackToPlaylistTrack(AudioTrack track) {
        String encoded = null;
        try { encoded = PlayerManager.getInstance().encodeAudioTrack(track.makeClone()); } catch (Exception ignored) {}
        String source = "unknown";
        if (track.getInfo().uri != null) {
            if (track.getInfo().uri.contains("youtube")) source = "youtube";
            else if (track.getInfo().uri.contains("soundcloud")) source = "soundcloud";
            else if (track.getInfo().uri.contains("spotify")) source = "spotify";
        }
        return new PlaylistTrack(track.getInfo().title, track.getInfo().author,
                track.getDuration(), track.getInfo().uri, source, encoded);
    }

    private void replyTrackAddResult(CommandContext ctx, String result, String title, String playlistName) {
        switch (result) {
            case "ok" -> ctx.replySuccess("Added **" + title + "** to **" + playlistName + "**");
            case "duplicate" -> ctx.replyError("Track already in **" + playlistName + "**");
            case "limit" -> ctx.replyError("Playlist track limit reached.");
            default -> ctx.replyError("Failed to add track.");
        }
    }

    private void resolveAndAddTrack(CommandContext ctx, PlaylistData pl, String query) {
        PlayerManager.getInstance().loadItemOrdered(ctx.getGuild(), query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                PlaylistTrack pt = audioTrackToPlaylistTrack(track);
                String result = PlaylistManager.getInstance().addTrack(ctx.getUser().getId(), pl.getId(), pt);
                String msg = result.equals("ok")
                        ? EmbedHelper.MSG_SUCCESS + " Added **" + track.getInfo().title + "** to **" + pl.getName() + "**"
                        : EmbedHelper.MSG_ERROR + " " + (result.equals("duplicate") ? "Already in playlist." : "Failed.");
                ctx.getEvent().getHook().sendMessage(msg).queue();
            }
            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.isSearchResult()) {
                    if (!playlist.getTracks().isEmpty()) trackLoaded(playlist.getTracks().get(0));
                    else noMatches();
                } else {
                    int added = 0;
                    for (AudioTrack track : playlist.getTracks()) {
                        PlaylistTrack pt = audioTrackToPlaylistTrack(track);
                        if (PlaylistManager.getInstance().addTrack(ctx.getUser().getId(), pl.getId(), pt).equals("ok")) {
                            added++;
                        }
                    }
                    ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Added **" + added + "** tracks from **" + playlist.getName() + "** to **" + pl.getName() + "**").queue();
                }
            }
            @Override
            public void noMatches() {
                ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " No matches found.").queue();
            }
            @Override
            public void loadFailed(FriendlyException e) {
                ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Load failed: " + e.getMessage()).queue();
            }
        });
    }

    private boolean ensureVoice(CommandContext ctx) {
        var userState = ctx.getMember().getVoiceState();
        if (userState == null || !userState.inAudioChannel()) {
            ctx.replyError("You need to be in a voice channel!");
            return false;
        }
        var botState = ctx.getGuild().getSelfMember().getVoiceState();
        if (botState != null && botState.inAudioChannel() && !userState.getChannel().equals(botState.getChannel())) {
            ctx.replyError("You need to be in the same voice channel as me!");
            return false;
        }
        // Connect if not connected
        if (botState == null || !botState.inAudioChannel()) {
            if (!com.discord.musicbot.commands.framework.CommandRegistry.checkBotVoicePermissions(ctx.getGuild().getSelfMember(), userState.getChannel(), ctx)) {
                return false;
            }
            ctx.getMusicManager().connectToVoiceChannel(userState.getChannel());
        }
        return true;
    }

    private void enqueuePlaylistTracks(CommandContext ctx, PlaylistData pl, boolean isInstant) {
        MusicManager mm = ctx.getMusicManager();
        mm.setNowPlayingChannel(ctx.getChannel().getId());
        List<PlaylistTrack> tracks = pl.getTracks();
        int existingQueue = mm.getScheduler().getQueueSize();
        boolean wasPlaying = mm.getPlayer().getPlayingTrack() != null;

        int loaded = 0;
        for (PlaylistTrack pt : tracks) {
            AudioTrack track = null;
            if (pt.getEncodedTrack() != null) {
                try {
                    track = PlayerManager.getInstance().decodeAudioTrack(pt.getEncodedTrack());
                } catch (Exception ignored) {}
            }
            if (track == null) {
                com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                        pt.getTitle(),
                        pt.getAuthor(),
                        pt.getDuration(),
                        pt.getUri() != null ? pt.getUri() : pt.getTitle(),
                        false,
                        pt.getUri(),
                        null,
                        null
                );
                String query = pt.getUri() != null && !pt.getUri().isEmpty() 
                               ? pt.getUri() 
                               : "ytsearch:" + pt.getTitle() + " " + (pt.getAuthor() != null ? pt.getAuthor() : "");
                track = new com.discord.musicbot.audio.DeferredTrack(info, query, null);
            }
            if (pl.isMewsic()) {
                track.setUserData("{\"requester\":\"" + ctx.getUser().getId() + "\", \"mewsic\":true}");
            } else {
                track.setUserData("{\"requester\":\"" + ctx.getUser().getId() + "\"}");
            }
            mm.getScheduler().queue(track);
            loaded++;
        }

        mm.updateNowPlayingMessage();
        String msg;
        if (isInstant || !wasPlaying) {
            msg = EmbedHelper.MSG_SUCCESS + " Playing **" + pl.getName() + "** • `" + loaded + " tracks`";
        } else {
            msg = EmbedHelper.MSG_SUCCESS + " Queued **" + pl.getName() + "** after " + existingQueue + " tracks • `" + loaded + " tracks`";
        }
        ctx.getEvent().getHook().sendMessage(msg).queue();
    }

    public static AudioTrack resolvePlaylistTrack(PlaylistTrack pt, net.dv8tion.jda.api.entities.Guild guild) {
        // Try encoded track first
        if (pt.getEncodedTrack() != null) {
            try {
                AudioTrack decoded = PlayerManager.getInstance().decodeAudioTrack(pt.getEncodedTrack());
                if (decoded != null) return decoded;
            } catch (Exception ignored) {}
        }
        // Try URI
        if (pt.getUri() != null && !pt.getUri().isEmpty()) {
            AudioTrack fromUri = loadSingle(pt.getUri(), guild);
            if (fromUri != null) return fromUri;
        }
        // Fallback: search by title + author
        if (pt.getTitle() != null && !pt.getTitle().isEmpty()) {
            String search = "ytsearch:" + pt.getTitle() + " " + (pt.getAuthor() != null ? pt.getAuthor() : "");
            return loadSingle(search.trim(), guild);
        }
        return null;
    }

    private static AudioTrack loadSingle(String query, net.dv8tion.jda.api.entities.Guild guild) {
        CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        PlayerManager.getInstance().loadItemOrdered(guild, query, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) { future.complete(track); }
            @Override public void playlistLoaded(AudioPlaylist pl) {
                future.complete(pl.getTracks().isEmpty() ? null : pl.getTracks().get(0));
            }
            @Override public void noMatches() { future.complete(null); }
            @Override public void loadFailed(FriendlyException e) { future.complete(null); }
        });
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (Exception e) { return null; }
    }

    // ======================== IMPORT CACHE ========================

    public static class ImportCache {
        private static final java.util.concurrent.ConcurrentHashMap<String, ImportData> pending = new java.util.concurrent.ConcurrentHashMap<>();
        public record ImportData(String name, List<PlaylistTrack> tracks, long timestamp) {}

        public static void store(String userId, String name, List<PlaylistTrack> tracks) {
            pending.put(userId, new ImportData(name, tracks, System.currentTimeMillis()));
            // Auto-expire after 60 seconds
            CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS).execute(() -> pending.remove(userId));
        }
        public static ImportData get(String userId) { return pending.remove(userId); }
    }

    // ======================== COMMAND DATA ========================

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Manage your playlists")
                .addSubcommands(
                        new SubcommandData("create", "Create a new playlist")
                                .addOption(OptionType.STRING, "name", "Playlist name", true),
                        new SubcommandData("delete", "Delete a playlist")
                                .addOption(OptionType.STRING, "name", "Playlist name", true, true),
                        new SubcommandData("rename", "Rename a playlist")
                                .addOption(OptionType.STRING, "old", "Current name", true, true)
                                .addOption(OptionType.STRING, "new", "New name", true),
                        new SubcommandData("list", "View your playlists")
                                .addOption(OptionType.INTEGER, "page", "Page number", false),
                        new SubcommandData("info", "View playlist info")
                                .addOption(OptionType.STRING, "name", "Playlist name", true, true),
                        new SubcommandData("tracks", "View tracks in a playlist")
                                .addOption(OptionType.STRING, "name", "Playlist name", true, true)
                                .addOption(OptionType.INTEGER, "page", "Page number", false),
                        new SubcommandData("add", "Add a track to a playlist")
                                .addOption(OptionType.STRING, "playlist", "Playlist name", true, true)
                                .addOption(OptionType.STRING, "query", "Search query or URL", false, true),
                        new SubcommandData("addqueue", "Add current queue to a playlist")
                                .addOption(OptionType.STRING, "playlist", "Playlist name", true, true),
                        new SubcommandData("remove", "Remove a track from a playlist")
                                .addOption(OptionType.STRING, "playlist", "Playlist name", true, true)
                                .addOption(OptionType.INTEGER, "index", "Track number", true),
                        new SubcommandData("move", "Move a track in a playlist")
                                .addOption(OptionType.STRING, "playlist", "Playlist name", true, true)
                                .addOption(OptionType.INTEGER, "from", "Current position", true)
                                .addOption(OptionType.INTEGER, "to", "New position", true),
                        new SubcommandData("dedupe", "Remove duplicate tracks")
                                .addOption(OptionType.STRING, "name", "Playlist name", true, true),
                        new SubcommandData("play", "Play a playlist")
                                .addOption(OptionType.STRING, "playlist", "Playlist name", true, true),
                        new SubcommandData("instant", "Force-play a playlist immediately")
                                .addOption(OptionType.STRING, "playlist", "Playlist name", true, true),
                        new SubcommandData("export", "Export a playlist as JSON")
                                .addOption(OptionType.STRING, "playlist", "Playlist name", true, true),
                        new SubcommandData("import", "Import a playlist from JSON")
                                .addOption(OptionType.ATTACHMENT, "file", "JSON file", true)
                );
    }
}
