package com.discord.musicbot;

import com.discord.musicbot.commands.framework.AutocompleteHandler;
import com.discord.musicbot.commands.framework.CommandRegistry;
import com.discord.musicbot.commands.framework.InteractionHandler;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class BotCommandHandler extends ListenerAdapter {
    private final CommandRegistry registry;

    public BotCommandHandler(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        registry.handleSlashCommand(event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        AutocompleteHandler.handle(event);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        InteractionHandler.handleButton(event);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        InteractionHandler.handleSelectMenu(event);
    }

    @Override
    public void onMessageReceived(net.dv8tion.jda.api.events.message.MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser(),
                net.dv8tion.jda.api.entities.Message.MentionType.USER)) return;

        net.dv8tion.jda.api.entities.MessageEmbed embed = new net.dv8tion.jda.api.EmbedBuilder()
                .setColor(com.discord.musicbot.commands.framework.EmbedHelper.COLOR_MAIN)
                .setAuthor("Hi! I am " + event.getJDA().getSelfUser().getName(), null, event.getJDA().getSelfUser().getEffectiveAvatarUrl())
                .setDescription("use `/play` or `/help` to know about me!!")
                .build();

        event.getMessage().replyEmbeds(embed)
             .setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                     net.dv8tion.jda.api.components.buttons.Button.link("https://melora-info.vercel.app", "Website")
             ))
             .queue();
    }
}
