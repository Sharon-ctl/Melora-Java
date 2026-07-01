package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.commands.playlist.PlaylistCommand;
import com.discord.musicbot.data.PlaylistManager;
import com.discord.musicbot.data.model.PlaylistData;
import com.discord.musicbot.data.model.PlaylistTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

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
                event.reply("No active music session.").setEphemeral(true).queue();
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
            if (!event.isAcknowledged()) {
                event.reply("An error occurred processing that button.").setEphemeral(true).queue();
            }
        }
    }

    private static void handleSettingsButtons(ButtonInteractionEvent event, String id) {
        if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
            event.reply("You must be an Administrator to change these settings.").setEphemeral(true).queue();
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

        event.deferEdit().queue(v -> {
            event.getHook().editOriginalEmbeds(EmbedHelper.createSettingsEmbed(settings))
                    .setComponents(EmbedHelper.createSettingsComponents(settings))
                    .queue();
        });
    }

    private static void handlePlaylistButtons(ButtonInteractionEvent event, String id) {
        String userId = event.getUser().getId();

        // Delete confirmation
        if (id.startsWith("pl_delconfirm_")) {
            String[] parts = id.split("_", 4);
            if (parts.length < 4 || !parts[3].equals(userId)) {
                event.reply("This isn't your confirmation.").setEphemeral(true).queue();
                return;
            }
            String playlistId = parts[2];
            boolean deleted = PlaylistManager.getInstance().deletePlaylist(userId, playlistId);
            event.editMessage(deleted ? EmbedHelper.MSG_SUCCESS + " Playlist deleted." : EmbedHelper.MSG_ERROR + " Deletion failed.")
                    .setComponents().queue();
            return;
        }
        if (id.startsWith("pl_delcancel_")) {
            if (!id.endsWith(userId)) { event.reply("Not yours.").setEphemeral(true).queue(); return; }
            event.editMessage("Cancelled.").setComponents().queue();
            return;
        }

        // Favorites clear confirmation
        if (id.startsWith("fav_clearconfirm_")) {
            if (!id.endsWith(userId)) { event.reply("Not yours.").setEphemeral(true).queue(); return; }
            int cleared = PlaylistManager.getInstance().clearFavorites(userId);
            event.editMessage(EmbedHelper.MSG_SUCCESS + " Cleared " + cleared + " favorites.").setComponents().queue();
            return;
        }
        if (id.startsWith("fav_clearcancel_")) {
            if (!id.endsWith(userId)) { event.reply("Not yours.").setEphemeral(true).queue(); return; }
            event.editMessage("Cancelled.").setComponents().queue();
            return;
        }

        // Import conflict resolution
        if (id.startsWith("import_replace_")) {
            if (!id.endsWith(userId)) { event.reply("Not yours.").setEphemeral(true).queue(); return; }
            var importData = PlaylistCommand.ImportCache.get(userId);
            if (importData == null) { event.editMessage("Import expired.").setComponents().queue(); return; }
            PlaylistData existing = PlaylistManager.getInstance().findPlaylistByName(userId, importData.name());
            if (existing != null) {
                PlaylistManager.getInstance().replacePlaylist(userId, existing.getId(), importData.tracks());
                event.editMessage(EmbedHelper.MSG_SUCCESS + " Replaced **" + importData.name() + "** with " + importData.tracks().size() + " tracks").setComponents().queue();
            } else {
                event.editMessage(EmbedHelper.MSG_ERROR + " Original playlist not found.").setComponents().queue();
            }
            return;
        }
        if (id.startsWith("import_rename_")) {
            if (!id.endsWith(userId)) { event.reply("Not yours.").setEphemeral(true).queue(); return; }
            var importData = PlaylistCommand.ImportCache.get(userId);
            if (importData == null) { event.editMessage("Import expired.").setComponents().queue(); return; }
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
                event.editMessage(EmbedHelper.MSG_SUCCESS + " Imported as **" + imported.getName() + "** with " + imported.getTracks().size() + " tracks").setComponents().queue();
            } else {
                event.editMessage(EmbedHelper.MSG_ERROR + " Import failed.").setComponents().queue();
            }
            return;
        }
        if (id.startsWith("import_cancel_")) {
            if (!id.endsWith(userId)) { event.reply("Not yours.").setEphemeral(true).queue(); return; }
            PlaylistCommand.ImportCache.get(userId); // consume and discard
            event.editMessage("Import cancelled.").setComponents().queue();
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
                MessageEmbed embed = EmbedHelper.createPlaylistListEmbed(playlists, newPage);
                event.deferEdit().queue(v -> event.getHook().editOriginalEmbeds(embed).setComponents(
                        net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                EmbedHelper.createPaginationButtons("pllist", newPage, maxPages)
                        )).queue());
            } else if (id.startsWith("pltracks_") && parts.length >= 5) {
                String playlistId = parts[1];
                String ownerUserId = parts[2];
                String action = parts[3];
                int currentPage = Integer.parseInt(parts[4]);
                PlaylistData pl = PlaylistManager.getInstance().getPlaylist(ownerUserId, playlistId);
                if (pl == null) { event.reply("Playlist not found.").setEphemeral(true).queue(); return; }
                int maxPages = Math.max(1, (int) Math.ceil(pl.getTracks().size() / 10.0));
                int newPage = calcNewPage(action, currentPage, maxPages);
                MessageEmbed embed = EmbedHelper.createPlaylistTracksEmbed(pl, newPage);
                event.deferEdit().queue(v -> event.getHook().editOriginalEmbeds(embed).setComponents(
                        net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                EmbedHelper.createPaginationButtons("pltracks_" + playlistId + "_" + ownerUserId, newPage, maxPages)
                        )).queue());
            } else if (id.startsWith("favlist_") && parts.length >= 4) {
                String ownerUserId = parts[1];
                String action = parts[2];
                int currentPage = Integer.parseInt(parts[3]);
                PlaylistData fav = PlaylistManager.getInstance().getFavorites(ownerUserId);
                int maxPages = Math.max(1, (int) Math.ceil(fav.getTracks().size() / 10.0));
                int newPage = calcNewPage(action, currentPage, maxPages);
                MessageEmbed embed = EmbedHelper.createPlaylistTracksEmbed(fav, newPage);
                event.deferEdit().queue(v -> event.getHook().editOriginalEmbeds(embed).setComponents(
                        net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                EmbedHelper.createPaginationButtons("favlist_" + ownerUserId, newPage, maxPages)
                        )).queue());
            } else if (id.startsWith("lyrics_") && parts.length == 4) {
                String action = parts[1];
                String lyricsId = parts[2];
                int currentPage = Integer.parseInt(parts[3]);
                com.discord.musicbot.lyrics.LyricsCache.LyricsData data = com.discord.musicbot.lyrics.LyricsCache.get(lyricsId);
                if (data == null) {
                    event.reply("This lyrics session has expired. Please run /lyrics again.").setEphemeral(true).queue();
                    return;
                }
                int maxPages = data.pages.size();
                int newPage = calcNewPage(action, currentPage, maxPages);
                MessageEmbed embed = EmbedHelper.createLyricsEmbed(data.query, data.pages, newPage, data.source, data.isLive);
                event.deferEdit().queue(v -> event.getHook().editOriginalEmbeds(embed).setComponents(
                        net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                EmbedHelper.createLyricsComponents(lyricsId, newPage, maxPages)
                        )).queue());
            } else if (id.startsWith("history_") && parts.length == 3) {
                String action = parts[1];
                int currentPage = Integer.parseInt(parts[2]);
                String userId = event.getUser().getId();
                java.util.List<com.discord.musicbot.data.HistoryManager.HistoryEntry> history = com.discord.musicbot.data.HistoryManager.getInstance().getUserHistory(userId);
                int maxPages = Math.max(1, (int) Math.ceil(history.size() / 10.0));
                int newPage = calcNewPage(action, currentPage, maxPages);
                MessageEmbed embed = EmbedHelper.createHistoryEmbed(history, newPage);
                event.deferEdit().queue(v -> event.getHook().editOriginalEmbeds(embed).setComponents(
                        net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                EmbedHelper.createPaginationButtons("history", newPage, maxPages)
                        )).queue());
            }
        } catch (NumberFormatException e) {
            event.reply("Invalid pagination data.").setEphemeral(true).queue();
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
    
            MessageEmbed embed = EmbedHelper.createQueueEmbed(manager, newPage, filterUserId);
            final int finalPage = newPage;
            final String finalPrefix = filterUserId == null ? "queue" : "queue_" + filterUserId;
            
            event.deferEdit().queue(v -> {
                event.getHook().editOriginalEmbeds(embed).setComponents(
                        net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                EmbedHelper.createPaginationButtons(finalPrefix, finalPage, maxPages)
                        )
                ).queue();
            });
        } catch (NumberFormatException e) {
            event.reply("Invalid pagination data.").setEphemeral(true).queue();
        }
    }

    private static void handleNowPlayingButtons(ButtonInteractionEvent event, String id, MusicManager manager) {
        // np_fav doesn't need voice check
        if (id.equals("np_fav")) {
            handleFavoriteButton(event, manager);
            return;
        }

        if (manager.getPlayer().getPlayingTrack() == null) {
            event.reply("No track is currently playing.").setEphemeral(true).queue();
            return;
        }

        // Voice state check
        var userState = event.getMember().getVoiceState();
        var botState = event.getGuild().getSelfMember().getVoiceState();
        if (userState == null || !userState.inAudioChannel() || botState == null || !botState.inAudioChannel() || !userState.getChannel().equals(botState.getChannel())) {
            event.reply("You must be in the same voice channel to use these buttons!").setEphemeral(true).queue();
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
                event.editMessageEmbeds(manager.createNowPlayingEmbed())
                        .setComponents(com.discord.musicbot.commands.framework.EmbedHelper.createNowPlayingComponents(manager))
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .queue();
                break;
            case "np_skip":
                if (manager.getScheduler().getQueueSize() == 0 && !manager.getScheduler().getAutoplay()) {
                    event.reply("No tracks in queue to skip to.").setEphemeral(true).queue();
                } else {
                    event.deferEdit().queue();
                    manager.getScheduler().nextTrack();
                }
                break;
            case "np_previous":
                if (!manager.getScheduler().hasHistory()) {
                    event.reply("No previous track in history.").setEphemeral(true).queue();
                } else {
                    event.deferEdit().queue();
                    manager.getScheduler().previousTrack();
                }
                break;

            case "np_shuffle":
                manager.getScheduler().shuffle();
                event.reply("Queue shuffled!").setEphemeral(true).queue();
                manager.updateNowPlayingMessage();
                break;
            case "np_loop":
                manager.getScheduler().cycleLoopMode();
                event.editMessageEmbeds(manager.createNowPlayingEmbed())
                        .setComponents(com.discord.musicbot.commands.framework.EmbedHelper.createNowPlayingComponents(manager))
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .queue();
                break;
            case "np_queue":
                event.replyEmbeds(EmbedHelper.createQueueEmbed(manager, 1, null))
                        .setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                                EmbedHelper.createPaginationButtons("queue", 1, Math.max(1, (int) Math.ceil(manager.getScheduler().getQueueSize() / 10.0)))
                        ))
                        .setEphemeral(true)
                        .queue();
                break;
            case "np_voldown":
                manager.getPlayer().setVolume(Math.max(1, manager.getPlayer().getVolume() - 10));
                event.editMessageEmbeds(manager.createNowPlayingEmbed())
                        .setComponents(com.discord.musicbot.commands.framework.EmbedHelper.createNowPlayingComponents(manager))
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .queue();
                break;
            case "np_volup":
                manager.getPlayer().setVolume(Math.min(200, manager.getPlayer().getVolume() + 10));
                event.editMessageEmbeds(manager.createNowPlayingEmbed())
                        .setComponents(com.discord.musicbot.commands.framework.EmbedHelper.createNowPlayingComponents(manager))
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .queue();
                break;
            case "np_stop":
                event.reply("Stopped playback and cleared the queue.").setEphemeral(true).queue();
                manager.getScheduler().stop();
                break;
        }
    }

    private static void handleFavoriteButton(ButtonInteractionEvent event, MusicManager manager) {
        AudioTrack track = manager.getPlayer().getPlayingTrack();
        if (track == null) {
            event.reply(EmbedHelper.MSG_ERROR + " Nothing is playing.").setEphemeral(true).queue();
            return;
        }
        String userId = event.getUser().getId();
        PlaylistData fav = PlaylistManager.getInstance().getFavorites(userId);
        PlaylistTrack pt = PlaylistCommand.audioTrackToPlaylistTrack(track);
        String result = PlaylistManager.getInstance().addTrack(userId, fav.getId(), pt);
        switch (result) {
            case "ok" -> {
                event.reply(EmbedHelper.MSG_SUCCESS + " Added to favorites!").setEphemeral(true).queue();
                
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
            case "duplicate" -> event.reply(EmbedHelper.MSG_ERROR + " Already in favorites.").setEphemeral(true).queue();
            case "limit" -> event.reply(EmbedHelper.MSG_ERROR + " Favorites limit reached.").setEphemeral(true).queue();
            default -> event.reply(EmbedHelper.MSG_ERROR + " Failed to add.").setEphemeral(true).queue();
        }
    }

    public static void handleSelectMenu(StringSelectInteractionEvent event) {
        try {
            if (event.getComponentId().equals("help_menu")) {
                String category = event.getValues().get(0);
                String prefix = "/";
                MessageEmbed embed = EmbedHelper.createHelpEmbed(category, prefix, event.getJDA());
                event.editMessageEmbeds(embed).queue();
            } else if (event.getComponentId().startsWith("search_")) {
                String[] parts = event.getComponentId().split("_", 3);
                if (parts.length < 3) return;
                String searchId = parts[1];
                String userId = parts[2];
                
                if (!event.getUser().getId().equals(userId)) {
                    event.reply("This search menu is not for you!").setEphemeral(true).queue();
                    return;
                }
                
                List<AudioTrack> results = com.discord.musicbot.commands.music.SearchCommand.searchCache.get(searchId);
                if (results == null) {
                    event.reply("This search session has expired.").setEphemeral(true).queue();
                    return;
                }
                
                int index = Integer.parseInt(event.getValues().get(0));
                AudioTrack selected = results.get(index).makeClone();
                selected.setUserData("{\"requester\":\"" + userId + "\"}");
                
                MusicManager manager = PlayerManager.getInstance().getMusicManager(event.getGuild());
                var userState = event.getMember().getVoiceState();
                if (userState != null && userState.inAudioChannel() && !event.getGuild().getAudioManager().isConnected()) {
                    event.getGuild().getAudioManager().openAudioConnection(userState.getChannel());
                }
                
                manager.getScheduler().queue(selected);
                manager.updateNowPlayingMessage();
                
                event.editMessageEmbeds(new net.dv8tion.jda.api.EmbedBuilder()
                    .setColor(new java.awt.Color(EmbedHelper.COLOR_MAIN))
                    .setDescription(EmbedHelper.MSG_SUCCESS + " Added **[" + EmbedHelper.escapeMarkdown(selected.getInfo().title) + "](" + selected.getInfo().uri + ")** to the queue.")
                    .build())
                    .setComponents()
                    .queue();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(InteractionHandler.class).error("Error in handleSelectMenu", e);
            if (!event.isAcknowledged()) {
                event.reply("An error occurred processing the menu.").setEphemeral(true).queue();
            }
        }
    }
}
