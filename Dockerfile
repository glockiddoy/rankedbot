FROM eclipse-temurin:17-jre
WORKDIR /app
COPY run/RankedBotV2.jar /app/RankedBotV2.jar
COPY run/RankedBot /app/RankedBot
CMD ["java", "-Xmx512M", "-jar", "RankedBotV2.jar"]
