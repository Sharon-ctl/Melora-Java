package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.commands.playlist.PlaylistCommand;
import com.discord.musicbot.data.PlaylistManager;
import com.discord.musicbot.data.model.PlaylistData;
import com.discord.musicbot.data.model.PlaylistTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.List;

public class InteractionHandler {

    public static void handleButton(ButtonInteractionEvent event) {
        try {
            if (event.getGuild() == null) return;
            String id = event.getComponentId();

            // Playlist/Favorites/Settings buttons don't need MusicManager
            if (id.startsWith("pl_") || id.startsWith("fav_") || id.startsWith("import_")
                    || id.startsWith("pllist_") || id.startsWith("pltracks_") || id.startsWith("favlist_") || id.startsWith("lyrics_") || id.startsWith("history_")) {
                handlePlaylistButtons(event, id);
                return;
            } else if (id.startsWith("setting_")) {
                handleSettingsButtons(event, id);
                return;
            }

            MusicManager manager = PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong());
            if (manager == null) {
                replyError(event, "No active music session.");
                return;
            }

            if (id.startsWith("queue_")) {
                handleQueuePagination(event, id, manager);
            } else if (id.startsWith("np_")) {
                handleNowPlayingButtons(event, id, manager);
            } else if (id.startsWith("vote_")) {
                com.discord.musicbot.audio.VoteManager.getInstance().handleVoteButton(event, id.equals("vote_yes"));
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(InteractionHandler.class).error("Error in handleButton", e);
            replyError(event, "An error occurred processing that button.");
        }
    }

    private static void handleSettingsButtons(ButtonInteractionEvent event, String id) {
        if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
            replyError(event, "You must be an Administrator to change these settings.");
            return;
        }

        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(event.getGuild().getId());

