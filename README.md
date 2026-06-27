# Melora-Java 
Dev:sharon, find me in discord https://discord.gg/KUAVNSJtu5


Privacy & Terms of the bot is listed in https://melora-info.vercel.app/

Melora-Java is a high-performance, robust Discord music bot built with Java, JDA, and Lavaplayer. Designed for large-scale deployments, it features native Spotify integration, YouTube Autoplay fallback, a persistent 24/7 mode, and highly reliable state management.

## Features

- **High-Fidelity Audio Playback**: Powered by Lavaplayer, supporting YouTube, Soundcloud, and more.
- **Native Spotify Integration**: Automatically resolves and scrapes Spotify track and playlist metadata.
- **Autoplay / Infinite Radio**: Seamlessly transitions into related YouTube tracks when the queue runs dry.
- **24/7 Persistent Mode**: Allows the bot to stay in the voice channel indefinitely and gracefully recover active queues after server restarts.
- **Advanced Queue Management**: Full support for shuffling, looping, track moving, jumping, and history tracking.
- **User Filters**: Target commands like clear or queue to specific users.
- **Playlist & Favorites System**: Save and load queues or individual tracks.

## Commands

### Playback Operations
- `/play <query>`: Start playback of a track or playlist from a URL or search term.
- `/playinstant <query>`: Instantly play a track, interrupting the current song without adding it to the queue.
- `/playnext <query>`: Insert a track at the very top of the queue so it plays immediately next.
- `/playrandom [source]`: Play a random track pulled from your favorites or the bot's global listening history.
- `/search <query>`: Search for a track and select your choice from an interactive list.
- `/pause` / `/resume`: Pause or resume the currently active playback.
- `/skip` / `/previous`: Transition to the next track in the queue, or revert to the previously played track.
- `/replay`: Restart the currently playing track from the beginning.
- `/forward <seconds>` / `/rewind <seconds>`: Skip ahead or backward in the current track by a specified duration.
- `/stop`: Immediately halt all playback and empty the current queue.
- `/join` / `/leave`: Instruct the bot to connect to or disconnect from your voice channel.
- `/disconnect`: An alias for leaving the voice channel and clearing the queue.
- `/autoplay`: Toggle the automated recommendation system that queues related tracks when your playlist ends.
- `/karaoke`: Enable Live Karaoke mode to display synchronized, real-time lyrics in chat.
- `/filter <filter>`: Apply custom audio digital signal processing (DSP) filters (e.g., bassboost, 8d, vaporwave, nightcore).
- `/crossfade [duration]`: Enable and configure overlapping PCM crossfading between tracks to prevent dead silence during transitions.
- `/247 [lock]`: Toggle 24/7 mode to keep the bot connected persistently. Use the lock argument to restrict bot dismissal to administrators or DJs.

### Queue Management
- `/queue show [page] [user]`: Display the current queue. You can optionally filter the results by a specific user or navigate pages.
- `/queue search <query>`: Search for a specific track currently within the queue.
- `/queue deduplicate`: Scan the queue and remove any duplicate entries automatically.
- `/queue compact`: Group identical tracks together in the queue sequentially.
- `/queue reverse`: Invert the current play order of all queued tracks.
- `/queue sort <criteria>`: Organize the queue alphabetically by track title or track author.
- `/queue slice <start> <end>`: Remove a contiguous range of tracks from the queue.
- `/queue shufflefrom <position>`: Shuffle only the tracks that occur after a specific point in the queue.
- `/queue swap <pos1> <pos2>`: Exchange the positions of two specific tracks in the queue.
- `/queue export`: Export the current queue into a downloadable JSON file.
- `/queue import <file>`: Import an existing JSON queue file to append to the active queue.
- `/nowplaying`: Display detailed metadata and progress regarding the currently playing track.
- `/shuffle`: Randomize the order of all upcoming tracks in the queue.
- `/loop <mode>`: Configure the repeat behavior. Modes include `off`, `track` (repeat current song), or `queue` (repeat entire list).
- `/remove <number>`: Delete a specific track from the queue using its queue index.
- `/insert <song> <position>`: Queue a new track at a specific numerical position.
- `/move <from> <to>`: Relocate an existing track from one position in the queue to another.
- `/clear [user]`: Remove all upcoming tracks from the queue. If a user is specified, only their queued tracks are removed.
- `/jump <number>`: Instantly skip playback forward to a specific track index in the queue.
- `/removedupes`: Scan the queue and eliminate any duplicate entries.

