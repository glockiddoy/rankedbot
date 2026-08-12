package com.rankedbot2.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Game {

    public enum State {
        PICKING,
        STARTED,
        SUBMITTED,
        SCORED,
        VOIDED
    }

    public int number;
    public String queueVc = "";
    public State state = State.PICKING;
    public int playersEachTeam;
    public List<String> team1 = new ArrayList<>();
    public List<String> team2 = new ArrayList<>();
    public List<String> remaining = new ArrayList<>();
    public String captain1 = "";
    public String captain2 = "";
    public int pickTurn = 1;
    /** Pick che restano al capitano di turno prima di passare all'altro (ordine 1-2-2-1). */
    public int picksLeft = 1;
    public String map = "";
    public String textChannel = "";
    public String vc1 = "";
    public String vc2 = "";
    public int winner;
    public String mvp = "";
    public boolean casual;
    public String scoredBy = "";
    /** userId -> variazione elo applicata allo scoring, per poter fare undo. */
    public Map<String, Integer> eloChanges = new LinkedHashMap<>();
    /** userId -> kill aggiunte dall'autoscore, per poter fare undo. */
    public Map<String, Integer> killChanges = new LinkedHashMap<>();
    /** userId -> morti aggiunte dall'autoscore, per poter fare undo. */
    public Map<String, Integer> deathChanges = new LinkedHashMap<>();
    /** ID partita CoralMC usata per l'autoscore, 0 se scorata a mano. */
    public long coralMatch;
    public long createdAt;
    public int clanWar = -1;
    public int clan1 = -1;
    public int clan2 = -1;

    public List<String> allPlayers() {
        List<String> all = new ArrayList<>(team1);
        all.addAll(team2);
        all.addAll(remaining);
        return all;
    }

    public String modeName() {
        return playersEachTeam + "v" + playersEachTeam;
    }

    public int teamOf(String userId) {
        if (team1.contains(userId)) return 1;
        if (team2.contains(userId)) return 2;
        return 0;
    }

    public boolean isCaptain(String userId) {
        return captain1.equals(userId) || captain2.equals(userId);
    }
}
