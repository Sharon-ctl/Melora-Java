package com.discord.musicbot.audio;

import com.discord.musicbot.commands.framework.EmbedHelper;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;


import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class VoteManager {
    private static VoteManager instance;

    public enum VoteType {
        SKIP, CLEAR, DISCONNECT
    }

    private static class ActiveVote {
        public VoteType type;
        public Set<String> yesVotes = new HashSet<>();
        public Set<String> noVotes = new HashSet<>();
        public ScheduledFuture<?> timeoutTask;
        public String messageId;
        public String channelId;
    }

    private final ConcurrentHashMap<String, ActiveVote> activeVotes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private VoteManager() {}

    public static synchronized VoteManager getInstance() {
        if (instance == null) {
            instance = new VoteManager();
        }
        return instance;
    }

    public boolean hasActiveVote(String guildId) {
        return activeVotes.containsKey(guildId);
    }

    public int getActiveListenersCount(Guild guild) {
        AudioChannel channel = guild.getSelfMember().getVoiceState().getChannel();
        if (channel == null) return 0;

        int count = 0;
        List<Member> members = channel.getMembers();
        for (Member m : members) {
            if (m.getUser().isBot()) continue;
            if (m.getVoiceState() != null && (m.getVoiceState().isDeafened() || m.getVoiceState().isGuildDeafened())) continue;
            count++;
        }
        return count;
    }

    public String startVote(Guild guild, Member requester, VoteType type, int thresholdPercent) {
        if (activeVotes.containsKey(guild.getId())) {
            return "A vote is already in progress.";
        }

        ActiveVote vote = new ActiveVote();
        vote.type = type;
        vote.yesVotes.add(requester.getId());

        int required = (int) Math.ceil((getActiveListenersCount(guild) * thresholdPercent) / 100.0);
        if (required <= 1) {
            executeVote(guild, type);
            return "passed";
        }

        vote.timeoutTask = executor.schedule(() -> {
            activeVotes.remove(guild.getId());
            sendVoteResult(guild, vote, false);
        }, 30, TimeUnit.SECONDS);

        activeVotes.put(guild.getId(), vote);
        return "started";
    }

    public void registerVoteMessage(String guildId, String channelId, String messageId) {
        ActiveVote vote = activeVotes.get(guildId);
        if (vote != null) {
            vote.channelId = channelId;
            vote.messageId = messageId;
        }
    }

    public void handleVoteButton(ButtonInteractionEvent event, boolean isYes) {
        String guildId = event.getGuild().getId();
        ActiveVote vote = activeVotes.get(guildId);
        
        if (vote == null) {
            event.reply("This vote has expired or is invalid.").setEphemeral(true).queue();
            return;
        }

        AudioChannel botChannel = event.getGuild().getSelfMember().getVoiceState().getChannel();
        AudioChannel userChannel = event.getMember().getVoiceState().getChannel();

        if (botChannel == null || !botChannel.equals(userChannel)) {
            event.reply("You must be in the same voice channel to vote.").setEphemeral(true).queue();
            return;
        }

        String userId = event.getUser().getId();
        if (isYes) {
            vote.yesVotes.add(userId);
            vote.noVotes.remove(userId);
        } else {
            vote.noVotes.add(userId);
            vote.yesVotes.remove(userId);
        }

        int thresholdPercent = getThresholdForType(event.getGuild(), vote.type);
        int required = (int) Math.ceil((getActiveListenersCount(event.getGuild()) * thresholdPercent) / 100.0);

        if (vote.yesVotes.size() >= required) {
            if (vote.timeoutTask != null) vote.timeoutTask.cancel(false);
            activeVotes.remove(guildId);
            executeVote(event.getGuild(), vote.type);
            event.editMessage(EmbedHelper.MSG_SUCCESS + " Vote passed!")
                 .setEmbeds().setComponents().queue();
            return;
        }

        var embed = EmbedHelper.createVoteEmbed(vote.type.name(), vote.yesVotes.size(), required);
        event.getChannel().editMessageEmbedsById(vote.messageId, embed).queue();
    }

    private int getThresholdForType(Guild guild, VoteType type) {
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guild.getId());
        switch (type) {
            case SKIP: return settings.getVoteSkipThreshold();
            case CLEAR: return settings.getVoteClearThreshold();
            case DISCONNECT: return settings.getVoteDisconnectThreshold();
        }
        return 60;
    }

    private void executeVote(Guild guild, VoteType type) {
        MusicManager mm = PlayerManager.getInstance().getMusicManager(guild.getIdLong());
        if (mm == null) return;
        switch (type) {
            case SKIP:
                mm.getScheduler().nextTrack();
                break;
            case CLEAR:
                mm.getScheduler().clear();
                break;
            case DISCONNECT:
                guild.getAudioManager().closeAudioConnection();
                mm.getScheduler().stop();
                break;
        }
    }

    private void sendVoteResult(Guild guild, ActiveVote vote, boolean passed) {
        if (vote.channelId != null && vote.messageId != null) {
            net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, vote.channelId);
            if (channel != null) {
                channel.retrieveMessageById(vote.messageId).queue(msg -> {
                    msg.editMessage(passed ? EmbedHelper.MSG_SUCCESS + " Vote passed!" : EmbedHelper.MSG_ERROR + " Vote failed/timed out.")
                       .setComponents().queue();
                }, err -> {});
            }
        }
    }
}
