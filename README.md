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
   This will generate a shaded JAR file in the `target/` directory (e.g., `discord-music-bot-1.0.0-shaded.jar`).

## Running the Bot

Run the generated shaded JAR using Java:

```bash
java -jar target/discord-music-bot-1.0.0-shaded.jar
```

Alternatively, on Windows or Linux, you can use the provided startup scripts (`start.bat` or `start.sh`) if configured for your environment.

## License

This project is open-sourced under the MIT License.
