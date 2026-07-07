package com.discord.musicbot.commands.music;

import com.discord.musicbot.audio.MusicManager;
import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilterCommand extends SlashCommand {

    private static final Logger logger = LoggerFactory.getLogger(FilterCommand.class);

    @Override
    public String getName() {
        return "filter";
    }

    @Override
    public void execute(CommandContext ctx) {
        MusicManager manager = ctx.getMusicManager();

        if (manager == null || manager.getPlayer().getPlayingTrack() == null) {
            ctx.replyError("Nothing is currently playing!");
            return;
        }

        String type = ctx.getEvent().getOption("type").getAsString().toLowerCase();

        try {
            manager.getFilterManager().applyFilter(type);
            
            if (type.equals("clear") || type.equals("none")) {
                ctx.replySuccess("Cleared all audio filters!");
            } else {
                String capitalized = type.substring(0, 1).toUpperCase() + type.substring(1);
                ctx.replySuccess("Enabled **" + capitalized + "** filter!");
            }
        } catch (Exception e) {
            logger.error("Failed to apply filter: {}", type, e);
            ctx.replyError("An error occurred while applying the filter. Please try again or clear it.");
        }
    }

    @Override
    public boolean requiresVoice() { return true; }

    @Override
    public boolean requiresSameChannel() { return true; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public boolean requiresDj() { return true; }

    @Override
    public CommandData getCommandData() {
        OptionData option = new OptionData(OptionType.STRING, "type", "The type of filter to apply", true)
                .addChoice("Bassboost", "bassboost")
                .addChoice("Earrape", "earrape")
                .addChoice("Pop", "pop")
                .addChoice("Rock", "rock")
                .addChoice("Electronic", "electronic")
                .addChoice("Nightcore", "nightcore")
                .addChoice("Vaporwave", "vaporwave")
                .addChoice("8D Audio", "8d")
                .addChoice("Tremolo", "tremolo")
                .addChoice("Vibrato", "vibrato")
                .addChoice("Distortion", "distortion")
                .addChoice("Muffled", "muffled")
                .addChoice("Vocal Remove", "vocal_remove")
                .addChoice("Mono", "mono")
                .addChoice("Clear", "clear");
        return Commands.slash(getName(), "Apply an audio filter to the current playback").addOptions(option);
    }
}
