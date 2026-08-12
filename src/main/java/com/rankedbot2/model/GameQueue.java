package com.rankedbot2.model;

public class GameQueue {

    public enum PickingMode {
        AUTOMATIC,
        CAPTAINS
    }

    public final String vcId;
    public final int playersEachTeam;
    public final PickingMode pickingMode;
    public final boolean casual;

    public GameQueue(String vcId, int playersEachTeam, PickingMode pickingMode, boolean casual) {
        this.vcId = vcId;
        this.playersEachTeam = playersEachTeam;
        this.pickingMode = pickingMode;
        this.casual = casual;
    }

    public int totalPlayers() {
        return playersEachTeam * 2;
    }

    public String modeName() {
        return playersEachTeam + "v" + playersEachTeam;
    }
}
