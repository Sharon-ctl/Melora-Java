# Melora-Java

Melora-Java is a high-performance, robust Discord music bot built with Java, JDA, and Lavaplayer. Designed for large-scale deployments, it features native Spotify integration, YouTube Autoplay fallback, a persistent 24/7 mode, and highly reliable state management.

## Features

- **High-Fidelity Audio Playback**: Powered by Lavaplayer, supporting YouTube, Soundcloud, and more.
- **Native Spotify Integration**: Automatically resolves and scrapes Spotify track and playlist metadata.
- **Autoplay / Infinite Radio**: Seamlessly transitions into related YouTube tracks when the queue runs dry.
- **24/7 Persistent Mode**: Allows the bot to stay in the voice channel indefinitely and gracefully recover active queues after server restarts.
- **Advanced Queue Management**: Full support for shuffling, looping, track moving, jumping, and history tracking.

## Prerequisites

- **Java Development Kit (JDK) 26**
- **Apache Maven 3.9+**
- A Discord Bot Token (from the Discord Developer Portal)
- Spotify API Credentials (Client ID and Client Secret)

## Installation

1. **Clone the Repository**
   ```bash
   git clone https://github.com/Sharon-ctl/Melora-Java.git
   cd Melora-Java
   ```

2. **Configure Environment Variables**
   Copy the provided `.env.example` file to `.env`:
   ```bash
   cp .env.example .env
   ```
   Open `.env` and fill in your Discord Bot Token and Spotify API keys.

3. **Build the Project**
   Compile the bot using Maven:
   ```bash
   mvn clean package -DskipTests
   ```
   This will generate a shaded JAR file in the `target/` directory (e.g., `melora.jar`).

## VPS / Datacenter Deployment (YouTube 403 Fix)

If you are hosting the bot on a VPS or cloud provider, YouTube will likely block the connection with a `This video requires login` error. To bypass this, configure a **PoToken**:

1. Log into YouTube in an incognito window in your browser.
2. Open Developer Tools (F12) -> Network tab.
3. Play a video. Look for a request named `player?key=...` or `videoplayback?`.
4. In the Request payload/headers, locate the `visitorData` and `poToken` strings. (Alternatively, use a community tool/extension like YouTube PoToken Generator).
5. Open `.env` and add:
   ```env
   YOUTUBE_PO_TOKEN=your_po_token
   YOUTUBE_VISITOR_DATA=your_visitor_data
   ```
The bot will automatically apply these credentials to bypass rate-limiting.

## Running the Bot

Run the generated shaded JAR using Java:

```bash
java -jar target/discord-music-bot-1.0.0-shaded.jar
```

Alternatively, on Windows or Linux, you can use the provided startup scripts (`start.bat` or `start.sh`) if configured for your environment.

## License

This project is open-sourced under the MIT License.
