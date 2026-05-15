@echo off
REM Discord Music Bot - Windows Startup Script

echo Starting Discord Music Bot...
echo %date% %time%: Bot starting >> bot.log

REM Run the bot with Java 17+
start /B java -jar target\discord-music-bot-1.0.0.jar >> bot.log 2>&1

echo Bot started!
echo View logs: type bot.log
echo Stop bot: Use Task Manager to kill java.exe process
pause