### Playlists & Favorites
- `/playlist <create/list/play/add/tracks>`: A suite of commands to create, manage, and play your own custom server playlists.
- `/favorites <add/list/play>`: Bookmark your favorite tracks and quickly play them later.
- `/savedqueue <save/list/play/delete>`: Save the current active queue state to be loaded again in the future.
- `/mewsic <import/export>`: Seamlessly import or export playlist JSON data to and from the offline Mewsic app.

### Utilities & Settings
- `/volume <1-200>`: Adjust the global audio output volume of the bot.
- `/seek <time>`: Jump to a specific timestamp (e.g., `1:30`) within the current track.
- `/time`: Display an interactive progress bar showing the current elapsed track time.
- `/lyrics [query]`: Retrieve the lyrics for the currently playing track, or search for lyrics of a specific song.
- `/grab`: Send the currently playing track's details and URL directly to your private messages.
- `/stats` / `/history` / `/wrapped`: View personalized statistics, listening history, and an aggregated summary of your music habits.
- `/ping`: Check the response latency between the bot and Discord servers.
- `/vote <skip/clear/disconnect>`: Initiate a democratic vote among listeners to perform playback actions.

### Administrative Controls
- `/settings`: Access the interactive server configuration dashboard to modify bot behaviors (Requires Admin permissions).
- `/djmode`: Toggle DJ-only mode, configure DJ roles, and grant temporary DJ access to specific users.
- `/blacklist`: Manage the server-wide music blacklist to prevent specific terms or URLs from being played.

## Prerequisites

- **Java Development Kit (JDK) 26. This is a must Have, no jdk versions lower will not work!!**
  - Download from: https://jdk.java.net/26/
- **Apache Maven 3.9+**
- A Discord Bot Token from the Discord Developer Portal
- Spotify API Credentials (Client ID and Client Secret)

## Installation

1. **Clone the Repository**
   ```bash
   git clone https://github.com/Sharon-ctl/Melora-Java.git
   cd Melora-Java
   ```

2. **Configure Environment Variables**
   Create a `.env` file in the root directory and populate it with the necessary keys.

   ```env
   # Required
   DISCORD_TOKEN=your_discord_bot_token

   YOUTUBE_OAUTH2_TOKEN=can be obtained by turning on the bot and sign in via the command line directly to youtube via google acc with the code shown in the terminal

   # Optional (Required for Spotify links)
   SPOTIFY_CLIENT_ID=your_spotify_client_id
   SPOTIFY_CLIENT_SECRET=your_spotify_client_secret
   
   # Required for full lyrics fallback
   GENIUS_ACCESS_TOKEN=your_genius_client_access_token_here

   # Optional (YouTube rate limit bypass for VPS deployment)
   YOUTUBE_PO_TOKEN=your_po_token
   YOUTUBE_VISITOR_DATA=your_visitor_data
   ```

3. **Build the Project**
   Compile the bot using Maven. This will download all dependencies and build the shaded executable JAR file.
   ```bash
   mvn clean package -DskipTests
   ```
   This generates a shaded JAR file in the `target/` directory (e.g., `melora.jar`).

## VPS / Datacenter Deployment (YouTube 403 Fix)

If you are hosting the bot on a VPS or cloud provider, YouTube will likely block the connection with a `This video requires login` error. To bypass this, configure a **PoToken**:

1. Log into YouTube in an incognito window in your browser.
2. Open Developer Tools (F12) -> Network tab.
3. Play a video. Look for a request named `player?key=...` or `videoplayback?`.
4. In the Request payload/headers, locate the `visitorData` and `poToken` strings.
5. Add these values to your `.env` file. The bot will automatically apply these credentials to bypass rate-limiting.

## Running the Bot

Run the generated shaded JAR using Java:

```bash
java -jar melora.jar
```

Ensure your terminal remains open, or use a process manager like `screen`, `tmux`, or a systemd service for persistent background execution.

## License

This project is open-sourced under the MIT License.
