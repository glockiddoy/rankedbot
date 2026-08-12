package com.rankedbot2.model;

public class Rank {

    public final String roleId;
    public final int startElo;
    public final int endElo;
    public final int winElo;
    public final int loseElo;

    public Rank(String roleId, int startElo, int endElo, int winElo, int loseElo) {
        this.roleId = roleId;
        this.startElo = startElo;
        this.endElo = endElo;
        this.winElo = winElo;
        this.loseElo = loseElo;
    }

    public boolean covers(int elo) {
        return elo >= startElo && elo <= endElo;
    }
}
