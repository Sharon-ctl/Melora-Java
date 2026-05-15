package com.discord.musicbot.commands.framework;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandRegistry {
    private static final Logger logger = LoggerFactory.getLogger(CommandRegistry.class);
    private final Map<String, SlashCommand> commands = new HashMap<>();
    private final Map<Long, Long> cooldowns = new ConcurrentHashMap<>();

    public void register(SlashCommand command) {
        commands.put(command.getName(), command);
    }

    public List<CommandData> getCommandData() {
        List<CommandData> dataList = new ArrayList<>();
        for (SlashCommand cmd : commands.values()) {
            CommandData data = cmd.getCommandData();
            if (data != null) {
                dataList.add(data);
            }
        }
        return dataList;
    }

    public void handleSlashCommand(SlashCommandInteractionEvent event) {
        if (event.getUser().isBot() || event.getGuild() == null) return;

        // Rate Limiting (3 seconds)
        long userId = event.getUser().getIdLong();
        long now = System.currentTimeMillis();
        if (cooldowns.containsKey(userId)) {
            long last = cooldowns.get(userId);
            if (now - last < 3000) {
                double timeLeft = (3000 - (now - last)) / 1000.0;
                event.reply(String.format("Please wait %.1f more seconds before using another command.", timeLeft))
                        .setEphemeral(true).queue();
                return;
            }
        }
        cooldowns.put(userId, now);

        SlashCommand cmd = commands.get(event.getName());
        if (cmd == null) {
            event.reply("Unknown command.").setEphemeral(true).queue();
            return;
        }

        CommandContext ctx = new CommandContext(event);

        try {
            if (cmd.requiresDj() && !checkDjRole(ctx)) return;
            if (cmd.requiresVoice() && !checkVoiceState(ctx, cmd.requiresBotInVoice(), cmd.requiresSameChannel())) return;

            cmd.execute(ctx);
        } catch (Exception e) {
            logger.error("Error executing command: {}", cmd.getName(), e);
            if (!event.isAcknowledged()) {
                event.reply("An error occurred while executing the command.").setEphemeral(true).queue();
            }
        }
    }

    private boolean checkDjRole(CommandContext ctx) {
        Member member = ctx.getMember();
        if (member == null) return false;
        
        if (member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) return true;
        boolean hasDj = member.getRoles().stream().anyMatch(role -> role.getName().equalsIgnoreCase("DJ"));
        
        if (hasDj || isCurrentRequester(ctx)) {
            return true;
        }

        ctx.replyError("You need the **DJ** role or be the requester of the current track to use this command.");
        return false;
    }

    private boolean isCurrentRequester(CommandContext ctx) {
        var manager = ctx.getMusicManager();
        if (manager == null || manager.getPlayer().getPlayingTrack() == null) return false;
        
        Object userData = manager.getPlayer().getPlayingTrack().getUserData();
        String requesterId = null;

        if (userData instanceof net.dv8tion.jda.api.entities.User) {
            requesterId = ((net.dv8tion.jda.api.entities.User) userData).getId();
        } else if (userData instanceof String) {
            String ud = (String) userData;
            if (ud.contains("\"requester\":\"")) {
                requesterId = ud.split("\"requester\":\"")[1].split("\"")[0];
            } else {
                requesterId = ud;
            }
        }

        return requesterId != null && requesterId.equals(ctx.getMember().getId());
    }

    private boolean checkVoiceState(CommandContext ctx, boolean requireBotInVoice, boolean requireSameChannel) {
        var userState = ctx.getMember().getVoiceState();
        var botState = ctx.getGuild().getSelfMember().getVoiceState();

        if (userState == null || !userState.inAudioChannel()) {
            ctx.replyError("You need to be in a voice channel!");
            return false;
        }

        if (requireBotInVoice) {
            if (botState == null || !botState.inAudioChannel()) {
                ctx.replyError("I am not connected to a voice channel.");
                return false;
            }
        }

        if (requireSameChannel && botState != null && botState.inAudioChannel()) {
            if (!userState.getChannel().equals(botState.getChannel())) {
                ctx.replyError("You need to be in the same voice channel as me!");
                return false;
            }
        }

        // Check Permissions for Bot to join User's channel
        if (botState == null || !botState.inAudioChannel() || !requireBotInVoice) {
            if (!checkBotVoicePermissions(ctx.getGuild().getSelfMember(), userState.getChannel(), ctx)) {
                return false;
            }
            // Automatically connect to the user's voice channel if not already connected
            if (botState == null || !botState.inAudioChannel()) {
                ctx.getGuild().getAudioManager().openAudioConnection(userState.getChannel());
            }
        }

        return true;
    }

    private boolean checkBotVoicePermissions(Member bot, AudioChannel targetChannel, CommandContext ctx) {
        List<net.dv8tion.jda.api.Permission> missing = new ArrayList<>();
        if (!bot.hasPermission(targetChannel, net.dv8tion.jda.api.Permission.VIEW_CHANNEL))
            missing.add(net.dv8tion.jda.api.Permission.VIEW_CHANNEL);
        if (!bot.hasPermission(targetChannel, net.dv8tion.jda.api.Permission.VOICE_CONNECT))
            missing.add(net.dv8tion.jda.api.Permission.VOICE_CONNECT);
        if (!bot.hasPermission(targetChannel, net.dv8tion.jda.api.Permission.VOICE_SPEAK))
            missing.add(net.dv8tion.jda.api.Permission.VOICE_SPEAK);

        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (net.dv8tion.jda.api.Permission p : missing) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("**").append(p.getName()).append("**");
            }
            if (!ctx.getEvent().isAcknowledged()) {
                ctx.replyError("I cannot join `" + targetChannel.getName() + "`. Missing permissions: "
                        + sb.toString() + ". Please grant them and try again.");
            }
            return false;
        }
        return true;
    }
}
