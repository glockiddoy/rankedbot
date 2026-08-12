package com.rankedbot2.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * I party vivono solo in memoria: come nel bot originale gli inviti scadono
 * al riavvio e il party stesso non sopravvive a un restart.
 */
public class Party {

    public String leader;
    public final List<String> members = new ArrayList<>();
    /** userId invitato -> istante di scadenza dell'invito. */
    public final Map<String, Long> invites = new HashMap<>();

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
