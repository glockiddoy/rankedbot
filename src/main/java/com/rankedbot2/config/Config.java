package com.rankedbot2.config;

import org.yaml.snakeyaml.Yaml;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Config {

    private final File file;
    private Map<String, Object> values = new LinkedHashMap<>();

    public Config(File file) {
        this.file = file;
        reload();
    }

    public void reload() {
        try (InputStream in = new FileInputStream(file)) {
            Map<String, Object> loaded = new Yaml().load(in);
            values = loaded == null ? new LinkedHashMap<>() : loaded;
        } catch (Exception e) {
            throw new IllegalStateException("Impossibile leggere " + file.getName() + ": " + e.getMessage(), e);
        }
    }

    public boolean has(String key) {
        Object v = values.get(key);
        return v != null && !String.valueOf(v).isBlank();
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /**
     * Valore senza trim: serve dove gli spazi contano, come il separatore tra
     * prefisso elo e nome ("[%elo%] " perderebbe lo spazio finale).
     */
    public String getRawString(String key, String def) {
        Object v = values.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public String getString(String key, String def) {
        return has(key) ? getString(key) : def;
    }

    public int getInt(String key, int def) {
        if (!has(key)) return def;
        try {
            return (int) Double.parseDouble(getString(key));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public double getDouble(String key, double def) {
        if (!has(key)) return def;
        try {
            return Double.parseDouble(getString(key));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBoolean(String key, boolean def) {
        if (!has(key)) return def;
        return Boolean.parseBoolean(getString(key));
    }

    /** Legge un ID Discord. Ritorna 0 se assente o non valido. */
    public long getId(String key) {
        if (!has(key)) return 0L;
        try {
            return Long.parseLong(getString(key).replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Legge una lista di ID separati da virgola. */
    public List<Long> getIdList(String key) {
        List<Long> out = new ArrayList<>();
        if (!has(key)) return out;
        for (String part : getString(key).split(",")) {
            String clean = part.replaceAll("[^0-9]", "");
            if (!clean.isEmpty()) {
                try {
                    out.add(Long.parseLong(clean));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    public List<String> getStringList(String key) {
        List<String> out = new ArrayList<>();
        if (!has(key)) return out;
        for (String part : getString(key).split(",")) {
            String clean = part.trim();
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    /** Legge un colore in formato "R,G,B". */
    public Color getColor(String key, Color def) {
        if (!has(key)) return def;
        String[] parts = getString(key).split(",");
        if (parts.length < 3) return def;
        try {
            return new Color(
                    clamp(Integer.parseInt(parts[0].trim())),
                    clamp(Integer.parseInt(parts[1].trim())),
                    clamp(Integer.parseInt(parts[2].trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Legge una coppia di coordinate "x,y". */
    public int[] getPixels(String key, int[] def) {
        if (!has(key)) return def;
        String[] parts = getString(key).split(",");
        if (parts.length < 2) return def;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
