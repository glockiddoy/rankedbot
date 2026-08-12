package com.rankedbot2.config;

import java.io.File;

public class Messages {

    private final Config config;

    public Messages(File file) {
        this.config = new Config(file);
    }

    public void reload() {
        config.reload();
    }

    public String get(String key) {
        String value = config.getString(key);
        return value.isEmpty() ? key : value;
    }

    public String get(String key, String fallback) {
        String value = config.getString(key);
        return value.isEmpty() ? fallback : value;
    }
}
