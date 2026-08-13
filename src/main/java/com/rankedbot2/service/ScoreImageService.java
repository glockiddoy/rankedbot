package com.rankedbot2.service;

import com.rankedbot2.core.BotContext;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Immagine di fine partita: sfondo della mappa, nome della mappa al centro e una
 * riga di card per squadra con la variazione di elo di ogni giocatore.
 */
public class ScoreImageService {

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;

    private static final int CARD_HEIGHT = 104;
    private static final int CARD_GAP = 14;
    private static final int ROW_MARGIN = 24;
    private static final int MAX_CARD_WIDTH = 296;
    private static final int HEAD_SIZE = 64;

    private static final Color WIN_COLOR = new Color(96, 226, 130);
    private static final Color LOSS_COLOR = new Color(235, 96, 96);
    private static final Color NAME_COLOR = new Color(238, 240, 243);
    private static final Color CARD_FILL = new Color(12, 14, 18, 176);
    private static final Color CARD_STROKE = new Color(255, 255, 255, 46);
    private static final Color MVP_STROKE = new Color(255, 205, 66);
    private static final Duration HEAD_TIMEOUT = Duration.ofSeconds(4);

    /**
     * Dati della mappa che influenzano lo sfondo generato. I colori sono quelli
     * dei due letti configurati in `/config addmap`, così l'immagine richiama
     * la partita vera invece di essere una tinta a caso.
     */
    public static class MapStyle {
        public final String name;
        public final String teamColor1;
        public final String teamColor2;

        public MapStyle(String name, String teamColor1, String teamColor2) {
            this.name = name;
            this.teamColor1 = teamColor1;
            this.teamColor2 = teamColor2;
        }

        public static MapStyle of(String name) {
            return new MapStyle(name, null, null);
        }
    }

    /** Colori dei letti di Minecraft usati dalle mappe. */
    private static Color teamColor(String name, Color fallback) {
        if (name == null) return fallback;
        return switch (name.trim().toLowerCase()) {
            case "red" -> new Color(196, 48, 44);
            case "blue" -> new Color(53, 84, 191);
            case "green" -> new Color(58, 143, 53);
            case "yellow" -> new Color(216, 175, 42);
            case "aqua", "cyan" -> new Color(52, 168, 178);
            case "white" -> new Color(206, 209, 214);
            case "pink" -> new Color(198, 79, 149);
            case "gray", "grey" -> new Color(102, 108, 116);
            case "purple" -> new Color(126, 61, 176);
            case "orange" -> new Color(214, 118, 36);
            default -> fallback;
        };
    }

    /** Riga della card di un giocatore. */
    public static class Entry {
        public final String ign;
        public final int eloBefore;
        public final int eloAfter;
        public final int delta;
        public final boolean mvp;
        /** Testo alternativo alla variazione di elo, usato a inizio partita. */
        public final String label;

        public Entry(String ign, int eloBefore, int eloAfter, int delta, boolean mvp) {
            this(ign, eloBefore, eloAfter, delta, mvp, null);
        }

        public Entry(String ign, int eloBefore, int eloAfter, int delta, boolean mvp, String label) {
            this.ign = ign;
            this.eloBefore = eloBefore;
            this.eloAfter = eloAfter;
            this.delta = delta;
            this.mvp = mvp;
            this.label = label;
        }

        /** Card di inizio partita: elo attuale al posto della variazione. */
        public static Entry atStart(String ign, int elo, boolean captain) {
            return new Entry(ign, elo, elo, 0, captain, elo + " ELO");
        }
    }

    private final BotContext ctx;
    private final CardRenderer renderer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Map<String, BufferedImage> headCache = new ConcurrentHashMap<>();
    /** Un solo sfondo alla volta: tenerne di più sprecherebbe memoria sull'host. */
    private final Map<String, BufferedImage> backgroundCache = new ConcurrentHashMap<>();

    public ScoreImageService(BotContext ctx) {
        this.ctx = ctx;
        this.renderer = new CardRenderer(
                new File(ctx.dataFolder, "fonts"),
                ctx.config.getString("s-text-font", "Minecraft"));
    }

    /**
     * Disegna la card di fine partita. `mapName` finisce al centro e dà anche il
     * nome del file di sfondo cercato in RankedBot/maps/.
     */
    public byte[] render(String mapName, List<Entry> winners, List<Entry> losers) throws Exception {
        return render(MapStyle.of(mapName), winners, losers);
    }

