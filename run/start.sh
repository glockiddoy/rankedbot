#!/bin/bash
echo "Avvio di RankedBotV2 in corso..."
while true; do
    java -Xmx512M -jar RankedBotV2.jar
    echo "Il bot si e arrestato. Riavvio automatico in 5 secondi..."
    sleep 5
done
