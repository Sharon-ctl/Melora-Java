package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class SeekCommand extends SlashCommand {
    @Override
    public String getName() {
        return "seek";
    }

    @Override
    public void execute(CommandContext ctx) {
        String timeStr = ctx.getOption("time").getAsString();
        long millis = parseTime(timeStr);
        if (millis < 0) {
            ctx.replyError("Invalid time format. Use mm:ss or hh:mm:ss");
            return;
        }
        ctx.getScheduler().seek(millis);
        ctx.replySuccess("Seeked to " + timeStr);
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

        private long parseTime(String time) {
        String[] parts = time.split(":");
        try {
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
            } else if (parts.length == 3) {
                return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1000;
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Seek").addOption(OptionType.STRING, "time", "Time (mm:ss)", true);
    }
}

