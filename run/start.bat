@echo off
title RankedBot V2 - 24/7 Supervisor
:loop
echo [%date% %time%] Avvio RankedBotV2...
java -Xmx512M -jar RankedBotV2.jar
echo [%date% %time%] Bot terminato o riavviato. Riavvio in 5 secondi...
timeout /t 5
goto loop
