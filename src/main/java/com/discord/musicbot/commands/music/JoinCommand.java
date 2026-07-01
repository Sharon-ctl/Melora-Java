package com.discord.musicbot.commands.music;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.commands.framework.EmbedHelper;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class JoinCommand extends SlashCommand {
    @Override
    public String getName() { return "join"; }

    @Override
    public void execute(CommandContext ctx) {
        var userChannel = ctx.getMember().getVoiceState().getChannel();
        var botState = ctx.getGuild().getSelfMember().getVoiceState();
        
        if (botState != null && botState.inAudioChannel() && botState.getChannel().equals(userChannel)) {
            ctx.reply(EmbedHelper.MSG_SUCCESS + " I'm already in your voice channel!");
            return;
        }

        ctx.getMusicManager().setNowPlayingChannel(ctx.getChannel().getId());
        ctx.getMusicManager().connectToVoiceChannel(userChannel);
        ctx.reply(EmbedHelper.MSG_SUCCESS + " Joined **" + userChannel.getName() + "**!");
    }

    @Override public boolean requiresDj() { return true; }
    @Override public boolean requiresVoice() { return true; }
    @Override public boolean requiresBotInVoice() { return false; }
    @Override public boolean requiresSameChannel() { return false; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Summons the bot to your voice channel");
    }
}
