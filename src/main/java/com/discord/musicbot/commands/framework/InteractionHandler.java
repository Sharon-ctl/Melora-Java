package com.discord.musicbot.commands.framework;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

public class InteractionHandler {

    public static void handleButton(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;
        MusicManager manager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        String id = event.getComponentId();

        if (id.startsWith("queue_")) {
            handleQueuePagination(event, id, manager);
        } else if (id.startsWith("np_")) {
            handleNowPlayingButtons(event, id, manager);
        }
    }

    private static void handleQueuePagination(ButtonInteractionEvent event, String id, MusicManager manager) {
        String[] parts = id.split("_");
        if (parts.length < 3) return;

        String action = parts[1];
        int currentPage = Integer.parseInt(parts[2]);
        int queueSize = manager.getScheduler().getQueueSize();
        int maxPages = Math.max(1, (int) Math.ceil(queueSize / 10.0));

        int newPage = currentPage;
        switch (action) {
            case "first" -> newPage = 1;
            case "prev" -> newPage = Math.max(1, currentPage - 1);
            case "next" -> newPage = Math.min(maxPages, currentPage + 1);
            case "last" -> newPage = maxPages;
        }

        MessageEmbed embed = EmbedHelper.createQueueEmbed(manager, newPage);
        final int finalPage = newPage;
        event.deferEdit().queue(v -> {
            event.getHook().editOriginalEmbeds(embed).setComponents(
                    net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                            EmbedHelper.createPaginationButtons("queue", finalPage, maxPages)
                    )
            ).queue();
        });
    }

    private static void handleNowPlayingButtons(ButtonInteractionEvent event, String id, MusicManager manager) {
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

        switch (id) {
            case "np_pause":
                if (manager.getScheduler().isPaused()) {
                    manager.getScheduler().resume();
                } else {
                    manager.getScheduler().pause();
                }
                event.editMessageEmbeds(manager.createNowPlayingEmbed())
                        .setComponents(EmbedHelper.createNowPlayingComponents(manager))
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
            case "np_stop":
                event.deferEdit().queue();
                manager.getScheduler().stop();
                break;
            case "np_shuffle":
                event.deferEdit().queue();
                manager.getScheduler().shuffle();
                manager.updateNowPlayingMessage();
                break;
            case "np_loop":
                manager.getScheduler().cycleLoopMode();
                event.editMessageEmbeds(manager.createNowPlayingEmbed())
                        .setComponents(EmbedHelper.createNowPlayingComponents(manager))
                        .queue();
                break;
        }
    }

    public static void handleSelectMenu(StringSelectInteractionEvent event) {
        if (event.getComponentId().equals("help_menu")) {
            String category = event.getValues().get(0);
            String prefix = "/"; // Slash commands always use /
            MessageEmbed embed = EmbedHelper.createHelpEmbed(category, prefix, event.getJDA());
            event.editMessageEmbeds(embed).queue();
        }
    }
}
