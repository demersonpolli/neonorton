/*
 * MIT License
 *
 * Copyright (c) 2026 NeoNorton Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package br.com.demersonpolli.neonorton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Central place to record handled exceptions to disk instead of stderr, which a
 * Swing-hosted SwingTerminalFrame app has no visible console for. Logs to
 * ~/.neonorton/neonorton.log, next to EditorConfig's config.properties. If the log
 * file itself can't be set up, errors are swallowed rather than blocking the caller -
 * logging failures must never take down the editor.
 */
public final class LogRegistry {
    private static final Logger LOGGER = Logger.getLogger("NeoNorton");
    private static boolean initialized = false;

    private LogRegistry() {
    }

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".neonorton");
            Files.createDirectories(dir);
            FileHandler handler = new FileHandler(dir.resolve("neonorton.log").toString(), true);
            handler.setFormatter(new SimpleFormatter());
            LOGGER.setUseParentHandlers(false);
            LOGGER.addHandler(handler);
        } catch (IOException | SecurityException e) {
            // No writable log file available; fall back to silence rather than stderr noise.
        }
    }

    public static void error(String message, Throwable t) {
        init();
        LOGGER.log(Level.SEVERE, message, t);
    }
}
