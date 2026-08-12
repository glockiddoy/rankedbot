package com.rankedbot2.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clan war attiva. Vive in memoria: è un evento che lo staff avvia e conclude
 * nella stessa sessione del bot.
 */
public class ClanWar {

    public int number;
    public int playersInTeam;
    public int minClans;
    public int maxClans;
    public int winXp;
    public int winGold;
    public boolean started;
    /** clanId -> lista di userId registrati per quel clan. */
    public final Map<Integer, List<String>> registrations = new LinkedHashMap<>();

    public boolean isRegistered(int clanId) {
        return registrations.containsKey(clanId);
    }

    public List<Integer> clanIds() {
        return new ArrayList<>(registrations.keySet());
    }

    public int clanCount() {
        return registrations.size();
    }
}
