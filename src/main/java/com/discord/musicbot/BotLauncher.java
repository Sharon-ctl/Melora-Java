package com.discord.musicbot;

import com.discord.musicbot.audio.PlayerManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BotLauncher {

        private static final Logger logger = LoggerFactory.getLogger(BotLauncher.class);
        private static java.util.concurrent.ScheduledExecutorService activityExecutor;

        public static void main(String[] args) {
                try {
                        logger.info("Initializing Discord Music Bot...");

                        Dotenv dotenv = Dotenv.load();
                        String token = dotenv.get("DISCORD_TOKEN");

                        if (token == null || token.isEmpty()) {
                                logger.error("DISCORD_TOKEN not found in .env file!");
                                return;
                        }

                        com.discord.musicbot.commands.framework.CommandRegistry registry = new com.discord.musicbot.commands.framework.CommandRegistry();
                        registry.register(new com.discord.musicbot.commands.music.PlayCommand());
                        registry.register(new com.discord.musicbot.commands.music.PlayInstantCommand());
                        registry.register(new com.discord.musicbot.commands.music.PlayRandomCommand());
                        registry.register(new com.discord.musicbot.commands.music.PauseCommand());
                        registry.register(new com.discord.musicbot.commands.music.ResumeCommand());
                        registry.register(new com.discord.musicbot.commands.music.KaraokeCommand());
                        registry.register(new com.discord.musicbot.commands.music.SkipCommand());
                        registry.register(new com.discord.musicbot.commands.music.PreviousCommand());
                        registry.register(new com.discord.musicbot.commands.music.StopCommand());
                        registry.register(new com.discord.musicbot.commands.music.SeekCommand());
                        registry.register(new com.discord.musicbot.commands.music.VolumeCommand());
                        registry.register(new com.discord.musicbot.commands.music.NowPlayingCommand());
                        registry.register(new com.discord.musicbot.commands.music.JoinCommand());
                        registry.register(new com.discord.musicbot.commands.music.ReplayCommand());
                        registry.register(new com.discord.musicbot.commands.music.ForwardCommand());
                        registry.register(new com.discord.musicbot.commands.music.RewindCommand());
                        registry.register(new com.discord.musicbot.commands.music.SearchCommand());
                        registry.register(new com.discord.musicbot.commands.music.GrabCommand());
                        registry.register(new com.discord.musicbot.commands.music.FilterCommand());
                        registry.register(new com.discord.musicbot.commands.music.TimeCommand());
                        registry.register(new com.discord.musicbot.commands.music.LyricsCommand());
                        registry.register(new com.discord.musicbot.commands.queue.QueueCommand());
                        registry.register(new com.discord.musicbot.commands.queue.ShuffleCommand());
                        registry.register(new com.discord.musicbot.commands.queue.LoopCommand());
                        registry.register(new com.discord.musicbot.commands.queue.RemoveCommand());
                        registry.register(new com.discord.musicbot.commands.queue.InsertCommand());
                        registry.register(new com.discord.musicbot.commands.queue.MoveCommand());
                        registry.register(new com.discord.musicbot.commands.queue.ClearCommand());
                        registry.register(new com.discord.musicbot.commands.queue.JumpCommand());
                        registry.register(new com.discord.musicbot.commands.queue.RemoveDupesCommand());
                        registry.register(new com.discord.musicbot.commands.queue.PlayNextCommand());
                        
                        registry.register(new com.discord.musicbot.commands.admin.DisconnectCommand());
                        registry.register(new com.discord.musicbot.commands.admin.LeaveCommand());
                        registry.register(new com.discord.musicbot.commands.admin.AutoplayCommand());
                        registry.register(new com.discord.musicbot.commands.admin.CrossfadeCommand());
                        registry.register(new com.discord.musicbot.commands.admin.Mode247Command());
                        registry.register(new com.discord.musicbot.commands.admin.HelpCommand());
                        registry.register(new com.discord.musicbot.commands.admin.PingCommand());
                        registry.register(new com.discord.musicbot.commands.admin.SettingsCommand());
                        
                        registry.register(new com.discord.musicbot.commands.admin.DJCommand());
                        registry.register(new com.discord.musicbot.commands.admin.BlacklistCommand());
                        
                        registry.register(new com.discord.musicbot.commands.interaction.VoteCommand());
                        registry.register(new com.discord.musicbot.commands.user.StatsCommand());
                        registry.register(new com.discord.musicbot.commands.user.WrappedCommand());
                        registry.register(new com.discord.musicbot.commands.user.SavedQueueCommand());
                        registry.register(new com.discord.musicbot.commands.user.HistoryCommand());
                        registry.register(new com.discord.musicbot.commands.user.ExcludeCommand());
                        
                        registry.register(new com.discord.musicbot.commands.playlist.PlaylistCommand());
                        registry.register(new com.discord.musicbot.commands.favorites.FavoritesCommand());
                        registry.register(new com.discord.musicbot.commands.mewsic.MewsicCommand());

                        JDA jda = JDABuilder.createDefault(token)
                                        .enableIntents(GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MESSAGES)
                                        .enableCache(CacheFlag.VOICE_STATE)
                                        .setMemberCachePolicy(net.dv8tion.jda.api.utils.MemberCachePolicy.VOICE)
                                        .setAudioModuleConfig(new net.dv8tion.jda.api.audio.AudioModuleConfig()
                                                .withDaveSessionFactory(new club.minnced.discord.jdave.interop.JDaveSessionFactory()))
                                        .setActivity(Activity.listening("/play"))
                                        .setEnableShutdownHook(false)
                                        .addEventListeners(new BotCommandHandler(registry), new VoiceEventHandler())
                                        .build();

                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                                logger.info("Shutting down bot...");
                                PlayerManager.getInstance().shutdown();
                                for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
                                        try {
                                                if (guild.getSelfMember().getVoiceState() != null && guild.getSelfMember().getVoiceState().inAudioChannel()) {
                                                        guild.getAudioManager().closeAudioConnection();
                                                        jda.getDirectAudioController().disconnect(guild);
                                                }
                                        } catch (Exception ignored) {}
                                }
                                com.discord.musicbot.data.SessionManager.getInstance().saveAllNow();
                                com.discord.musicbot.data.PlaylistManager.getInstance().flushAll();
                                com.discord.musicbot.data.StatsManager.getInstance().flushAll();
                                com.discord.musicbot.data.HistoryManager.getInstance().flushAll();
                                com.discord.musicbot.data.GuildSettingsManager.getInstance().save();
                                if (activityExecutor != null) {
                                        activityExecutor.shutdownNow();
                                }
                                jda.shutdown();
                                try {
                                        if (!jda.awaitShutdown(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                                jda.shutdownNow();
                                        }
                                } catch (InterruptedException e) {
                                }
                                logger.info("Shutdown complete.");
                        }));

                        jda.awaitReady();

                        // Restore previous sessions
                        com.discord.musicbot.data.SessionManager.getInstance().restoreAll(jda);

                        // Register all Slash Commands
                        jda.updateCommands().addCommands(registry.getCommandData())
                                        .queue(commands -> logger.info("Registered {} slash commands",
                                                        commands.size()));

                        logger.info("Bot is ready! Logged in as: {}", jda.getSelfUser().getName());

                        // Initialize PlayerManager
                        PlayerManager.getInstance();

                        // Start Activity Updater
                        activityExecutor = java.util.concurrent.Executors
                                        .newSingleThreadScheduledExecutor(r -> {
                                                Thread t = new Thread(r);
                                                t.setDaemon(true);
                                                return t;
                                        });
                        activityExecutor.scheduleAtFixedRate(() -> {
                                jda.getPresence().setActivity(Activity.listening("/play"));
                        }, 0, 10, java.util.concurrent.TimeUnit.MINUTES);

                } catch (Exception e) {
                        logger.error("Failed to start bot", e);
                }
        }
}
