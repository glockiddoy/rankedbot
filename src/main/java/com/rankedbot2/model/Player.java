package com.rankedbot2.model;

import java.util.ArrayList;
import java.util.List;

public class Player {

    public String id;
    public String ign;
    public int elo;
    public int peakElo;
    public int wins;
    public int losses;
    public int games;
    public int winstreak;
    public int highestWs;
    public int lossstreak;
    public int highestLs;
    public int mvp;
    public int kills;
    public int deaths;
    public int strikes;
    public int scored;
    public int gold;
    public int xp;
    public int level;
    public int clanId = -1;
    public String theme = "";
    public String ownedThemes = "";
    public long bannedUntil;
    public String banReason = "";

    public boolean isBanned() {
        return bannedUntil > System.currentTimeMillis();
    }

    public double wlr() {
        return losses == 0 ? wins : (double) wins / losses;
    }

    public double kdr() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    public List<String> ownedThemeList() {
        List<String> out = new ArrayList<>();
        for (String t : ownedThemes.split(",")) {
            if (!t.isBlank()) out.add(t.trim());
        }
        return out;
    }

    public void addTheme(String name) {
        List<String> themes = ownedThemeList();
        if (!themes.contains(name)) themes.add(name);
        ownedThemes = String.join(",", themes);
    }

    public void removeTheme(String name) {
        List<String> themes = ownedThemeList();
        themes.remove(name);
        ownedThemes = String.join(",", themes);
        if (theme.equalsIgnoreCase(name)) theme = "";
    }

    public String rankBadge() {
        if (elo >= 1500) return "👑";
        if (elo >= 1200) return "💎";
        if (elo >= 1000) return "🥇";
        if (elo >= 800) return "🥈";
        if (elo >= 500) return "🥉";
        return "🪵";
    }
}
