package com.rankedbot2.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Soglie XP di levels.yml / clanlevels.yml.
 * Formato di ogni livello: "<xp richiesto>;;;RICOMPENSA=valore,RICOMPENSA=valore"
 */
public class Levels {

    public static class Level {
        public final int number;
        public final int requiredXp;
        public final int goldReward;

        Level(int number, int requiredXp, int goldReward) {
            this.number = number;
            this.requiredXp = requiredXp;
            this.goldReward = goldReward;
        }
    }

    private final Config config;
    private List<Level> levels = new ArrayList<>();

    public Levels(File file) {
        this.config = new Config(file);
        parse();
    }

    public void reload() {
        config.reload();
        parse();
    }

    private void parse() {
        List<Level> parsed = new ArrayList<>();
        int total = config.getInt("total-levels", 0);
        for (int i = 1; i <= total; i++) {
            String raw = config.getString("l" + i);
            if (raw.isEmpty()) continue;

            String[] halves = raw.split(";;;");
            int xp;
            try {
                xp = Integer.parseInt(halves[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }

            int gold = 0;
            if (halves.length > 1) {
                for (String reward : halves[1].split(",")) {
                    String[] pair = reward.trim().split("=");
                    if (pair.length == 2 && pair[0].trim().equalsIgnoreCase("GOLD")) {
                        try {
                            gold = Integer.parseInt(pair[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            parsed.add(new Level(i, xp, gold));
        }
        levels = parsed;
    }

    public List<Level> all() {
        return levels;
    }

    /** Livello raggiunto con l'XP totale indicato. */
    public int levelFor(int totalXp) {
        int level = 0;
        for (Level l : levels) {
            if (totalXp >= l.requiredXp) level = l.number;
            else break;
        }
        return level;
    }

    /** XP necessario per il livello successivo, 0 se già al massimo. */
    public int xpForNextLevel(int currentLevel) {
        for (Level l : levels) {
            if (l.number == currentLevel + 1) return l.requiredXp;
        }
        return 0;
    }

    public int goldRewardFor(int level) {
        for (Level l : levels) {
            if (l.number == level) return l.goldReward;
        }
        return 0;
    }

    public int maxLevel() {
        return levels.isEmpty() ? 0 : levels.get(levels.size() - 1).number;
    }
}
