package com.discord.musicbot.commands.admin;

import com.discord.musicbot.commands.framework.CommandContext;

import com.discord.musicbot.commands.framework.SlashCommand;
import com.discord.musicbot.data.GuildSettingsManager;
import com.discord.musicbot.data.model.GuildSettings;
import net.dv8tion.jda.api.EmbedBuilder;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.awt.Color;
import java.util.List;

public class BlacklistCommand extends SlashCommand {

    @Override
    public String getName() {
        return "blacklist";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.getMember().hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER)) {
            ctx.replyError("You need `Manage Server` permission to use this command.");
            return;
        }

        String sub = ctx.getEvent().getSubcommandName();
        if (sub == null) { ctx.replyError("Invalid subcommand."); return; }

        GuildSettings settings = GuildSettingsManager.getInstance().getSettings(ctx.getGuild().getId());

        switch (sub) {
            case "track": {
                String val = ctx.getOption("value").getAsString();
                if (!settings.getBlacklistTracks().contains(val)) {
                    settings.getBlacklistTracks().add(val);
                    GuildSettingsManager.getInstance().markDirty();
                }
                ctx.replySuccess("Blacklisted track/URL: `" + val + "`");
                break;
            }
            case "artist": {
                String val = ctx.getOption("value").getAsString();
                if (!settings.getBlacklistArtists().contains(val)) {
                    settings.getBlacklistArtists().add(val);
                    GuildSettingsManager.getInstance().markDirty();
                }
                ctx.replySuccess("Blacklisted artist: `" + val + "`");
                break;
            }
            case "domain": {
                String val = ctx.getOption("value").getAsString();
                if (!settings.getBlacklistDomains().contains(val)) {
                    settings.getBlacklistDomains().add(val);
                    GuildSettingsManager.getInstance().markDirty();
                }
                ctx.replySuccess("Blacklisted domain: `" + val + "`");
                break;
            }
            case "list": {
                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("Server Blacklist");
                eb.setColor(Color.DARK_GRAY);

                StringBuilder tb = new StringBuilder();
                int i = 1;
                for (String t : settings.getBlacklistTracks()) tb.append(i++).append(". ").append(t).append("\n");
                if (tb.length() == 0) tb.append("None");
                eb.addField("Tracks", tb.toString(), false);

                StringBuilder ab = new StringBuilder();
                for (String a : settings.getBlacklistArtists()) ab.append(i++).append(". ").append(a).append("\n");
                if (ab.length() == 0) ab.append("None");
                eb.addField("Artists", ab.toString(), false);

                StringBuilder db = new StringBuilder();
                for (String d : settings.getBlacklistDomains()) db.append(i++).append(". ").append(d).append("\n");
                if (db.length() == 0) db.append("None");
                eb.addField("Domains", db.toString(), false);

                ctx.getEvent().replyEmbeds(eb.build()).queue();
                break;
            }
            case "remove": {
                int index = ctx.getOption("index").getAsInt() - 1;
                List<String> t = settings.getBlacklistTracks();
                List<String> a = settings.getBlacklistArtists();
                List<String> d = settings.getBlacklistDomains();
                
                String removed = null;
                if (index >= 0) {
                    if (index < t.size()) { removed = t.remove(index); }
                    else if (index < t.size() + a.size()) { removed = a.remove(index - t.size()); }
                    else if (index < t.size() + a.size() + d.size()) { removed = d.remove(index - t.size() - a.size()); }
                }

                if (removed != null) {
                    GuildSettingsManager.getInstance().markDirty();
                    ctx.replySuccess("Removed from blacklist: `" + removed + "`");
                } else {
                    ctx.replyError("Invalid index.");
                }
                break;
            }
            default:
                ctx.replyError("Unknown subcommand.");
        }
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash(getName(), "Manage guild music blacklist")
                .addSubcommands(
                        new SubcommandData("track", "Blacklist a specific track URL or title")
                                .addOption(OptionType.STRING, "value", "Track URL or exact title", true),
                        new SubcommandData("artist", "Blacklist an artist")
                                .addOption(OptionType.STRING, "value", "Artist name", true),
                        new SubcommandData("domain", "Blacklist a streaming domain")
                                .addOption(OptionType.STRING, "value", "Domain (e.g. spotify.com)", true),
                        new SubcommandData("list", "View all blacklisted items"),
                        new SubcommandData("remove", "Remove an item from the blacklist by index")
                                .addOption(OptionType.INTEGER, "index", "Index from /blacklist list", true)
                );
    }
}
