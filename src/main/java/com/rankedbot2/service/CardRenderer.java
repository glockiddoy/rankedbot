package com.rankedbot2.service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;

/** Primitive di disegno condivise dalle card di statistiche e classifica. */
public class CardRenderer {

    public static final int WIDTH = 820;
    public static final int HEIGHT = 510;

    static final Color PANEL_FILL = new Color(255, 255, 255, 18);
    static final Color PANEL_STROKE = new Color(255, 255, 255, 48);
    static final Color TITLE = new Color(236, 238, 241);
    static final Color LABEL = new Color(214, 217, 222);
    static final Color VALUE = new Color(255, 209, 61);
    static final Color MUTED = new Color(150, 155, 163);

    private final Font baseFont;

    public CardRenderer(File fontsFolder, String fontName) {
        this.baseFont = loadFont(fontsFolder, fontName);
    }

    /**
     * Carica il font dalla cartella fonts/. Senza file, ripiega su un font di
     * sistema dall'aspetto simile prima di arrivare al sans-serif generico.
     */
    private static Font loadFont(File folder, String name) {
        if (folder != null && folder.isDirectory()) {
            for (String ext : new String[]{".ttf", ".otf"}) {
                File file = new File(folder, name + ext);
                if (file.isFile()) {
                    try {
                        return Font.createFont(Font.TRUETYPE_FONT, file);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        for (String candidate : new String[]{"Bahnschrift", "Segoe UI Semibold", "Verdana"}) {
            Font font = new Font(candidate, Font.PLAIN, 12);
            if (!font.getFamily().equalsIgnoreCase(Font.DIALOG)) return font;
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    public Font font(int size) {
        return baseFont.deriveFont(Font.PLAIN, size);
    }

    public Font fontBold(int size) {
        return baseFont.deriveFont(Font.BOLD, size);
    }

    public BufferedImage newCanvas() {
        return new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
    }

    public Graphics2D graphics(BufferedImage canvas) {
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /**
     * Sfondo della card: usa l'immagine indicata se esiste, scurita e sfocata,
     * altrimenti disegna una sfumatura grigia.
     */
    public void drawBackground(Graphics2D g, File backgroundImage) {
        BufferedImage picture = null;
        if (backgroundImage != null && backgroundImage.isFile()) {
            try {
                picture = ImageIO.read(backgroundImage);
            } catch (Exception ignored) {
            }
        }

        if (picture == null) {
            g.setPaint(new GradientPaint(0, 0, new Color(52, 54, 58), 0, HEIGHT, new Color(32, 33, 36)));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        } else {
            g.drawImage(blur(scaleToCover(picture)), 0, 0, null);
            // Velo scuro: abbastanza da far leggere il testo, non da nascondere l'immagine.
            g.setColor(new Color(8, 10, 14, 96));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        }

        // Vignettatura leggera: stacca i pannelli dai bordi.
        g.setColor(new Color(0, 0, 0, 45));
        g.fillRect(0, 0, WIDTH, 6);
        g.fillRect(0, HEIGHT - 6, WIDTH, 6);
    }

    private BufferedImage scaleToCover(BufferedImage source) {
        double scale = Math.max((double) WIDTH / source.getWidth(), (double) HEIGHT / source.getHeight());
        int w = (int) Math.ceil(source.getWidth() * scale);
        int h = (int) Math.ceil(source.getHeight() * scale);

        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(out);
        g.drawImage(source, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    /**
     * Sfocatura ottenuta rimpicciolendo e reingrandendo. Una sola passata a 1/5
     * lascia riconoscibile il soggetto, che è il punto di avere uno sfondo.
     */
    private BufferedImage blur(BufferedImage source) {
        BufferedImage out = source;
        for (int pass = 0; pass < 1; pass++) {
            BufferedImage small = new BufferedImage(WIDTH / 5, HEIGHT / 5, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gs = graphics(small);
            gs.drawImage(out, 0, 0, small.getWidth(), small.getHeight(), null);
            gs.dispose();

            BufferedImage big = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gb = graphics(big);
            gb.drawImage(small, 0, 0, WIDTH, HEIGHT, null);
            gb.dispose();
            out = big;
        }
        return out;
    }

    public void drawPanel(Graphics2D g, int x, int y, int w, int h) {
        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, 18, 18);
        g.setColor(PANEL_FILL);
        g.fill(shape);
        g.setColor(PANEL_STROKE);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(shape);
    }

    public void drawCentered(Graphics2D g, String text, int centerX, int y, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, centerX - width / 2, y);
    }

    public void drawLeft(Graphics2D g, String text, int x, int y, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    public void drawRight(Graphics2D g, String text, int rightX, int y, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, rightX - width, y);
    }

    /** Riga "etichetta ..... valore" dentro un pannello. */
    public void drawStatRow(Graphics2D g, String label, String value, int leftX, int rightX, int y, int size) {
        drawLeft(g, label, leftX, y, font(size), LABEL);
        drawRight(g, value, rightX, y, fontBold(size), VALUE);
    }

    public void drawFooter(Graphics2D g, String leftText, String rightText) {
        if (leftText != null && !leftText.isEmpty()) {
            drawLeft(g, leftText, 34, HEIGHT - 28, fontBold(20), new Color(120, 170, 255));
        }
        if (rightText != null && !rightText.isEmpty()) {
            drawRight(g, rightText, WIDTH - 34, HEIGHT - 28, font(18), MUTED);
        }
    }

    public static byte[] toPng(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
