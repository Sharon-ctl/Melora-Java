package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class LoopCommand extends SlashCommand {
    @Override
    public String getName() {
        return "loop";
    }

    @Override
    public void execute(CommandContext ctx) {
        var modeOpt = ctx.getOption("mode");
        if (modeOpt == null) {
            var m = ctx.getScheduler().cycleLoopMode();
            ctx.replySuccess("Loop mode set to: " + m.name());
            ctx.getMusicManager().updateNowPlayingMessage();
        } else {
            String m = modeOpt.getAsString().toUpperCase();
            try {
                com.discord.musicbot.audio.TrackScheduler.LoopMode lm = com.discord.musicbot.audio.TrackScheduler.LoopMode.valueOf(m);
                ctx.getScheduler().setLoopMode(lm);
                ctx.replySuccess("Loop mode set to: " + lm.name());
                ctx.getMusicManager().updateNowPlayingMessage();
            } catch (Exception e) {
                ctx.replyError("Invalid mode.");
            }
        }
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Set loop mode").addOption(OptionType.STRING, "mode", "off/track/queue", false);
    }
}

