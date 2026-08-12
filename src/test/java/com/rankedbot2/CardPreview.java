package com.rankedbot2;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.model.Player;
import com.rankedbot2.service.CardImageService;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Genera le card di esempio su file, per controllare il layout senza dover
 * lanciare il bot e invocare i comandi su Discord.
 *
 * mvn -q exec:java -Dexec.mainClass=com.rankedbot2.CardPreview -Dexec.classpathScope=test
 */
public class CardPreview {

    public static void main(String[] args) throws Exception {
        File runDir = new File("run");
        BotContext ctx = new BotContext(new File(runDir, "RankedBot"));
        CardImageService cards = new CardImageService(ctx);

        Player p = new Player();
        p.id = "1";
        p.ign = "Miriace";
        p.elo = 1240;
        p.peakElo = 1310;
        p.wins = 87;
        p.losses = 41;
        p.games = 128;
        p.winstreak = 6;
        p.lossstreak = 0;
        p.mvp = 23;
        p.strikes = 1;
        p.gold = 2450;
        p.level = 7;
        p.xp = 3200;

        Files.write(new File(runDir, "preview-stats.png").toPath(),
                cards.renderStats(null, p, "miriace"));

        List<Player> top = new ArrayList<>();
        String[] names = {"paniclfio", "Reeside", "Zeldryss", "Jamaicah", "leunammm",
                "universalprince", "andrew0w0", "3cstasyuser", "Gqbbo_", "Tqsh1ro"};
        int[] elos = {1000, 940, 880, 810, 760, 700, 640, 580, 520, 460};
        for (int i = 0; i < names.length; i++) {
            Player entry = new Player();
            entry.id = String.valueOf(i + 10);
            entry.ign = names[i];
            entry.elo = elos[i];
            entry.wins = 40 - i * 3;
            entry.losses = 10 + i;
            entry.mvp = 12 - i;
            top.add(entry);
        }

        Files.write(new File(runDir, "preview-leaderboard.png").toPath(),
                cards.renderLeaderboard(top, "elo"));

        ctx.shutdown();
        System.out.println("Card generate in run/");
    }
}
