package com.discord.musicbot.commands.queue;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;

public class QueueCommand extends SlashCommand {
    @Override
    public String getName() {
        return "queue";
    }

    @Override
    public void execute(CommandContext ctx) {
        var pageOpt = ctx.getOption("page");
        int page = pageOpt != null ? Math.max(1, (int) pageOpt.getAsLong()) : 1;
        var embed = com.discord.musicbot.commands.framework.EmbedHelper.createQueueEmbed(ctx.getMusicManager(), page);
        int maxPages = Math.max(1, (int) Math.ceil(ctx.getScheduler().getQueueSize() / 10.0));
        var components = com.discord.musicbot.commands.framework.EmbedHelper.createPaginationButtons("queue", page, maxPages);
        ctx.getEvent().replyEmbeds(embed).setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(components)).queue();
    }

    @Override
    public boolean requiresDj() { return false; }

    @Override
    public boolean requiresVoice() { return false; }

    @Override
    public boolean requiresBotInVoice() { return true; }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "View queue").addOption(OptionType.INTEGER, "page", "Page number", false);
    }
}

