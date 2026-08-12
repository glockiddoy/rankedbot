package com.rankedbot2.service;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.model.Clan;
import com.rankedbot2.model.Player;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Genera le immagini di /stats e /leaderboard disegnandole da zero. */
public class CardImageService {

    private final BotContext ctx;
    private final CardRenderer renderer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final Map<String, BufferedImage> skinCache = new ConcurrentHashMap<>();

    public CardImageService(BotContext ctx) {
        this.ctx = ctx;
        this.renderer = new CardRenderer(
                new File(ctx.dataFolder, "fonts"),
                ctx.config.getString("s-text-font", "Minecraft"));
    }

    /** Sfondo opzionale: RankedBot/themes/background.png se presente. */
    private File backgroundFile() {
        File file = new File(new File(ctx.dataFolder, "themes"), "background.png");
        return file.isFile() ? file : null;
    }

    private String footerRight() {
        return ctx.config.getString("server-name", "");
    }

    public byte[] renderStats(Guild guild, Player p, String discordName) throws Exception {
        BufferedImage canvas = renderer.newCanvas();
        Graphics2D g = renderer.graphics(canvas);
        renderer.drawBackground(g, backgroundFile());

        // Pannello rank
        renderer.drawPanel(g, 52, 62, 232, 140);
        renderer.drawCentered(g, "Rank", 168, 96, renderer.fontBold(26), CardRenderer.TITLE);

        String rankName = "Unranked";
        Color rankColor = CardRenderer.VALUE;
        var rank = ctx.ranks.forElo(p.elo);
        if (rank != null && guild != null) {
            Role role = guild.getRoleById(rank.roleId);
            if (role != null) {
                rankName = role.getName();
                if (role.getColor() != null) rankColor = role.getColor();
            }
        }
        renderer.drawCentered(g, rankName, 168, 142, renderer.fontBold(30), rankColor);
        renderer.drawCentered(g, String.valueOf(p.elo), 168, 182, renderer.fontBold(28), CardRenderer.VALUE);

        // Pannello centrale con nome e skin
        renderer.drawPanel(g, 292, 62, 236, 352);
        renderer.drawCentered(g, p.ign, 410, 104, renderer.fontBold(30), CardRenderer.TITLE);
        if (discordName != null && !discordName.isBlank()) {
            renderer.drawCentered(g, "@" + discordName, 410, 130, renderer.font(18), CardRenderer.VALUE);
        }

        BufferedImage skin = fetchImage("https://mc-heads.net/body/"
                + URLEncoder.encode(p.ign, StandardCharsets.UTF_8) + "/220");
        drawSkinInBox(g, skin, 302, 146, 216, 258);

        // Pannello riepilogo a destra
        renderer.drawPanel(g, 536, 62, 232, 140);
        renderer.drawCentered(g, "Progressi", 652, 96, renderer.fontBold(26), CardRenderer.TITLE);
        renderer.drawStatRow(g, "Livello:", String.valueOf(p.level), 560, 748, 130, 19);
        renderer.drawStatRow(g, "XP:", String.valueOf(p.xp), 560, 748, 158, 19);
        renderer.drawStatRow(g, "Gold:", String.valueOf(p.gold), 560, 748, 186, 19);

        // Statistiche principali
        renderer.drawPanel(g, 52, 212, 232, 202);
        renderer.drawCentered(g, "Statistics", 168, 248, renderer.fontBold(26), CardRenderer.TITLE);
        renderer.drawStatRow(g, "Games Played:", String.valueOf(p.games), 70, 268, 282, 18);
        renderer.drawStatRow(g, "Games Won:", String.valueOf(p.wins), 70, 268, 308, 18);
        renderer.drawStatRow(g, "Games Lost:", String.valueOf(p.losses), 70, 268, 334, 18);
        renderer.drawStatRow(g, "Peak Elo:", String.valueOf(p.peakElo), 70, 268, 368, 18);
        renderer.drawStatRow(g, "MVP(s):", String.valueOf(p.mvp), 70, 268, 394, 18);

        // Statistiche secondarie
        renderer.drawPanel(g, 536, 212, 232, 202);
        renderer.drawCentered(g, "Other Statistics", 652, 248, renderer.fontBold(24), CardRenderer.TITLE);
        renderer.drawStatRow(g, "WS:", String.valueOf(p.winstreak), 556, 752, 282, 18);
        renderer.drawStatRow(g, "LS:", String.valueOf(p.lossstreak), 556, 752, 308, 18);
        renderer.drawStatRow(g, "WLR:", String.format(java.util.Locale.ROOT, "%.2f", p.wlr()), 556, 752, 334, 18);
        renderer.drawStatRow(g, "Strikes:", String.valueOf(p.strikes), 556, 752, 368, 18);

        String clanName = "-";
        if (p.clanId >= 0) {
            Clan clan = ctx.clans.get(p.clanId);
            if (clan != null) clanName = clan.name;
        }
        renderer.drawStatRow(g, "Clan:", clanName, 556, 752, 394, 18);

        if (p.isBanned()) {
            renderer.drawCentered(g, "BANNED", 410, 440, renderer.fontBold(24), new Color(240, 90, 90));
        }

        renderer.drawFooter(g, "R", footerRight());
        g.dispose();
        return CardRenderer.toPng(canvas);
    }

