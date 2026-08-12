package com.rankedbot2.service;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.model.Clan;
import com.rankedbot2.model.Player;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rendering delle immagini stats con Java2D, usando le coordinate pixel,
 * i colori e le dimensioni definiti in config.yml.
 */
public class StatsImageService {

    /** Coordinata che disattiva il rendering di un valore (convenzione del config originale). */
    private static final int DISABLED_COORD = 9000;

    private final BotContext ctx;
    private final Map<String, Font> fontCache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public StatsImageService(BotContext ctx) {
        this.ctx = ctx;
    }

    /** Ritorna il PNG delle stats, oppure null se manca il tema di sfondo. */
    public byte[] renderPlayer(Guild guild, Player p) throws Exception {
        BufferedImage background = loadTheme(p.theme, "themes");
        if (background == null) return null;

        BufferedImage canvas = copy(background);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawSkin(g, p.ign);

        Map<String, String> values = new HashMap<>();
        values.put("ign", p.ign);
        values.put("ELO", String.valueOf(p.elo));
        values.put("PEAKELO", String.valueOf(p.peakElo));
        values.put("WINS", String.valueOf(p.wins));
        values.put("LOSSES", String.valueOf(p.losses));
        values.put("GAMES", String.valueOf(p.games));
        values.put("WINSTREAK", String.valueOf(p.winstreak));
        values.put("HIGHESTWS", String.valueOf(p.highestWs));
        values.put("LOSSSTREAK", String.valueOf(p.lossstreak));
        values.put("HIGHESTLS", String.valueOf(p.highestLs));
        values.put("WLR", String.format(java.util.Locale.ROOT, "%.2f", p.wlr()));
        values.put("MVP", String.valueOf(p.mvp));
        values.put("KILLS", String.valueOf(p.kills));
        values.put("DEATHS", String.valueOf(p.deaths));
        values.put("KDR", String.format(java.util.Locale.ROOT, "%.2f", p.kdr()));
        values.put("STRIKES", String.valueOf(p.strikes));
        values.put("SCORED", String.valueOf(p.scored));
        values.put("GOLD", String.valueOf(p.gold));
        values.put("LEVEL", String.valueOf(p.level));
        values.put("XP", String.valueOf(p.xp));
        values.put("needed-xp", String.valueOf(ctx.levels.xpForNextLevel(p.level)));
        values.put("theme", p.theme.isEmpty() ? "default" : p.theme);

        for (Map.Entry<String, String> entry : values.entrySet()) {
            drawValue(g, entry.getKey(), entry.getValue(), null);
        }

        if (p.isBanned()) drawValue(g, "banned", "BANNED", null);

        if (p.clanId >= 0) {
            Clan clan = ctx.clans.get(p.clanId);
            if (clan != null) drawValue(g, "clan", clan.name, null);
        }

        drawRankName(g, guild, p);

        g.dispose();
        return toPng(canvas);
    }

    /** Ritorna il PNG delle stats clan, oppure null se manca il tema di sfondo. */
    public byte[] renderClan(Guild guild, Clan clan) throws Exception {
        BufferedImage background = loadTheme(clan.theme, "clans");
        if (background == null) return null;

        BufferedImage canvas = copy(background);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int members = ctx.players.inClan(clan.id).size();
        int allElo = 0;
        int allGold = 0;
        for (Player member : ctx.players.inClan(clan.id)) {
            allElo += member.elo;
            allGold += member.gold;
        }

        Map<String, String> values = new HashMap<>();
        values.put("name", clan.name);
        values.put("reputation", String.valueOf(clan.reputation));
        values.put("description", clan.description);
        values.put("ranking", String.valueOf(ctx.clans.ranking(clan.id)));
        values.put("xp", String.valueOf(clan.xp));
        values.put("level", String.valueOf(clan.level));
        values.put("allelo", String.valueOf(allElo));
        values.put("allgold", String.valueOf(allGold));
        values.put("members", String.valueOf(members));
        values.put("invited", String.valueOf(clan.invited));
        values.put("cwplayed", String.valueOf(clan.cwPlayed));
        values.put("cwwins", String.valueOf(clan.cwWins));
        values.put("cwlosses", String.valueOf(clan.cwLosses));
        values.put("cwwlr", String.format(java.util.Locale.ROOT, "%.2f", clan.cwWlr()));

        Player leader = ctx.players.get(clan.leader);
        values.put("leaderign", leader == null ? "—" : leader.ign);

        for (Map.Entry<String, String> entry : values.entrySet()) {
            drawValue(g, entry.getKey(), entry.getValue(), null);
        }

        if (leader != null) drawLeaderSkin(g, leader.ign);

        g.dispose();
        return toPng(canvas);
    }