    public byte[] render(MapStyle style, List<Entry> winners, List<Entry> losers) throws Exception {
        String mapName = style == null ? null : style.name;
        prefetchHeads(winners, losers);

        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(canvas);

        drawBackground(g, style);
        drawMapTitle(g, mapName);
        drawLogos(g);
        drawRow(g, winners, ROW_MARGIN, true);
        drawRow(g, losers, HEIGHT - ROW_MARGIN - CARD_HEIGHT, false);

        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        return out.toByteArray();
    }

    /**
     * Immagine di inizio partita: stesso sfondo del recap, ma i due team uno
     * sopra l'altro con "VS" al centro al posto del nome della mappa.
     */
    public byte[] renderGameStart(String mapName, String subtitle,
                                  List<Entry> team1, List<Entry> team2) throws Exception {
        return renderGameStart(MapStyle.of(mapName), subtitle, team1, team2);
    }

    public byte[] renderGameStart(MapStyle style, String subtitle,
                                  List<Entry> team1, List<Entry> team2) throws Exception {
        String mapName = style == null ? null : style.name;
        prefetchHeads(team1, team2);

        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(canvas);

        drawBackground(g, style);
        drawVersus(g, mapName, subtitle);
        drawLogos(g);
        drawRow(g, team1, ROW_MARGIN, true);
        drawRow(g, team2, HEIGHT - ROW_MARGIN - CARD_HEIGHT, false);

        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        return out.toByteArray();
    }

    private void drawVersus(Graphics2D g, String mapName, String subtitle) {
        int centerY = HEIGHT / 2;

        drawCentered(g, "VS", WIDTH / 2 + 4, centerY + 4, renderer.fontBold(120), new Color(0, 0, 0, 150));
        drawCentered(g, "VS", WIDTH / 2, centerY, renderer.fontBold(120), new Color(252, 249, 244));

        if (mapName != null && !mapName.trim().isEmpty()) {
            String trimmed = mapName.trim();
            String cleanName = trimmed.replaceAll("\\d+$", "").trim();
            if (cleanName.isEmpty()) cleanName = trimmed;
            String title = Character.toUpperCase(cleanName.charAt(0))
                    + (cleanName.length() > 1 ? cleanName.substring(1) : "");
            drawCentered(g, title, WIDTH / 2 + 3, centerY + 55, renderer.fontBold(42), new Color(0, 0, 0, 150));
            drawCentered(g, title, WIDTH / 2, centerY + 52, renderer.fontBold(42), new Color(255, 209, 61));
        }

        if (subtitle != null && !subtitle.isBlank()) {
            drawCentered(g, subtitle, WIDTH / 2 + 2, centerY - 88, renderer.fontBold(30), new Color(0, 0, 0, 150));
            drawCentered(g, subtitle, WIDTH / 2, centerY - 90, renderer.fontBold(30), new Color(214, 220, 230));
        }
    }

    private void drawLogos(Graphics2D g) {
        int rx = 36;
        int ry = HEIGHT / 2 - 25;
        g.setColor(new Color(15, 20, 28, 210));
        g.fillRoundRect(rx, ry, 50, 50, 16, 16);
        g.setColor(new Color(40, 150, 255));
        g.setStroke(new BasicStroke(2.0f));
        g.drawRoundRect(rx, ry, 50, 50, 16, 16);

        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.setColor(new Color(60, 180, 255));
        g.drawString("R", rx + 14, ry + 36);

        int cx = WIDTH - 126;
        int cy = HEIGHT / 2 - 22;
        g.setColor(new Color(15, 20, 28, 210));
        g.fillRoundRect(cx, cy, 90, 44, 16, 16);
        g.setColor(new Color(255, 170, 30));
        g.setStroke(new BasicStroke(2.0f));
        g.drawRoundRect(cx, cy, 90, 44, 16, 16);

        g.setFont(new Font("Minecraft", Font.BOLD, 15));
        g.setColor(new Color(255, 200, 50));
        g.drawString("CoralMC", cx + 10, cy + 27);
    }

