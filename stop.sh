#!/bin/bash

# Discord Music Bot - Stop Script

if [ -f bot.pid ]; then
    PID=$(cat bot.pid)
    echo "Stopping bot (PID: $PID)..."
    kill $PID
    rm bot.pid
    echo "Bot stopped successfully"
else
    echo "No PID file found. Bot may not be running."
    echo "Try: pkill -f discord-music-bot"
fi
