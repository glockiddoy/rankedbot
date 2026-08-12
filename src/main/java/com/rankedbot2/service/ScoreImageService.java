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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Riga della card di un giocatore. */
    public static class Entry {
        public final String ign;
        public final int eloBefore;
        public final int eloAfter;
        public final int delta;
        public final boolean mvp;

        public Entry(String ign, int eloBefore, int eloAfter, int delta, boolean mvp) {
            this.ign = ign;
            this.eloBefore = eloBefore;
            this.eloAfter = eloAfter;
            this.delta = delta;
            this.mvp = mvp;
        }
    }

    private final BotContext ctx;
    private final CardRenderer renderer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final Map<String, BufferedImage> headCache = new ConcurrentHashMap<>();

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
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(canvas);

        drawBackground(g, mapName);
        drawMapTitle(g, mapName);
        drawLogos(g);
        drawRow(g, winners, ROW_MARGIN, true);
        drawRow(g, losers, HEIGHT - ROW_MARGIN - CARD_HEIGHT, false);

        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        return out.toByteArray();
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
    private void drawBackground(Graphics2D g, String mapName) {
        BufferedImage picture = readImage(mapBackgroundFile(mapName));
        if (picture == null) {
            g.setPaint(new GradientPaint(0, 0, new Color(58, 62, 68), 0, HEIGHT, new Color(26, 28, 32)));
            g.fillRect(0, 0, WIDTH, HEIGHT);
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
        File fallback = new File(new File(ctx.dataFolder, "themes"), "background.png");
        return fallback.isFile() ? fallback : null;
    }

    private BufferedImage readImage(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            return ImageIO.read(file);
        } catch (Exception e) {
            return null;
        }
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

        String name = entry.mvp ? "♛ " + entry.ign : entry.ign;
        drawFitted(g, name, textX, y + 44, textWidth, 26, NAME_COLOR);

        Color color = entry.delta >= 0 ? WIN_COLOR : LOSS_COLOR;
        String delta = (entry.delta >= 0 ? "+" : "") + entry.delta
                + " [" + entry.eloBefore + " > " + entry.eloAfter + "]";
        drawFitted(g, delta, textX, y + 76, textWidth, 22, color);
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

    /** Testa del giocatore dal servizio pubblico; null se non raggiungibile. */
    private BufferedImage fetchHead(String ign) {
        if (ign == null || ign.isBlank()) return null;

        String url = "https://mc-heads.net/avatar/" + URLEncoder.encode(ign, StandardCharsets.UTF_8) + "/64";
        BufferedImage cached = headCache.get(url);
        if (cached != null) return cached;

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (image != null) headCache.put(url, image);
            return image;
        } catch (Exception e) {
            return null;
        }
    }
}
