package com.discord.musicbot.commands.user;

import com.discord.musicbot.audio.DeferredTrack;
import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.data.SavedQueueManager;
import com.discord.musicbot.data.model.PlaylistTrack;
import com.discord.musicbot.data.model.SavedQueue;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class SavedQueueCommand extends SlashCommand {

    @Override
    public String getName() {
        return "savedqueue";
    }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null) { ctx.replyError("Invalid subcommand."); return; }

        String userId = ctx.getUser().getId();

        switch (sub) {
            case "save": {
                List<AudioTrack> queue = ctx.getScheduler().getQueue();
                if (queue.isEmpty()) {
                    ctx.replyError("The current queue is empty.");
                    return;
                }
                String name = ctx.getOption("name").getAsString();
                List<PlaylistTrack> pTracks = new ArrayList<>();
                for (AudioTrack t : queue) {
                    pTracks.add(new PlaylistTrack(
                            t.getInfo().title,
                            t.getInfo().author,
                            t.getDuration(),
                            t.getInfo().uri,
                            t.getInfo().uri != null ? t.getInfo().uri : "ytsearch:" + t.getInfo().title + " " + t.getInfo().author,
                            null
                    ));
                }
                String result = SavedQueueManager.getInstance().saveQueue(userId, name, pTracks);
                if ("updated".equals(result)) {
                    ctx.replySuccess("Updated saved queue: **" + name + "** with " + pTracks.size() + " tracks.");
                } else if ("created".equals(result)) {
                    ctx.replySuccess("Created saved queue: **" + name + "** with " + pTracks.size() + " tracks.");
                } else if ("limit".equals(result)) {
                    ctx.replyError("You have reached the maximum number of saved queues (" + SavedQueueManager.MAX_SAVES_PER_USER + ").");
                }
                break;
            }
            case "load": {
                if (ctx.getMember().getVoiceState() == null || !ctx.getMember().getVoiceState().inAudioChannel()) {
                    ctx.replyError("You must be in a voice channel to load a queue.");
                    return;
                }
                var botState = ctx.getGuild().getSelfMember().getVoiceState();
                if (botState == null || !botState.inAudioChannel()) {
                    if (!com.discord.musicbot.commands.framework.CommandRegistry.checkBotVoicePermissions(ctx.getGuild().getSelfMember(), ctx.getMember().getVoiceState().getChannel(), ctx)) {
                        return;
                    }
                    ctx.getMusicManager().connectToVoiceChannel(ctx.getMember().getVoiceState().getChannel());
                }
                String name = ctx.getOption("name").getAsString();
                SavedQueue sq = SavedQueueManager.getInstance().getSavedQueue(userId, name);
                if (sq == null) {
                    ctx.replyError("Saved queue **" + name + "** not found.");
                    return;
                }
                int added = 0;
                for (PlaylistTrack pt : sq.getTracks()) {
                    com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                            pt.getTitle(), pt.getAuthor(), pt.getDuration(), "id", false, pt.getUri()
                    );
                    DeferredTrack track = new DeferredTrack(info, pt.getSource(), null);
                    track.setUserData(ctx.getUser());
                    ctx.getScheduler().queue(track);
                    added++;
                }
                ctx.replySuccess("Loaded **" + added + "** tracks from saved queue **" + name + "**.");
                break;
            }
            case "delete": {
                String name = ctx.getOption("name").getAsString();
                boolean removed = SavedQueueManager.getInstance().deleteQueue(userId, name);
                if (removed) {
                    ctx.replySuccess("Deleted saved queue: **" + name + "**");
                } else {
                    ctx.replyError("Saved queue **" + name + "** not found.");
                }
                break;
            }
            case "list": {
                List<SavedQueue> queues = SavedQueueManager.getInstance().getSavedQueues(userId);
                if (queues.isEmpty()) {
                    ctx.replyError("You have no saved queues.");
                    return;
                }
                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("Your Saved Queues");
                eb.setColor(Color.DARK_GRAY);
                StringBuilder sb = new StringBuilder();
                int i = 1;
                for (SavedQueue sq : queues) {
                    sb.append("`").append(i++).append(".` **").append(sq.getName()).append("** (")
                      .append(sq.getTracks().size()).append(" tracks)\n");
                }
                eb.setDescription(sb.toString());
                ctx.getEvent().replyEmbeds(eb.build()).queue();
                break;
            }
            default:
                ctx.replyError("Unknown subcommand.");
        }
    }

    @Override
    public boolean requiresDj() { return false; } // Handled natively

    @Override
    public boolean requiresVoice() { return false; } // Handled per subcommand inside execute if needed, but saving/listing doesn't need voice. Load might? Actually load needs voice, but wait! SlashCommand limits it globally.

    @Override
    public boolean requiresBotInVoice() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Manage your saved queues")
                .addSubcommands(
                        new SubcommandData("save", "Save the current queue")
                                .addOption(OptionType.STRING, "name", "Name of the saved queue", true),
                        new SubcommandData("load", "Load a saved queue")
                                .addOption(OptionType.STRING, "name", "Name of the saved queue", true),
                        new SubcommandData("delete", "Delete a saved queue")
                                .addOption(OptionType.STRING, "name", "Name of the saved queue", true),
                        new SubcommandData("list", "List your saved queues")
                );
    }
}
