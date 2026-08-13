package com.rankedbot2.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * I party vivono in memoria e sono resi thread-safe per la concorrenza con i comandi slash e il thread delle code.
 */
public class Party {

    public String leader;
    public final List<String> members = new CopyOnWriteArrayList<>();
    /** userId invitato -> istante di scadenza dell'invito. */
    public final Map<String, Long> invites = new ConcurrentHashMap<>();

    public Party(String leader) {
        this.leader = leader;
        this.members.add(leader);
    }

    public boolean contains(String userId) {
        return members.contains(userId);
    }

    public int size() {
        return members.size();
    }

    public boolean hasValidInvite(String userId) {
        Long expiry = invites.get(userId);
        return expiry != null && expiry > System.currentTimeMillis();
    }
}
