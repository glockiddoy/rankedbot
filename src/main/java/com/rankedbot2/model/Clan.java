package com.rankedbot2.model;

public class Clan {

    public int id = -1;
    public String name = "";
    public String description = "";
    public String leader = "";
    public int reputation;
    public int xp;
    public int level;
    public String icon = "";
    public String theme = "";
    public boolean open;
    public int minElo;
    public int invited;
    public int cwPlayed;
    public int cwWins;
    public int cwLosses;

    public double cwWlr() {
        return cwLosses == 0 ? cwWins : (double) cwWins / cwLosses;
    }
}