        switch (id) {
            case "setting_status":
                settings.setUpdateVcStatus(!settings.isUpdateVcStatus());
                if (com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()) != null) {
                    com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()).updateVoiceChannelStatus();
                }
                break;
            case "setting_announce":
                boolean newAnnounce = !settings.isAnnounceTracks();
                settings.setAnnounceTracks(newAnnounce);
                if (!newAnnounce && com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()) != null) {
                    com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()).deleteNowPlayingMessage();
                }
                break;
            case "setting_247":
                boolean new247 = !settings.isMode247();
                settings.setMode247(new247);
                if (new247 != com.discord.musicbot.data.DatabaseManager.getInstance().is247(event.getGuild().getId())) {
                    com.discord.musicbot.data.DatabaseManager.getInstance().toggle247(event.getGuild().getId());
                }
                if (com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()) != null) {
                    com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()).set247(new247);
                    com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()).updateNowPlayingMessage();
                }
                break;
            case "setting_autoplay":
                boolean newAp = !settings.isAutoplay();
                settings.setAutoplay(newAp);
                if (com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()) != null) {
                    com.discord.musicbot.audio.TrackScheduler scheduler = com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()).getScheduler();
                    if (scheduler.isAutoPlay() != newAp) {
                        scheduler.toggleAutoplay();
                    }
                    com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()).updateNowPlayingMessage();
                }
                break;
            case "setting_random":
                boolean newRan = !settings.isRandomPlay();
                settings.setRandomPlay(newRan);
                if (com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()) != null) {
                    com.discord.musicbot.audio.TrackScheduler scheduler = com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()).getScheduler();
                    scheduler.setRandomPlay(newRan);
                    com.discord.musicbot.audio.PlayerManager.getInstance().getMusicManager(event.getGuild().getIdLong()).updateNowPlayingMessage();
                }
                break;
            case "setting_dj":
                settings.setDjMode(!settings.isDjMode());
                break;
        }

        com.discord.musicbot.data.GuildSettingsManager.getInstance().markDirty();

        event.editComponents(EmbedHelper.createSettingsContainer(settings)).useComponentsV2().queue();
    }

    private static void handlePlaylistButtons(ButtonInteractionEvent event, String id) {
        String userId = event.getUser().getId();

        // Delete confirmation
        if (id.startsWith("pl_delconfirm_")) {
            String[] parts = id.split("_", 4);
            if (parts.length < 4 || !parts[3].equals(userId)) {
                replyError(event, "This isn't your confirmation.");
                return;
            }
            String playlistId = parts[2];
            boolean deleted = PlaylistManager.getInstance().deletePlaylist(userId, playlistId);
            event.editComponents(Container.of(
                TextDisplay.of(deleted ? EmbedHelper.MSG_SUCCESS + " Playlist deleted." : EmbedHelper.MSG_ERROR + " Deletion failed.")
            ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            return;
        }
        if (id.startsWith("pl_delcancel_")) {
            if (!id.endsWith(userId)) { replyError(event, "Not yours."); return; }
            event.editComponents(Container.of(
                TextDisplay.of("Cancelled.")
            ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            return;
        }

        // Favorites clear confirmation
        if (id.startsWith("fav_clearconfirm_")) {
            if (!id.endsWith(userId)) { replyError(event, "Not yours."); return; }
            int cleared = PlaylistManager.getInstance().clearFavorites(userId);
            event.editComponents(Container.of(
                TextDisplay.of(EmbedHelper.MSG_SUCCESS + " Cleared " + cleared + " favorites.")
            ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            return;
        }
        if (id.startsWith("fav_clearcancel_")) {
            if (!id.endsWith(userId)) { replyError(event, "Not yours."); return; }
            event.editComponents(Container.of(
                TextDisplay.of("Cancelled.")
            ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            return;
        }

        // Import conflict resolution
        if (id.startsWith("import_replace_")) {
            if (!id.endsWith(userId)) { replyError(event, "Not yours."); return; }
            var importData = PlaylistCommand.ImportCache.get(userId);
            if (importData == null) { event.editComponents(Container.of(TextDisplay.of(EmbedHelper.MSG_ERROR + " Import expired.")).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue(); return; }
            PlaylistData existing = PlaylistManager.getInstance().findPlaylistByName(userId, importData.name());
            if (existing != null) {
                PlaylistManager.getInstance().replacePlaylist(userId, existing.getId(), importData.tracks());
                event.editComponents(Container.of(
                    TextDisplay.of(EmbedHelper.MSG_SUCCESS + " Replaced **" + importData.name() + "** with " + importData.tracks().size() + " tracks")
                ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            } else {
                event.editComponents(Container.of(
                    TextDisplay.of(EmbedHelper.MSG_ERROR + " Original playlist not found.")
                ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            }
            return;
        }
        if (id.startsWith("import_rename_")) {
            if (!id.endsWith(userId)) { replyError(event, "Not yours."); return; }
            var importData = PlaylistCommand.ImportCache.get(userId);
            if (importData == null) { event.editComponents(Container.of(TextDisplay.of(EmbedHelper.MSG_ERROR + " Import expired.")).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue(); return; }
            // Auto-rename with suffix
            String newName = importData.name();
            for (int i = 2; i <= 99; i++) {
                String candidate = newName + " (" + i + ")";
                if (PlaylistManager.getInstance().findPlaylistByName(userId, candidate) == null) {
                    newName = candidate; break;
                }
            }
            PlaylistData imported = PlaylistManager.getInstance().importPlaylist(userId, newName, importData.tracks());
            if (imported != null) {
                event.editComponents(Container.of(
                    TextDisplay.of(EmbedHelper.MSG_SUCCESS + " Imported as **" + imported.getName() + "** with " + imported.getTracks().size() + " tracks")
                ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            } else {
                event.editComponents(Container.of(
                    TextDisplay.of(EmbedHelper.MSG_ERROR + " Import failed.")
                ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            }
            return;
        }
        if (id.startsWith("import_cancel_")) {
            if (!id.endsWith(userId)) { replyError(event, "Not yours."); return; }
            PlaylistCommand.ImportCache.get(userId); // consume and discard
            event.editComponents(Container.of(
                TextDisplay.of("Import cancelled.")
            ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            return;
        }

        // Pagination for playlist list, playlist tracks, favorites list, lyrics, history
        if (id.startsWith("pllist_") || id.startsWith("pltracks_") || id.startsWith("favlist_") || id.startsWith("lyrics_") || id.startsWith("history_")) {
            handlePlaylistPagination(event, id);
        }
    }

    private static void handlePlaylistPagination(ButtonInteractionEvent event, String id) {
        // Format: prefix_action_currentPage  (for pllist)
        // Format: pltracks_playlistId_userId_action_currentPage
        // Format: favlist_userId_action_currentPage
        String[] parts = id.split("_");

        try {
            if (id.startsWith("pllist_") && parts.length >= 4) {
                String action = parts[1];
                int currentPage = Integer.parseInt(parts[2]);
                String ownerUserId = event.getUser().getId();
                List<PlaylistData> playlists = PlaylistManager.getInstance().getPlaylists(ownerUserId);
                int maxPages = Math.max(1, (int) Math.ceil(playlists.size() / 10.0));
                int newPage = calcNewPage(action, currentPage, maxPages);
                var container = EmbedHelper.createPlaylistListContainer(playlists, newPage, "pllist");
                event.editComponents(container).useComponentsV2().queue();
            } else if (id.startsWith("pltracks_") && parts.length >= 5) {
                String playlistId = parts[1];
                String ownerUserId = parts[2];
                String action = parts[3];
                int currentPage = Integer.parseInt(parts[4]);
                PlaylistData pl = PlaylistManager.getInstance().getPlaylist(ownerUserId, playlistId);
                if (pl == null) { replyError(event, "Playlist not found."); return; }
                int maxPages = Math.max(1, (int) Math.ceil(pl.getTracks().size() / 10.0));
                int newPage = calcNewPage(action, currentPage, maxPages);
                var container = EmbedHelper.createPlaylistTracksContainer(pl, newPage, "pltracks_" + playlistId + "_" + ownerUserId);
                event.editComponents(container).useComponentsV2().queue();
            } else if (id.startsWith("favlist_") && parts.length >= 4) {
                String ownerUserId = parts[1];
                String action = parts[2];
                int currentPage = Integer.parseInt(parts[3]);
                PlaylistData fav = PlaylistManager.getInstance().getFavorites(ownerUserId);
                int maxPages = Math.max(1, (int) Math.ceil(fav.getTracks().size() / 10.0));
                int newPage = calcNewPage(action, currentPage, maxPages);
                var container = EmbedHelper.createPlaylistTracksContainer(fav, newPage, "favlist_" + ownerUserId);
                event.editComponents(container).useComponentsV2().queue();
            } else if (id.startsWith("lyrics_") && parts.length == 4) {
                String action = parts[1];
                String lyricsId = parts[2];
                int currentPage = Integer.parseInt(parts[3]);
                com.discord.musicbot.lyrics.LyricsCache.LyricsData data = com.discord.musicbot.lyrics.LyricsCache.get(lyricsId);
                if (data == null) {
                    replyError(event, "This lyrics session has expired. Please run /lyrics again.");
                    return;
                }
                int maxPages = data.pages.size();
                int newPage = calcNewPage(action, currentPage, maxPages);
                var container = EmbedHelper.createLyricsContainer(lyricsId, data.query, data.pages, newPage, data.source, data.isLive);
                event.editComponents(container).useComponentsV2().queue();
            } else if (id.startsWith("history_") && parts.length == 3) {
                String action = parts[1];
                int currentPage = Integer.parseInt(parts[2]);
                String userId = event.getUser().getId();
                java.util.List<com.discord.musicbot.data.HistoryManager.HistoryEntry> history = com.discord.musicbot.data.HistoryManager.getInstance().getUserHistory(userId);
                int maxPages = Math.max(1, (int) Math.ceil(history.size() / 10.0));
                int newPage = calcNewPage(action, currentPage, maxPages);
                var container = EmbedHelper.createHistoryContainer(history, newPage);
                event.editComponents(container).useComponentsV2().queue();
            }
        } catch (NumberFormatException e) {
            replyError(event, "Invalid pagination data.");
        }
    }

    private static int calcNewPage(String action, int currentPage, int maxPages) {
        return switch (action) {
            case "first" -> 1;
            case "prev" -> Math.max(1, currentPage - 1);
            case "next" -> Math.min(maxPages, currentPage + 1);
            case "last" -> maxPages;
            default -> currentPage;
        };
    }

    private static void handleQueuePagination(ButtonInteractionEvent event, String id, MusicManager manager) {
        String[] parts = id.split("_");
        if (parts.length < 3) return;

        String filterUserId = null;
        String action;
        int currentPage;
        
        try {
            if (parts.length == 4) {
                filterUserId = parts[1];
                action = parts[2];
                currentPage = Integer.parseInt(parts[3]);
            } else {
                action = parts[1];
                currentPage = Integer.parseInt(parts[2]);
            }
    
            final String finalFilterUserId = filterUserId;
            long filteredSize = filterUserId == null ? manager.getScheduler().getQueueSize() : 
                manager.getScheduler().getQueue().stream().filter(t -> {
                    Object ud = t.getUserData();
                    String uid = "";
                    if (ud instanceof net.dv8tion.jda.api.entities.User u) uid = u.getId();
                    else if (ud instanceof String s) {
                        if (s.contains("\"requester\":\"")) uid = s.split("\"requester\":\"")[1].split("\"")[0];
                        else uid = s;
                    }
                    return uid.equals(finalFilterUserId);
                }).count();
    
            int maxPages = Math.max(1, (int) Math.ceil(filteredSize / 10.0));
            int newPage = calcNewPage(action, currentPage, maxPages);
    
            if (filteredSize == 0) {
                replyError(event, "No tracks in the queue.");
                return;
            }
            var container = EmbedHelper.createQueueContainer(manager, newPage, filterUserId);
            event.editComponents(container).useComponentsV2().queue();
        } catch (NumberFormatException e) {
            replyError(event, "Invalid pagination data.");
        }
    }

    private static void handleNowPlayingButtons(ButtonInteractionEvent event, String id, MusicManager manager) {
        // np_fav doesn't need voice check
        if (id.equals("np_fav")) {
            handleFavoriteButton(event, manager);
            return;
        }

        if (manager.getPlayer().getPlayingTrack() == null) {
            replyError(event, "No track is currently playing.");
            return;
        }

        // Voice state check
        var userState = event.getMember().getVoiceState();
        var botState = event.getGuild().getSelfMember().getVoiceState();
        if (userState == null || !userState.inAudioChannel() || botState == null || !botState.inAudioChannel() || !userState.getChannel().equals(botState.getChannel())) {
            replyError(event, "You must be in the same voice channel to use these buttons!");
            return;
        }

        // Hardcore DJ Permission Check for playback modifications
        if (!id.equals("np_queue")) {
            if (!CommandRegistry.checkDjRole(event.getGuild(), event.getMember(), manager, event)) {
                return; // checkDjRole automatically sends the error reply and logs the security event
            }
        }

        switch (id) {
            case "np_pause":
                if (manager.getScheduler().isPaused()) {
                    manager.getScheduler().resume();
                } else {
                    manager.getScheduler().pause();
                }
                var pauseComponents = manager.createNowPlayingContainer();
                if (pauseComponents != null && !pauseComponents.isEmpty()) {
                    event.editComponents(pauseComponents)
                            .useComponentsV2()
                            .setAllowedMentions(java.util.Collections.emptyList())
                            .queue();
                } else {
                    replySuccess(event, "Playback is now **" + (manager.getScheduler().isPaused() ? "Paused" : "Resumed") + "**");
                }
                break;
            case "np_skip":
                if (manager.getScheduler().getQueueSize() == 0 && !manager.getScheduler().getAutoplay() && !manager.getScheduler().isRandomPlay() && manager.getScheduler().getLoopMode() != com.discord.musicbot.audio.TrackScheduler.LoopMode.TRACK) {
                    replyError(event, "No tracks in queue to skip to.");
                } else {
                    event.deferEdit().queue();
                    manager.getScheduler().nextTrack();
                }
                break;
            case "np_previous":
                if (!manager.getScheduler().hasHistory()) {
                    replyError(event, "No previous track in history.");
                } else {
                    event.deferEdit().queue();
                    manager.getScheduler().previousTrack();
                }
                break;

            case "np_shuffle":
                manager.getScheduler().shuffle();
                replySuccess(event, "Queue shuffled!");
                manager.updateNowPlayingMessage();
                break;
            case "np_loop":
                var loopMode = manager.getScheduler().cycleLoopMode();
                var loopComponents = manager.createNowPlayingContainer();
                if (loopComponents != null && !loopComponents.isEmpty()) {
                    event.editComponents(loopComponents)
                            .useComponentsV2()
                            .setAllowedMentions(java.util.Collections.emptyList())
                            .queue();
                } else {
                    replySuccess(event, "Loop mode set to: **" + loopMode.name() + "** (Will apply when songs start playing)");
                }
                break;
            case "np_queue":
                if (manager.getScheduler().getQueueSize() == 0) {
                    replyError(event, "No tracks in the queue.");
                    break;
                }
                event.replyComponents(EmbedHelper.createQueueContainer(manager, 1, null))
                        .useComponentsV2()
                        .setEphemeral(true)
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .queue();
                break;
            case "np_voldown":
                int newVolDown = Math.max(1, manager.getPlayer().getVolume() - 10);
                manager.getPlayer().setVolume(newVolDown);
                var volDownComponents = manager.createNowPlayingContainer();
                if (volDownComponents != null && !volDownComponents.isEmpty()) {
                    event.editComponents(volDownComponents)
                            .useComponentsV2()
                            .setAllowedMentions(java.util.Collections.emptyList())
                            .queue();
                } else {
                    replySuccess(event, "Volume decreased to **" + newVolDown + "%**");
                }
                break;
            case "np_volup":
                int newVolUp = Math.min(200, manager.getPlayer().getVolume() + 10);
                manager.getPlayer().setVolume(newVolUp);
                var volUpComponents = manager.createNowPlayingContainer();
                if (volUpComponents != null && !volUpComponents.isEmpty()) {
                    event.editComponents(volUpComponents)
                            .useComponentsV2()
                            .setAllowedMentions(java.util.Collections.emptyList())
                            .queue();
                } else {
                    replySuccess(event, "Volume increased to **" + newVolUp + "%**");
                }
                break;
            case "np_stop":
                replySuccess(event, "Stopped playback and cleared the queue.");
                manager.getScheduler().stop();
                break;
        }
    }

    private static void handleFavoriteButton(ButtonInteractionEvent event, MusicManager manager) {
        AudioTrack track = manager.getPlayer().getPlayingTrack();
        if (track == null) {
            replyError(event, "Nothing is playing.");
            return;
        }
        String userId = event.getUser().getId();
        PlaylistData fav = PlaylistManager.getInstance().getFavorites(userId);
        PlaylistTrack pt = PlaylistCommand.audioTrackToPlaylistTrack(track);
        String result = PlaylistManager.getInstance().addTrack(userId, fav.getId(), pt);
        switch (result) {
            case "ok" -> {
                replySuccess(event, "Added to favorites!");
                
                // DJ Economy: Award point to the requester if it's someone else
                Object ud = track.getUserData();
                String requesterId = null;
                if (ud instanceof net.dv8tion.jda.api.entities.User u) {
                    requesterId = u.getId();
                } else if (ud instanceof String s && s.contains("\"requester\":\"")) {
                    requesterId = s.split("\"requester\":\"")[1].split("\"")[0];
                } else if (ud instanceof String s && s.matches("\\d+")) {
                    requesterId = s;
                }
                
                if (requesterId != null && !requesterId.equals(userId)) {
                    com.discord.musicbot.data.StatsManager.getInstance().addDjPoints(requesterId, 1);
                }
            }
            case "duplicate" -> replyError(event, "Already in favorites.");
            case "limit" -> replyError(event, "Favorites limit reached.");
            default -> replyError(event, "Failed to add.");
        }
    }

    public static void handleSelectMenu(StringSelectInteractionEvent event) {
        try {
            if (event.getComponentId().equals("help_menu")) {
                String category = event.getValues().get(0);
                String prefix = "/";
                var container = EmbedHelper.createHelpContainer(category, prefix, event.getJDA());
                event.editComponents(container).useComponentsV2().queue();
            } else if (event.getComponentId().startsWith("search_")) {
                String[] parts = event.getComponentId().split("_", 3);
                if (parts.length < 3) return;
                String searchId = parts[1];
                String userId = parts[2];
                
                if (!event.getUser().getId().equals(userId)) {
                    replyError(event, "This search menu is not for you!");
                    return;
                }
                
                List<AudioTrack> results = com.discord.musicbot.commands.music.SearchCommand.searchCache.get(searchId);
                if (results == null) {
                    replyError(event, "This search session has expired.");
                    return;
                }
                
                int index = Integer.parseInt(event.getValues().get(0));
                AudioTrack selected = results.get(index).makeClone();
                selected.setUserData("{\"requester\":\"" + userId + "\"}");
                
                MusicManager manager = PlayerManager.getInstance().getMusicManager(event.getGuild());
                var userState = event.getMember().getVoiceState();
                if (userState != null && userState.inAudioChannel() && !event.getGuild().getAudioManager().isConnected()) {
                    manager.connectToVoiceChannel(userState.getChannel());
                }
                
                manager.getScheduler().queue(selected);
                manager.updateNowPlayingMessage();
                
                event.editComponents(Container.of(
                    TextDisplay.of(EmbedHelper.MSG_SUCCESS + " Added **[" + EmbedHelper.escapeMarkdown(selected.getInfo().title) + "](" + selected.getInfo().uri + ")** to the queue.")
                ).withAccentColor(EmbedHelper.COLOR_MAIN))
                    .useComponentsV2()
                    .queue();
            } else if (event.getComponentId().startsWith("clear_data_")) {
                String userId = event.getComponentId().substring("clear_data_".length());
                if (!event.getUser().getId().equals(userId)) {
                    replyError(event, "This menu is not for you!");
                    return;
                }
                List<String> values = event.getValues();
                StringBuilder deletedItems = new StringBuilder();
                boolean deleteAll = values.contains("all");

                if (deleteAll || values.contains("history")) {
                    com.discord.musicbot.data.HistoryManager.getInstance().clearHistory(userId);
                    deletedItems.append("• Listening History\n");
                }
                if (deleteAll || values.contains("playlists")) {
                    com.discord.musicbot.data.PlaylistManager.getInstance().clearPlaylists(userId);
                    deletedItems.append("• Playlists\n");
                }
                if (deleteAll || values.contains("favorites")) {
                    com.discord.musicbot.data.PlaylistManager.getInstance().clearFavorites(userId);
                    deletedItems.append("• Favorites\n");
                }
                if (deleteAll || values.contains("stats")) {
                    com.discord.musicbot.data.StatsManager.getInstance().clearStats(userId);
                    deletedItems.append("• Stats & Wrapped Data\n");
                }
                if (deleteAll || values.contains("excludes")) {
                    com.discord.musicbot.data.UserExcludeManager.getInstance().clearExcludes(userId);
                    deletedItems.append("• Excluded Users\n");
                }

                event.editComponents(Container.of(
                    TextDisplay.of(EmbedHelper.MSG_SUCCESS + " **Successfully cleared selected data:**\n\n" + deletedItems.toString())
                ).withAccentColor(EmbedHelper.COLOR_MAIN)).useComponentsV2().queue();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(InteractionHandler.class).error("Error in handleSelectMenu", e);
            replyError(event, "An error occurred processing the menu.");
        }
    }

    private static void replyError(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String msg) {
        String content = msg.startsWith(EmbedHelper.MSG_ERROR) ? msg : EmbedHelper.MSG_ERROR + " " + msg;
        var container = Container.of(TextDisplay.of(content)).withAccentColor(EmbedHelper.COLOR_MAIN);
        if (event.isAcknowledged()) event.getHook().sendMessageComponents(container).useComponentsV2().setEphemeral(true).queue();
        else event.replyComponents(container).useComponentsV2().setEphemeral(true).queue();
    }

    private static void replySuccess(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, String msg) {
        String content = msg.startsWith(EmbedHelper.MSG_SUCCESS) ? msg : EmbedHelper.MSG_SUCCESS + " " + msg;
        var container = Container.of(TextDisplay.of(content)).withAccentColor(EmbedHelper.COLOR_MAIN);
        if (event.isAcknowledged()) event.getHook().sendMessageComponents(container).useComponentsV2().setEphemeral(true).queue();
        else event.replyComponents(container).useComponentsV2().setEphemeral(true).queue();
    }
}
