package com.peppone;

import org.yaml.snakeyaml.Yaml;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

public class Config {

    private final Map<String, Object> values = new HashMap<>();

    public Config(File file) {
        if (file != null && file.exists()) {
            try (InputStream is = new FileInputStream(file)) {
                Yaml yaml = new Yaml();
                Map<String, Object> loaded = yaml.load(is);
                if (loaded != null) {
                    values.putAll(loaded);
                }
            } catch (Exception e) {
                System.err.println("[PepponeConfig] Errore nel caricamento di " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    public boolean has(String key) {
        Object val = values.get(key);
        return val != null && !String.valueOf(val).isBlank();
    }

    public String getString(String key, String defaultValue) {
        Object val = values.get(key);
        if (val == null || String.valueOf(val).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(val).trim();
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        if (has(key)) {
            return Boolean.parseBoolean(getString(key, String.valueOf(defaultValue)));
        }
        return defaultValue;
    }

    public long getId(String key) {
        String str = getString(key, "").replaceAll("[^0-9]", "");
        if (str.isEmpty()) return 0L;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public List<String> getList(String key) {
        List<String> result = new ArrayList<>();
        String raw = getString(key, "");
        if (!raw.isEmpty()) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    public Color getColor(String key, Color defaultColor) {
        String raw = getString(key, "");
        String[] parts = raw.split(",");
        if (parts.length < 3) return defaultColor;
        try {
            int r = clamp(Integer.parseInt(parts[0].trim()));
            int g = clamp(Integer.parseInt(parts[1].trim()));
            int b = clamp(Integer.parseInt(parts[2].trim()));
            return new Color(r, g, b);
        } catch (NumberFormatException e) {
            return defaultColor;
        }
    }

    private static int clamp(int val) {
        return Math.max(0, Math.min(255, val));
    }
}
