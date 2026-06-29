package com.discord.musicbot.commands.favorites;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.playlist.PlaylistCommand;
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
import java.util.Collections;
import java.util.List;


public class FavoritesCommand extends SlashCommand {
    private static final Logger logger = LoggerFactory.getLogger(FavoritesCommand.class);

    @Override
    public String getName() { return "favorites"; }

    @Override
    public boolean requiresVoice() { return false; }
    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null) { ctx.replyError("Invalid subcommand."); return; }

        switch (sub) {
            case "add" -> handleAdd(ctx);
            case "remove" -> handleRemove(ctx);
            case "list" -> handleList(ctx);
            case "info" -> handleInfo(ctx);
            case "clear" -> handleClear(ctx);
            case "play" -> handlePlay(ctx);
            case "instant" -> handleInstant(ctx);
            case "shuffle" -> handleShuffle(ctx);
            case "export" -> handleExport(ctx);
            case "import" -> handleImport(ctx);
            default -> ctx.replyError("Unknown subcommand.");
        }
    }

    private PlaylistData getFav(CommandContext ctx) {
        return PlaylistManager.getInstance().getFavorites(ctx.getUser().getId());
    }

    private void handleAdd(CommandContext ctx) {
        PlaylistData fav = getFav(ctx);
        OptionMapping queryOpt = ctx.getOption("query");

        if (queryOpt == null) {
            MusicManager mm = ctx.getMusicManager();
            AudioTrack current = mm != null ? mm.getPlayer().getPlayingTrack() : null;
            if (current == null) {
                ctx.replyError("Nothing is playing. Provide a URL/search or play a track first.");
                return;
            }
            PlaylistTrack pt = PlaylistCommand.audioTrackToPlaylistTrack(current);
            String result = PlaylistManager.getInstance().addTrack(ctx.getUser().getId(), fav.getId(), pt);
            switch (result) {
                case "ok" -> ctx.replySuccess("Added **" + current.getInfo().title + "** to favorites");
                case "duplicate" -> ctx.replyError("Already in favorites.");
                case "limit" -> ctx.replyError("Favorites limit reached (" + PlaylistManager.MAX_FAVORITES + ").");
                default -> ctx.replyError("Failed to add.");
            }
        } else {
            ctx.deferReply();
            String query = queryOpt.getAsString();
            if (!query.startsWith("http") && !query.contains(":")) query = "ytsearch:" + query;
            final String finalQuery = query;
            PlayerManager.getInstance().loadItemOrdered(ctx.getGuild(), finalQuery, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    PlaylistTrack pt = PlaylistCommand.audioTrackToPlaylistTrack(track);
                    String result = PlaylistManager.getInstance().addTrack(ctx.getUser().getId(), fav.getId(), pt);
                    String msg = result.equals("ok")
                            ? EmbedHelper.MSG_SUCCESS + " Added **" + track.getInfo().title + "** to favorites"
                            : EmbedHelper.MSG_ERROR + " " + (result.equals("duplicate") ? "Already in favorites." : "Failed.");
                    ctx.getEvent().getHook().sendMessage(msg).queue();
                }
                @Override public void playlistLoaded(AudioPlaylist pl) {
                    if (pl.isSearchResult()) {
                        if (!pl.getTracks().isEmpty()) trackLoaded(pl.getTracks().get(0));
                        else noMatches();
                    } else {
                        int added = 0;
                        for (AudioTrack track : pl.getTracks()) {
                            PlaylistTrack pt = PlaylistCommand.audioTrackToPlaylistTrack(track);
                            if (PlaylistManager.getInstance().addTrack(ctx.getUser().getId(), fav.getId(), pt).equals("ok")) {
                                added++;
                            }
                        }
                        ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Added **" + added + "** tracks from **" + pl.getName() + "** to favorites").queue();
                    }
                }
                @Override public void noMatches() {
                    ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " No matches found.").queue();
                }
                @Override public void loadFailed(FriendlyException e) {
                    ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Load failed.").queue();
                }
            });
        }
    }

    private void handleRemove(CommandContext ctx) {
        PlaylistData fav = getFav(ctx);
        int index = ctx.getOption("index").getAsInt() - 1;
        PlaylistTrack removed = PlaylistManager.getInstance().removeTrack(ctx.getUser().getId(), fav.getId(), index);
        if (removed != null) ctx.replySuccess("Removed **" + removed.getTitle() + "** from favorites");
        else ctx.replyError("Invalid index.");
    }

    private void handleList(CommandContext ctx) {
        PlaylistData fav = getFav(ctx);
        OptionMapping pageOpt = ctx.getOption("page");
        int page = pageOpt != null ? Math.max(1, (int) pageOpt.getAsLong()) : 1;
        var embed = EmbedHelper.createPlaylistTracksEmbed(fav, page);
        int maxPages = Math.max(1, (int) Math.ceil(fav.getTracks().size() / 10.0));
        ctx.getEvent().replyEmbeds(embed).setComponents(
                net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                        EmbedHelper.createPaginationButtons("favlist_" + ctx.getUser().getId(), page, maxPages)
                )).queue();
    }

    private void handleInfo(CommandContext ctx) {
        PlaylistData fav = getFav(ctx);
        ctx.getEvent().replyEmbeds(EmbedHelper.createPlaylistInfoEmbed(fav)).queue();
    }

    private void handleClear(CommandContext ctx) {
        ctx.getEvent().reply(EmbedHelper.MSG_ERROR + " Clear all favorites? This cannot be undone.")
                .addComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                        net.dv8tion.jda.api.components.buttons.Button.danger("fav_clearconfirm_" + ctx.getUser().getId(), "Clear All"),
                        net.dv8tion.jda.api.components.buttons.Button.secondary("fav_clearcancel_" + ctx.getUser().getId(), "Cancel")
                )).queue();
    }

    private void handlePlay(CommandContext ctx) {
        PlaylistData fav = getFav(ctx);
        if (fav.getTracks().isEmpty()) { ctx.replyError("Your favorites are empty."); return; }
        if (!ensureVoice(ctx)) return;
        ctx.deferReply();
        enqueueFavorites(ctx, fav, false, false);
    }

    private void handleInstant(CommandContext ctx) {
        PlaylistData fav = getFav(ctx);
        if (fav.getTracks().isEmpty()) { ctx.replyError("Your favorites are empty."); return; }
        if (!ensureVoice(ctx)) return;
        ctx.deferReply();
        ctx.getMusicManager().getScheduler().stop();
        enqueueFavorites(ctx, fav, true, false);
    }

    private void handleShuffle(CommandContext ctx) {
        PlaylistData fav = getFav(ctx);
        if (fav.getTracks().isEmpty()) { ctx.replyError("Your favorites are empty."); return; }
        if (!ensureVoice(ctx)) return;
        ctx.deferReply();

        enqueueFavorites(ctx, fav, false, true);
    }

    private void handleExport(CommandContext ctx) {
        PlaylistData fav = getFav(ctx);
        ctx.deferReply();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("version", 1);
            root.put("name", "favorites");
            ArrayNode tracksArr = root.putArray("tracks");
            for (PlaylistTrack t : fav.getTracks()) {
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
            ctx.getEvent().getHook().sendMessage("Exported favorites")
                    .addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(json, "favorites.json")).queue();
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

                if (!root.has("tracks") || !root.get("tracks").isArray()) {
                    ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Invalid file format.").queue();
                    return;
                }

                ArrayNode tracksNode = (ArrayNode) root.get("tracks");
                List<PlaylistTrack> tracks = new ArrayList<>();
                for (JsonNode tn : tracksNode) {
                    if (tracks.size() >= PlaylistManager.MAX_FAVORITES) break;
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

                PlaylistData imported = PlaylistManager.getInstance().importFavorites(ctx.getUser().getId(), tracks);
                ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_SUCCESS + " Imported " + imported.getTracks().size() + " favorites").queue();
            } catch (Exception e) {
                logger.error("Import failed", e);
                ctx.getEvent().getHook().sendMessage(EmbedHelper.MSG_ERROR + " Failed to parse file.").queue();
            }
        });
    }

    // ======================== HELPERS ========================

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
        // Connect to voice channel if not already connected
        var botStateCheck = ctx.getGuild().getSelfMember().getVoiceState();
        if (botStateCheck == null || !botStateCheck.inAudioChannel()) {
            if (!com.discord.musicbot.commands.framework.CommandRegistry.checkBotVoicePermissions(ctx.getGuild().getSelfMember(), userState.getChannel(), ctx)) {
                return false;
            }
            ctx.getMusicManager().connectToVoiceChannel(userState.getChannel());
        }
        return true;
    }

    private void enqueueFavorites(CommandContext ctx, PlaylistData fav, boolean isInstant, boolean shuffle) {
        MusicManager mm = ctx.getMusicManager();
        mm.setNowPlayingChannel(ctx.getChannel().getId());
        List<PlaylistTrack> tracks = new ArrayList<>(fav.getTracks());
        if (shuffle) Collections.shuffle(tracks);

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
            track.setUserData("{\"requester\":\"" + ctx.getUser().getId() + "\"}");
            mm.getScheduler().queue(track);
            loaded++;
        }

        mm.updateNowPlayingMessage();
        String action = shuffle ? "Shuffle playing" : (isInstant || mm.getPlayer().getPlayingTrack() == null ? "Playing" : "Queued");
        String msg = EmbedHelper.MSG_SUCCESS + " " + action + " **favorites** • `" + loaded + " tracks`";
        ctx.getEvent().getHook().sendMessage(msg).queue();
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Manage your favorites")
                .addSubcommands(
                        new SubcommandData("add", "Add a track to favorites")
                                .addOption(OptionType.STRING, "query", "Search query or URL", false, true),
                        new SubcommandData("remove", "Remove a favorite by index")
                                .addOption(OptionType.INTEGER, "index", "Track number", true),
                        new SubcommandData("list", "View your favorites")
                                .addOption(OptionType.INTEGER, "page", "Page number", false),
                        new SubcommandData("info", "View favorites info"),
                        new SubcommandData("clear", "Clear all favorites"),
                        new SubcommandData("play", "Play your favorites"),
                        new SubcommandData("instant", "Force-play favorites immediately"),
                        new SubcommandData("shuffle", "Shuffle-play your favorites"),
                        new SubcommandData("export", "Export favorites as JSON"),
                        new SubcommandData("import", "Import favorites from JSON")
                                .addOption(OptionType.ATTACHMENT, "file", "JSON file", true)
                );
    }
}
