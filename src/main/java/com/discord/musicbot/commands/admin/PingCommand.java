package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;
import com.discord.musicbot.commands.framework.EmbedHelper;
import com.discord.musicbot.commands.framework.SlashCommand;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class PingCommand extends SlashCommand {
    @Override
    public String getName() {
        return "ping";
    }

    @Override
    public void execute(CommandContext ctx) {
        long timeBefore = System.currentTimeMillis();
        ctx.getEvent().deferReply().queue(hook -> {
            long restPing = System.currentTimeMillis() - timeBefore;
            long gatewayPing = ctx.getEvent().getJDA().getGatewayPing();
            var container = Container.of(
                    TextDisplay.of(String.format("### 🏓 Pong!\n**Gateway Ping:** `%dms`\n**REST Ping:** `%dms`", gatewayPing, restPing))
            ).withAccentColor(EmbedHelper.COLOR_MAIN);
            hook.sendMessageComponents(container).useComponentsV2().queue();
        });
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
        return Commands.slash(getName(), "Check bot latency");
    }
}
