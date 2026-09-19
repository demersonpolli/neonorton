package br.com.demersonpolli.neonorton;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * F5 S "Save editor configuration": a small on-disk preferences file for settings that would
 * otherwise reset every launch (tab width/mode, format width, word-wrap, indent, display
 * theme, cursor style, print margin/page length, Ins-key behavior). The spec doesn't recover a
 * historical filename or format for this, so the location and the Properties format here are a
 * portable design choice. A missing or corrupt file falls back to built-in defaults silently
 * rather than blocking startup.
 */
public class EditorConfig {
    public int tabWidth = 8;
    public String tabMode = "SPACES";
    public int wrapColumn = 0;
    public boolean wordWrap = false;
    public boolean indent = false;
    public String displayMode = "DA";
    public String cursorStyle = "REVERSED";
    public int printMarginLeft = 0;
    public int printPageLines = 0;
    public boolean insToggles = false;

    private static Path configPath() {
        return Paths.get(System.getProperty("user.home"), ".neonorton", "config.properties");
    }

    public static EditorConfig load() {
        EditorConfig cfg = new EditorConfig();
        Path path = configPath();
        if (!Files.exists(path)) return cfg;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
            cfg.tabWidth = parseInt(p.getProperty("tabWidth"), cfg.tabWidth);
            cfg.tabMode = p.getProperty("tabMode", cfg.tabMode);
            cfg.wrapColumn = parseInt(p.getProperty("wrapColumn"), cfg.wrapColumn);
            cfg.wordWrap = Boolean.parseBoolean(p.getProperty("wordWrap", String.valueOf(cfg.wordWrap)));
            cfg.indent = Boolean.parseBoolean(p.getProperty("indent", String.valueOf(cfg.indent)));
            cfg.displayMode = p.getProperty("displayMode", cfg.displayMode);
            cfg.cursorStyle = p.getProperty("cursorStyle", cfg.cursorStyle);
            cfg.printMarginLeft = parseInt(p.getProperty("printMarginLeft"), cfg.printMarginLeft);
            cfg.printPageLines = parseInt(p.getProperty("printPageLines"), cfg.printPageLines);
            cfg.insToggles = Boolean.parseBoolean(p.getProperty("insToggles", String.valueOf(cfg.insToggles)));
        } catch (IOException | RuntimeException e) {
            return new EditorConfig(); // corrupt or unreadable: safe defaults
        }
        return cfg;
    }

    public static boolean save(EditorConfig cfg) {
        Properties p = new Properties();
        p.setProperty("tabWidth", String.valueOf(cfg.tabWidth));
        p.setProperty("tabMode", cfg.tabMode);
        p.setProperty("wrapColumn", String.valueOf(cfg.wrapColumn));
        p.setProperty("wordWrap", String.valueOf(cfg.wordWrap));
        p.setProperty("indent", String.valueOf(cfg.indent));
        p.setProperty("displayMode", cfg.displayMode);
        p.setProperty("cursorStyle", cfg.cursorStyle);
        p.setProperty("printMarginLeft", String.valueOf(cfg.printMarginLeft));
        p.setProperty("printPageLines", String.valueOf(cfg.printPageLines));
        p.setProperty("insToggles", String.valueOf(cfg.insToggles));
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                p.store(out, "NeoNorton editor configuration");
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