    private Graphics2D graphics(BufferedImage canvas) {
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /**
     * Sfondo: l'immagine della mappa se c'è, altrimenti lo sfondo generico delle
     * card. A differenza di /stats resta nitido, è il soggetto dell'immagine.
     */
    private void drawBackground(Graphics2D g, MapStyle style) {
        String mapName = style == null ? null : style.name;
        BufferedImage picture = readImage(mapBackgroundFile(mapName), mapName);
        if (picture == null) {
            drawGeneratedBackground(g, style);
        } else {
            drawCover(g, picture);
        }

        // Bande scure dietro le due file di card, così i nomi restano leggibili
        // anche su mappe chiare.
        int band = ROW_MARGIN * 2 + CARD_HEIGHT;
        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 170), 0, band, new Color(0, 0, 0, 0)));
        g.fillRect(0, 0, WIDTH, band);
        g.setPaint(new GradientPaint(0, HEIGHT - band, new Color(0, 0, 0, 0), 0, HEIGHT, new Color(0, 0, 0, 170)));
        g.fillRect(0, HEIGHT - band, WIDTH, band);
    }

    /**
     * Sfondo disegnato quando la mappa non ha una sua immagine in RankedBot/maps/.
     * I colori derivano dal nome, così ogni mappa ha sempre lo stesso aspetto e
     * due mappe diverse non si confondono.
     */
    private void drawGeneratedBackground(Graphics2D g, MapStyle style) {
        String seed = style == null || style.name == null || style.name.isBlank()
                ? "ranked" : style.name.toLowerCase();
        float hue = Math.abs(seed.hashCode() % 360) / 360f;

        // Senza colori configurati si ripiega su due tinte derivate dal nome:
        // resta comunque una mappa sempre uguale a se stessa.
        Color first = teamColor(style == null ? null : style.teamColor1,
                Color.getHSBColor(hue, 0.55f, 0.55f));
        Color second = teamColor(style == null ? null : style.teamColor2,
                Color.getHSBColor((hue + 0.45f) % 1f, 0.55f, 0.55f));

        g.setPaint(new GradientPaint(0, 0, darken(first, 0.32f), WIDTH, HEIGHT, darken(second, 0.26f)));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // Diagonale che separa i due colori: richiama i due team che si affrontano.
        g.setColor(new Color(second.getRed(), second.getGreen(), second.getBlue(), 46));
        g.fillPolygon(new int[]{WIDTH, WIDTH, 0}, new int[]{0, HEIGHT, HEIGHT}, 3);

        // Isole stilizzate: rombi sfumati, il motivo visivo del bedwars.
        drawIsland(g, WIDTH * 0.18, HEIGHT * 0.34, 300, first);
        drawIsland(g, WIDTH * 0.82, HEIGHT * 0.66, 300, second);
        drawIsland(g, WIDTH * 0.50, HEIGHT * 0.50, 190, blend(first, second));

        g.setPaint(new java.awt.RadialGradientPaint(
                new java.awt.geom.Point2D.Float(WIDTH / 2f, HEIGHT / 2f), WIDTH * 0.62f,
                new float[]{0.55f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 120)}));
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }

    /** Rombo sfumato, come un'isola vista dall'alto. */
    private void drawIsland(Graphics2D g, double cx, double cy, int size, Color color) {
        int half = size / 2;
        g.setPaint(new java.awt.RadialGradientPaint(
                new java.awt.geom.Point2D.Double(cx, cy), size * 0.75f,
                new float[]{0f, 1f},
                new Color[]{new Color(color.getRed(), color.getGreen(), color.getBlue(), 60),
                        new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)}));
        g.fillPolygon(
                new int[]{(int) cx, (int) cx + half, (int) cx, (int) cx - half},
                new int[]{(int) cy - half / 2, (int) cy, (int) cy + half / 2, (int) cy},
                4);
    }

    private static Color darken(Color c, float factor) {
        return new Color(
                Math.round(c.getRed() * factor),
                Math.round(c.getGreen() * factor),
                Math.round(c.getBlue() * factor));
    }

    private static Color blend(Color a, Color b) {
        return new Color((a.getRed() + b.getRed()) / 2,
                (a.getGreen() + b.getGreen()) / 2,
                (a.getBlue() + b.getBlue()) / 2);
    }

    private File mapBackgroundFile(String mapName) {
        File maps = new File(ctx.dataFolder, "maps");
        if (mapName != null && !mapName.isBlank() && maps.isDirectory()) {
            String safe = mapName.replaceAll("[^a-zA-Z0-9-_ ]", "").trim();
            for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
                File file = new File(maps, safe.toLowerCase() + ext);
                if (file.isFile()) return file;
                file = new File(maps, safe + ext);
                if (file.isFile()) return file;
            }
        }
        // Nessun ripiego su themes/background.png: era lo stesso sfondo per ogni
        // partita. Senza immagine della mappa si disegna quello generato, che
        // almeno cambia da mappa a mappa.
        return null;
    }

    /**
     * Legge lo sfondo tenendolo in cache: il PNG della mappa pesa qualche MB e
     * ridecodificarlo a ogni scoring costava più del disegno stesso. La chiave
     * include la data di modifica, così sostituire il file ha effetto subito.
     */
    private BufferedImage readImage(File file, String mapName) {
        if (file != null && file.isFile()) {
            String key = file.getAbsolutePath() + "|" + file.lastModified();
            BufferedImage cached = backgroundCache.get(key);
            if (cached != null) return cached;

            try {
                BufferedImage image = ImageIO.read(file);
                if (image != null) {
                    backgroundCache.clear();
                    backgroundCache.put(key, image);
                    return image;
                }
            } catch (Exception ignored) {}
        }

        if (mapName != null && !mapName.isBlank()) {
            String safe = mapName.replaceAll("[^a-zA-Z0-9-_ ]", "").trim().toLowerCase();
            String key = "resource:" + safe;
            BufferedImage cached = backgroundCache.get(key);
            if (cached != null) return cached;

            for (String ext : new String[]{".jpg", ".png", ".jpeg"}) {
                try (var is = getClass().getResourceAsStream("/maps/" + safe + ext)) {
                    if (is != null) {
                        BufferedImage image = ImageIO.read(is);
                        if (image != null) {
                            backgroundCache.put(key, image);
                            return image;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    /** Riempie tutta la tela mantenendo le proporzioni, tagliando l'eccesso. */
    private void drawCover(Graphics2D g, BufferedImage source) {
        double scale = Math.max((double) WIDTH / source.getWidth(), (double) HEIGHT / source.getHeight());
        int w = (int) Math.ceil(source.getWidth() * scale);
        int h = (int) Math.ceil(source.getHeight() * scale);
        g.drawImage(source, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h, null);
    }

    private void drawMapTitle(Graphics2D g, String mapName) {
        if (mapName == null || mapName.trim().isEmpty()) return;

        String trimmed = mapName.trim();
        String title = Character.toUpperCase(trimmed.charAt(0)) + (trimmed.length() > 1 ? trimmed.substring(1) : "");
        Font font = renderer.fontBold(fitTitleSize(g, title));
        int baseline = HEIGHT / 2 + 24;

        drawCentered(g, title, WIDTH / 2 + 4, baseline + 4, font, new Color(0, 0, 0, 150));
        drawCentered(g, title, WIDTH / 2, baseline, font, new Color(250, 246, 240));
    }

    /** Rimpicciolisce il titolo finché non sta dentro la tela con un margine. */
    private int fitTitleSize(Graphics2D g, String title) {
        int size = 96;
        while (size > 36) {
            g.setFont(renderer.fontBold(size));
            if (g.getFontMetrics().stringWidth(title) <= WIDTH - 200) break;
            size -= 4;
        }
        return size;
    }

    private void drawRow(Graphics2D g, List<Entry> entries, int y, boolean isWinner) {
        if (entries == null || entries.isEmpty()) return;

        int count = entries.size();
        int available = WIDTH - ROW_MARGIN * 2 - CARD_GAP * (count - 1);
        int cardWidth = Math.min(MAX_CARD_WIDTH, available / count);
        int rowWidth = cardWidth * count + CARD_GAP * (count - 1);
        int x = (WIDTH - rowWidth) / 2;

        for (Entry entry : entries) {
            drawCard(g, entry, x, y, cardWidth, isWinner);
            x += cardWidth + CARD_GAP;
        }
    }

    private void drawCard(Graphics2D g, Entry entry, int x, int y, int width, boolean isWinner) {
        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, width, CARD_HEIGHT, 16, 16);
        g.setColor(CARD_FILL);
        g.fill(shape);

        Color border = entry.mvp
                ? MVP_STROKE
                : (isWinner ? new Color(50, 215, 110, 150) : new Color(235, 75, 75, 150));

        g.setColor(border);
        g.setStroke(new BasicStroke(entry.mvp ? 2.5f : 1.5f));
        g.draw(shape);

        int headX = x + 14;
        int headY = y + (CARD_HEIGHT - HEAD_SIZE) / 2;
        BufferedImage head = fetchHead(entry.ign);
        if (head != null) {
            g.drawImage(head, headX, headY, HEAD_SIZE, HEAD_SIZE, null);
        } else {
            g.setColor(new Color(255, 255, 255, 26));
            g.fillRect(headX, headY, HEAD_SIZE, HEAD_SIZE);
        }

        int textX = headX + HEAD_SIZE + 14;
        int textWidth = x + width - 14 - textX;

        // Niente simboli tipo ♛: il font Minecraft non li ha e uscirebbe un
        // quadratino. La targhetta è disegnata, quindi non dipende dal font.
        int badgeWidth = 0;
        if (entry.mvp) {
            String badge = entry.label != null ? "C" : "MVP";
            badgeWidth = drawBadge(g, badge, x + width - 12, y + 10) + 8;
        }
        drawFitted(g, entry.ign, textX, y + 44, textWidth - badgeWidth, 26, NAME_COLOR);

        String line;
        Color color;
        if (entry.label != null) {
            line = entry.label;
            color = new Color(255, 209, 61);
        } else {
            line = (entry.delta >= 0 ? "+" : "") + entry.delta
                    + " [" + entry.eloBefore + " > " + entry.eloAfter + "]";
            color = entry.delta >= 0 ? WIN_COLOR : LOSS_COLOR;
        }
        drawFitted(g, line, textX, y + 76, textWidth, 22, color);
    }

    /** Targhetta dorata in alto a destra della card. Ritorna la larghezza usata. */
    private int drawBadge(Graphics2D g, String text, int rightX, int y) {
        Font font = renderer.fontBold(15);
        g.setFont(font);

        int textWidth = g.getFontMetrics().stringWidth(text);
        int boxWidth = textWidth + 14;
        int boxHeight = 22;
        int x = rightX - boxWidth;

        g.setColor(new Color(255, 205, 66, 235));
        g.fillRoundRect(x, y, boxWidth, boxHeight, 8, 8);

        g.setColor(new Color(28, 22, 6));
        g.drawString(text, x + 7, y + boxHeight - 6);
        return boxWidth;
    }

    /** Scrive il testo rimpicciolendo il font finché non entra nello spazio dato. */
    private void drawFitted(Graphics2D g, String text, int x, int y, int maxWidth, int size, Color color) {
        int current = size;
        Font font = renderer.fontBold(current);
        g.setFont(font);
        while (current > 11 && g.getFontMetrics().stringWidth(text) > maxWidth) {
            current -= 1;
            font = renderer.fontBold(current);
            g.setFont(font);
        }
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(text, x + 2, y + 2);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private void drawCentered(Graphics2D g, String text, int centerX, int y, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, centerX - g.getFontMetrics().stringWidth(text) / 2, y);
    }

    /**
     * Scarica tutte le teste in parallelo prima di disegnare. In sequenza erano
     * 8 richieste una dopo l'altra, ed erano il grosso dell'attesa dello scoring.
     */
    private void prefetchHeads(List<Entry> winners, List<Entry> losers) {
        List<Entry> all = new ArrayList<>();
        if (winners != null) all.addAll(winners);
        if (losers != null) all.addAll(losers);

        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (Entry entry : all) {
            String url = headUrl(entry.ign);
            if (url == null || headCache.containsKey(url)) continue;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HEAD_TIMEOUT)
                    .GET()
                    .build();

            pending.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) return;
                        try {
                            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
                            if (image != null) headCache.put(url, image);
                        } catch (Exception ignored) {
                        }
                    })
                    .exceptionally(err -> null));
        }

        if (pending.isEmpty()) return;
        try {
            // Le teste sono un dettaglio: se il servizio non risponde in fretta si
            // disegna comunque, con il riquadro vuoto al posto dell'avatar.
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                    .get(HEAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private String headUrl(String ign) {
        if (ign == null || ign.isBlank()) return null;
        return "https://mc-heads.net/avatar/" + URLEncoder.encode(ign, StandardCharsets.UTF_8) + "/64";
    }

    /** Testa già scaricata da prefetchHeads; null se non disponibile. */
    private BufferedImage fetchHead(String ign) {
        String url = headUrl(ign);
        return url == null ? null : headCache.get(url);
    }
}
