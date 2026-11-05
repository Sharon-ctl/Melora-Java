package com.discord.musicbot.commands.admin;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.audio.PlayerManager;
import com.discord.musicbot.audio.TrackScheduler;
import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class DiagnoseCommand extends SlashCommand {
    @Override
    public String getName() {
        return "diagnose";
    }

    @Override
    public void execute(CommandContext ctx) {
        var guild = ctx.getGuild();
        MusicManager manager = PlayerManager.getInstance().getMusicManager(guild.getIdLong());
        TrackScheduler scheduler = manager == null ? null : manager.getScheduler();
        var current = scheduler == null ? null : scheduler.getCurrentTrack();
        int queued = scheduler == null ? 0 : scheduler.getQueueSize();
        boolean ready = ctx.getEvent().getJDA().getStatus() == JDA.Status.CONNECTED;
        boolean voiceReady = guild.getAudioManager().isConnected();
        boolean paused = current != null && scheduler.isPaused();
        String playback = current == null ? "IDLE" : paused ? "PAUSED" : "PLAYING";

        String finding;
        String code;
        String nextStep;
        if (!ready) {
            code = "GATEWAY_OFFLINE";
            finding = "The bot is not connected to Discord.";
            nextStep = "Check the bot process and Discord connection, then retry.";
        } else if (manager != null && !voiceReady) {
            code = "VOICE_DISCONNECTED";
            finding = "The player exists, but Discord voice is disconnected.";
            nextStep = "Run /play from your voice channel to reconnect.";
        } else if (current == null && queued > 0) {
            code = "QUEUE_STALLED";
            finding = "Tracks are queued, but none is playing.";
            nextStep = "Try /play with a new track; if playback remains idle, share this report with an administrator.";
        } else if (paused) {
            code = "PLAYBACK_PAUSED";
            finding = "Playback is paused.";
            nextStep = "Run /resume to continue.";
        } else if (manager == null) {
            code = "NO_ACTIVE_SESSION";
            finding = "No music session is active in this server.";
            nextStep = "Join a voice channel and run /play.";
        } else {
            code = "HEALTHY";
            finding = "The gateway, audio backend, and player state look healthy.";
            nextStep = "If you still hear silence, check your voice channel permissions and bitrate.";
        }

        String report = "### Music Bot Diagnostics\n"
                + "**Discord gateway:** `" + (ready ? "READY" : "NOT READY") + "`\n"
                + "**Audio backend:** `READY`\n"
                + "**Voice connection:** `" + (voiceReady ? "CONNECTED" : "NOT CONNECTED") + "`\n"
                + "**Playback:** `" + playback + "`\n"
                + "**Queue:** `" + queued + " tracks`\n"
                + "**Finding:** " + finding + "\n"
                + "**Code:** `" + code + "`\n"
                + "**Next step:** " + nextStep;
        ctx.replyContainerEphemeral(Container.of(TextDisplay.of(report)).withAccentColor(EmbedHelper.COLOR_MAIN));
    }

    @Override
    public boolean requiresDj() {
        return false;
    }

    @Override
    public boolean requiresVoice() {
        return false;
    }

    @Override
    public boolean requiresBotInVoice() {
        return false;
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Check music playback and show likely fixes");
    }
}
