package com.discord.musicbot.commands.framework;

import net.dv8tion.jda.api.entities.Member;
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
    private final java.util.concurrent.ExecutorService commandExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public CommandRegistry() {
        java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Cooldown-Cleanup-Thread");
            return t;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                cooldowns.entrySet().removeIf(entry -> now - entry.getValue() >= 3000);
            } catch (Exception e) {
                logger.error("Error in cooldown cleanup task", e);
            }
        }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

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
                replyError(event, String.format("Please wait %.1f more seconds before using another command.", timeLeft));
                return;
            }
        }
        cooldowns.put(userId, now);

        SlashCommand cmd = commands.get(event.getName());
        if (cmd == null) {
            replyError(event, "Unknown command.");
            return;
        }

        CommandContext ctx = new CommandContext(event);

        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(event.getGuild().getId());
        String reqChannel = settings.getCommandChannelId();
        if (reqChannel != null && !reqChannel.isEmpty() && !event.getChannel().getId().equals(reqChannel)) {
            // Only allow settings/help commands to bypass channel restrictions so admins don't get locked out
            if (!cmd.getName().equals("settings") && !cmd.getName().equals("help")) {
                replyError(event, "Commands are restricted to <#" + reqChannel + "> in this server.");
                return;
            }
        }

        try {
            if (cmd.requiresDj() && !checkDjRole(ctx)) return;
            if (cmd.requiresVoice() && !checkVoiceState(ctx, cmd.requiresBotInVoice(), cmd.requiresSameChannel())) return;

            commandExecutor.execute(() -> {
                try {
                    cmd.execute(ctx);
                } catch (Exception e) {
                    logger.error("Error executing command: {}", cmd.getName(), e);
                    try {
                        replyError(event, "An error occurred while executing the command.");
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            logger.error("Error initiating command execution: {}", cmd.getName(), e);
        }
    }

    public static boolean checkDjRole(net.dv8tion.jda.api.entities.Guild guild, Member member, com.discord.musicbot.audio.MusicManager manager, net.dv8tion.jda.api.interactions.callbacks.IReplyCallback replyCallback) {
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(guild.getId());
        if (!settings.isDjMode()) return true;

        if (member == null) return false;
        
        if (member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER) || member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) return true;
        
        boolean hasDj = false;
        String djRoleId = settings.getDjRole();
        if (djRoleId != null) {
            hasDj = member.getRoles().stream().anyMatch(role -> role.getId().equals(djRoleId));
        } else {
            hasDj = member.getRoles().stream().anyMatch(role -> role.getName().equalsIgnoreCase("DJ"));
        }
        
        if (hasDj || isCurrentRequester(member, manager) || (manager != null && manager.isTempDj(member.getId()))) {
            return true;
        }

        if (replyCallback != null) {
            org.slf4j.LoggerFactory.getLogger(CommandRegistry.class).warn("SECURITY: User {} attempted unauthorized DJ action in Guild {}", member.getUser().getName(), guild.getName());
            replyError(replyCallback, "DJ mode is enabled. You need the DJ role, `Manage Server` permission, or be the requester of the current track to do this.");
        }
        return false;
    }

    private static void replyError(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback callback, String msg) {
        var container = net.dv8tion.jda.api.components.container.Container.of(
            net.dv8tion.jda.api.components.textdisplay.TextDisplay.of(EmbedHelper.MSG_ERROR + " " + msg)
        ).withAccentColor(EmbedHelper.COLOR_MAIN);
        if (callback.isAcknowledged()) {
            callback.getHook().sendMessageComponents(container).useComponentsV2().setEphemeral(true).queue();
        } else {
            callback.replyComponents(container).useComponentsV2().setEphemeral(true).queue();
        }
    }

    public static boolean isAuthorizedForLock(CommandContext ctx) {
        Member member = ctx.getMember();
        if (member == null) return false;
        if (member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR) || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER) || member.isOwner()) return true;
        
        com.discord.musicbot.data.model.GuildSettings settings = com.discord.musicbot.data.GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());
        String djRoleId = settings.getDjRole();
        if (djRoleId != null) {
            return member.getRoles().stream().anyMatch(r -> r.getId().equals(djRoleId));
        } else {
            return member.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("DJ"));
        }
    }

    private boolean checkDjRole(CommandContext ctx) {
        return checkDjRole(ctx.getGuild(), ctx.getMember(), ctx.getMusicManager(), ctx.getEvent());
    }

    public static boolean isCurrentRequester(Member member, com.discord.musicbot.audio.MusicManager manager) {
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

        return requesterId != null && requesterId.equals(member.getId());
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
                ctx.getMusicManager().connectToVoiceChannel(userState.getChannel());
            }
        }

        return true;
    }

    public static boolean checkBotVoicePermissions(Member bot, net.dv8tion.jda.api.entities.channel.middleman.AudioChannel targetChannel, CommandContext ctx) {
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
            ctx.replyError("I cannot join `" + targetChannel.getName() + "`. Missing permissions: "
                    + sb.toString() + ". Please grant them and try again.");
            return false;
        }
        return true;
    }
}
