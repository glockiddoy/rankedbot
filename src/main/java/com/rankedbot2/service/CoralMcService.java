package com.rankedbot2.service;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoralMcService {

    /** Solo la forma "match/123" o "match=123": un numero qualsiasi nel messaggio non è un ID. */
    private static final Pattern MATCH_ID_PATTERN = Pattern.compile("match[/=](\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONLY_DIGITS = Pattern.compile("\\d{1,12}");
    private final HttpClient httpClient;

    public CoralMcService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static class CoralPlayerStats {
        public String username;
        public String teamName;
        public String matchOutcome; // "Win" or "Loss"
        public int kills;
        public int finalKills;
        public int deaths;
        public int bedsBroken;
        public double score;

        public boolean isWinner() {
            return "Win".equalsIgnoreCase(matchOutcome);
        }
    }

    public static class CoralMatchData {
        public long matchId;
        public String arenaName;
        public String typeName;
        public String winningTeamName;
        public int durationSeconds;
        public List<CoralPlayerStats> perPlayerStats = new ArrayList<>();

        /**
         * Una partita è ancora in corso finché CoralMC non ha scritto il team
         * vincitore ed esito e durata di ogni giocatore.
         */
        public boolean isOngoing() {
            if (winningTeamName == null || winningTeamName.isBlank()
                    || "Ongoing".equalsIgnoreCase(winningTeamName)
                    || "N/A".equalsIgnoreCase(winningTeamName)) {
                return true;
            }
            if (perPlayerStats == null || perPlayerStats.isEmpty()) return true;
            for (CoralPlayerStats p : perPlayerStats) {
                if (p.matchOutcome == null || p.matchOutcome.isBlank()
                        || "Ongoing".equalsIgnoreCase(p.matchOutcome)) {
                    return true;
                }
            }
            return false;
        }

        public CoralPlayerStats byUsername(String username) {
            if (username == null) return null;
            for (CoralPlayerStats p : perPlayerStats) {
                if (username.equalsIgnoreCase(p.username)) return p;
            }
            return null;
        }

        public CoralPlayerStats getMvp() {
            CoralPlayerStats mvp = null;
            for (CoralPlayerStats p : perPlayerStats) {
                if (p.isWinner()) {
                    if (mvp == null || p.score > mvp.score) {
                        mvp = p;
                    }
                }
            }
            if (mvp == null && !perPlayerStats.isEmpty()) {
                for (CoralPlayerStats p : perPlayerStats) {
                    if (mvp == null || p.score > mvp.score) {
                        mvp = p;
                    }
                }
            }
            return mvp;
        }
    }

    public String extractMatchId(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();

        Matcher m = MATCH_ID_PATTERN.matcher(trimmed);
        if (m.find()) return m.group(1);

        // Solo se tutto l'input è l'ID: dentro una frase un numero non è un match.
        if (ONLY_DIGITS.matcher(trimmed).matches()) return trimmed;
        return null;
    }

    /** True se il messaggio contiene un link a una partita bedwars di CoralMC. */
    public boolean containsMatchLink(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("coralmc.it") && MATCH_ID_PATTERN.matcher(lower).find();
    }

    /**
     * Come fetchMatchData, ma riprova finché CoralMC non ha salvato il finale di
     * partita: subito dopo l'ultimo letto distrutto i dati sono ancora parziali.
     * Va chiamato fuori dal thread eventi di JDA perché blocca.
     */
    public CoralMatchData fetchFinishedMatch(String input, int attempts, long delayMillis) throws Exception {
        CoralMatchData data = fetchMatchData(input);

        // Attesa crescente: quasi sempre i dati sono già completi al primo colpo,
        // e quando mancano bastano poche centinaia di ms. Un'attesa fissa lunga
        // faceva pagare a tutti il caso peggiore.
        long wait = 400;
        for (int i = 1; i < Math.max(1, attempts) && data.isOngoing(); i++) {
            Thread.sleep(Math.min(wait, delayMillis));
            wait *= 2;
            data = fetchMatchData(input);
        }
        return data;
    }

    public CoralMatchData fetchMatchData(String input) throws Exception {
        String matchId = extractMatchId(input);
        if (matchId == null) {
            throw new IllegalArgumentException("Link o ID partita non valido. Esempio: https://www.coralmc.it/stats/bedwars/match/4763808");
        }

        String apiUrl = "https://coralmc.it/api/v1/stats/bedwars/match/" + matchId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/json")
                .header("User-Agent", "RankedBot/1.0 (Minecraft Discord Bot)")
                .header("X-Contact", "RankedBotDiscord")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IllegalStateException("Partita non trovata su CoralMC (ID: " + matchId + ")");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Errore API CoralMC (HTTP " + response.statusCode() + ")");
        }

        DataObject obj = DataObject.fromJson(response.body());
        CoralMatchData match = new CoralMatchData();
        match.matchId = obj.getLong("match_id", Long.parseLong(matchId));
        match.arenaName = obj.getString("arena_name", "");
        match.typeName = obj.getString("type_name", "");
        match.winningTeamName = obj.getString("winning_team_name", "");
        match.durationSeconds = obj.getInt("duration_seconds", 0);

        if (obj.hasKey("per_player_stats")) {
            DataArray arr = obj.getArray("per_player_stats");
            for (int i = 0; i < arr.length(); i++) {
                DataObject pObj = arr.getObject(i);
                CoralPlayerStats p = new CoralPlayerStats();
                p.username = pObj.getString("username", "");
                p.teamName = pObj.getString("team_name", "");
                p.matchOutcome = pObj.getString("match_outcome", "");
                p.kills = pObj.getInt("kills", 0);
                p.finalKills = pObj.getInt("final_kills", 0);
                p.deaths = pObj.getInt("deaths", 0);
                p.bedsBroken = pObj.getInt("beds_broken", 0);
                p.score = pObj.getDouble("score", 0.0);
                match.perPlayerStats.add(p);
            }
        }

        return match;
    }
}