    /** Disegna un valore leggendo size/color/pixels da config.yml con il prefisso indicato. */
    private void drawValue(Graphics2D g, String key, String value, Color colorOverride) {
        int[] pixels = ctx.config.getPixels(key + "-pixels", null);
        if (pixels == null || pixels[0] >= DISABLED_COORD || pixels[1] >= DISABLED_COORD) return;

        int size = ctx.config.getInt(key + "-size", 30);
        Color color = colorOverride != null
                ? colorOverride
                : ctx.config.getColor(key + "-color", Color.WHITE);

        g.setFont(font(size));
        g.setColor(color);
        g.drawString(value == null ? "" : value, pixels[0], pixels[1]);
    }

    /** Il rank usa il colore del ruolo Discord, non un colore da config. */
    private void drawRankName(Graphics2D g, Guild guild, Player p) {
        var rank = ctx.ranks.forElo(p.elo);
        if (rank == null || guild == null) return;
        Role role = guild.getRoleById(rank.roleId);
        if (role == null) return;
        drawValue(g, "rbw-rank", role.getName(), role.getColor() == null ? Color.WHITE : role.getColor());
    }

    private void drawSkin(Graphics2D g, String ign) {
        int[] pixels = ctx.config.getPixels("skin-pixels", null);
        if (pixels == null || pixels[0] >= DISABLED_COORD) return;
        int size = ctx.config.getInt("skin-size", 400);
        BufferedImage skin = fetchSkin(ign, size);
        if (skin != null) g.drawImage(skin, pixels[0], pixels[1], null);
    }

    private void drawLeaderSkin(Graphics2D g, String ign) {
        int[] pixels = ctx.config.getPixels("leaderskin-pixels", null);
        if (pixels == null || pixels[0] >= DISABLED_COORD) return;
        int size = ctx.config.getInt("leaderskin-size", 240);
        BufferedImage skin = fetchSkin(ign, size);
        if (skin != null) g.drawImage(skin, pixels[0], pixels[1], null);
    }

    /** Scarica la skin Minecraft. Ritorna null se offline o nome non valido. */
    private BufferedImage fetchSkin(String ign, int size) {
        try {
            String safeIgn = URLEncoder.encode(ign, StandardCharsets.UTF_8);
            URI uri = URI.create("https://mc-heads.net/body/" + safeIgn + "/" + size);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;
            return ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Carica il tema a coordinate: solo il tema scelto dal giocatore oppure
     * theme.png. Niente fallback su un PNG qualsiasi della cartella, altrimenti
     * background.png (che serve alle card generate) verrebbe scambiato per un tema.
     */
    private BufferedImage loadTheme(String themeName, String folderName) throws Exception {
        File folder = new File(ctx.dataFolder, folderName);
        if (!folder.isDirectory()) return null;

        if (themeName != null && !themeName.isBlank() && !themeName.equalsIgnoreCase("background")) {
            File specific = new File(folder, themeName + ".png");
            if (specific.isFile()) return ImageIO.read(specific);
        }

        File defaultTheme = new File(folder, "theme.png");
        return defaultTheme.isFile() ? ImageIO.read(defaultTheme) : null;
    }

    /** Carica il font da fonts/, con fallback su un font di sistema. */
    private Font font(int size) {
        String name = ctx.config.getString("s-text-font", "Minecraft");
        Font base = fontCache.computeIfAbsent(name, key -> {
            File folder = new File(ctx.dataFolder, "fonts");
            for (String ext : new String[]{".ttf", ".otf"}) {
                File file = new File(folder, key + ext);
                if (file.isFile()) {
                    try {
                        return Font.createFont(Font.TRUETYPE_FONT, file);
                    } catch (Exception ignored) {
                    }
                }
            }
            return new Font(Font.SANS_SERIF, Font.BOLD, 12);
        });
        return base.deriveFont(Font.PLAIN, size);
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static byte[] toPng(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
