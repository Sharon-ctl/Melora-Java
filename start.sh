#!/bin/bash

# Discord Music Bot - Startup Script
# This script starts the bot and redirects output to logs

echo "Starting Discord Music Bot..."
echo "$(date): Bot starting" >> bot.log

# Run the bot with Java 17+
java -jar target/discord-music-bot-1.0.0.jar >> bot.log 2>&1 &

# Save PID
echo $! > bot.pid

echo "Bot started with PID $(cat bot.pid)"
echo "View logs: tail -f bot.log"
echo "Stop bot: kill $(cat bot.pid)"