    public byte[] renderLeaderboard(List<Player> players, String statLabel) throws Exception {
        BufferedImage canvas = renderer.newCanvas();
        Graphics2D g = renderer.graphics(canvas);
        renderer.drawBackground(g, backgroundFile());

        renderer.drawPanel(g, 30, 30, 760, 440);
        renderer.drawCentered(g, "Leaderboard — " + statLabel, 410, 72, renderer.fontBold(28), CardRenderer.TITLE);

        int nameX = 110;
        int[] columns = {430, 530, 640, 740};
        String[] headers = {"Elo", "Wins", "Losses", "MVP(s)"};

        renderer.drawLeft(g, "Name", nameX, 112, renderer.fontBold(19), CardRenderer.LABEL);
        for (int i = 0; i < headers.length; i++) {
            renderer.drawRight(g, headers[i], columns[i], 112, renderer.fontBold(19), CardRenderer.LABEL);
        }

        int y = 150;
        int position = 1;
        for (Player p : players) {
            BufferedImage head = fetchImage("https://mc-heads.net/avatar/"
                    + URLEncoder.encode(p.ign, StandardCharsets.UTF_8) + "/26");
            if (head != null) g.drawImage(head, 68, y - 20, 26, 26, null);

            Color nameColor = position == 1 ? new Color(255, 120, 120) : CardRenderer.VALUE;
            renderer.drawLeft(g, p.ign, nameX, y, renderer.fontBold(20), nameColor);

            String[] values = {
                    String.valueOf(p.elo),
                    String.valueOf(p.wins),
                    String.valueOf(p.losses),
                    String.valueOf(p.mvp),
            };
            for (int i = 0; i < values.length; i++) {
                renderer.drawRight(g, values[i], columns[i], y, renderer.font(20), CardRenderer.VALUE);
            }

            y += 31;
            position++;
            if (y > 450) break;
        }

        renderer.drawFooter(g, "R", footerRight());
        g.dispose();
        return CardRenderer.toPng(canvas);
    }

    /**
     * Disegna la skin dentro il riquadro indicato mantenendo le proporzioni:
     * il render del corpo è alto circa il doppio della larghezza e senza questo
     * uscirebbe dal pannello.
     */
    private void drawSkinInBox(Graphics2D g, BufferedImage skin, int x, int y, int boxWidth, int boxHeight) {
        if (skin == null) return;

        double scale = Math.min((double) boxWidth / skin.getWidth(), (double) boxHeight / skin.getHeight());
        int w = (int) Math.round(skin.getWidth() * scale);
        int h = (int) Math.round(skin.getHeight() * scale);

        g.drawImage(skin, x + (boxWidth - w) / 2, y + (boxHeight - h) / 2, w, h, null);
    }

    /** Scarica skin o testa dal servizio pubblico. Ritorna null se non raggiungibile. */
    private BufferedImage fetchImage(String url) {
        BufferedImage cached = skinCache.get(url);
        if (cached != null) return cached;

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (image != null) skinCache.put(url, image);
            return image;
        } catch (Exception e) {
            return null;
        }
    }
}
