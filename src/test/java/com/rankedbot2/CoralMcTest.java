package com.rankedbot2;

import com.rankedbot2.service.CoralMcService;

public class CoralMcTest {

    public static void main(String[] args) {
        CoralMcService service = new CoralMcService();
        String link = "https://www.coralmc.it/stats/bedwars/match/4763808";

        System.out.println("Testing match ID extraction...");
        String matchId = service.extractMatchId(link);
        if (!"4763808".equals(matchId)) {
            System.err.println("FAILED: Expected 4763808, got " + matchId);
            System.exit(1);
        }
        System.out.println("Extracted match ID: " + matchId);

        System.out.println("Testing CoralMC API fetch...");
        try {
            CoralMcService.CoralMatchData matchData = service.fetchMatchData(link);
            System.out.println("Match ID: " + matchData.matchId);
            System.out.println("Arena: " + matchData.arenaName);
            System.out.println("Winning Team: " + matchData.winningTeamName);
            System.out.println("Players count: " + matchData.perPlayerStats.size());

            CoralMcService.CoralPlayerStats mvp = matchData.getMvp();
            if (mvp != null) {
                System.out.println("MVP: " + mvp.username + " (Score: " + mvp.score + ")");
            }

            for (CoralMcService.CoralPlayerStats p : matchData.perPlayerStats) {
                System.out.printf("- %s (%s, %s): %d Kills, %d Final Kills, %d Beds, Score %.1f%n",
                        p.username, p.teamName, p.matchOutcome, p.kills, p.finalKills, p.bedsBroken, p.score);
            }

            System.out.println("SUCCESS: CoralMC API test completed!");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
