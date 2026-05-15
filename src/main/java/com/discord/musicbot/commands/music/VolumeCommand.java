package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class VolumeCommand extends SlashCommand {
    @Override
    public String getName() {
        return "volume";
    }

    @Override
    public void execute(CommandContext ctx) {
        int vol = ctx.getOption("level").getAsInt();
        vol = Math.max(1, Math.min(200, vol));
        ctx.getScheduler().setVolume(vol);
        ctx.replySuccess("Volume set to " + vol + "%");
    }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Set volume").addOption(OptionType.INTEGER, "level", "Volume (1-200)", true);
    }
}

