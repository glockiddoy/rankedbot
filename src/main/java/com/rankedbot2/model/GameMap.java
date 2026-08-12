package com.rankedbot2.model;

public class GameMap {

    public final String name;
    public final int height;
    public final String team1;
    public final String team2;
    /** Giocatori per team per cui la mappa è pensata. 0 = va bene per ogni modalità. */
    public final int playersEachTeam;

    public GameMap(String name, int height, String team1, String team2, int playersEachTeam) {
        this.name = name;
        this.height = height;
        this.team1 = team1;
        this.team2 = team2;
        this.playersEachTeam = playersEachTeam;
    }

    public String modeName() {
        return playersEachTeam <= 0 ? "any" : playersEachTeam + "v" + playersEachTeam;
    }
}
