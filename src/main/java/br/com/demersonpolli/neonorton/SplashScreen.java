package br.com.demersonpolli.neonorton;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;

public class SplashScreen implements AppScreen {

    private String fileName = "";

    public String getFileName() {
        return fileName;
    }

    @Override
    public void show(MultiWindowTextGUI gui) {
        Screen screen = gui.getScreen();
        try {
            drawScreen(screen);

            // --- Phase 1: collect file name ---
            StringBuilder input = new StringBuilder();
            int cols = screen.getTerminalSize().getColumns();

            while (true) {
                KeyStroke key = screen.readInput();
                KeyType type = key.getKeyType();

                if (type == KeyType.Enter) {
                    fileName = input.toString();
                    break;
                } else if (type == KeyType.Backspace) {
                    if (input.length() > 0) {
                        input.deleteCharAt(input.length() - 1);
                    }
                } else if (type == KeyType.Character) {
                    input.append(key.getCharacter());
                }

                // Redraw input line
                TextGraphics tg = screen.newTextGraphics();
                tg.setForegroundColor(TextColor.ANSI.WHITE);
                tg.setBackgroundColor(TextColor.ANSI.BLACK);
                StringBuilder blank = new StringBuilder();
                for (int i = 0; i < cols; i++) blank.append(' ');
                tg.putString(0, 1, blank.toString());
                tg.putString(0, 1, input.toString());
                screen.setCursorPosition(new TerminalPosition(input.length(), 1));
                screen.refresh();
            }

            // --- Phase 2: clear top rows, show prompts, wait for key ---
            clearTopRows(screen);
            drawPrompts(screen);

            while (true) {
                KeyStroke key = screen.readInput();
                if (key.getKeyType() == KeyType.F1) {
                    new HelpScreen().show(gui);
                    // Redraw splash prompts after returning from help
                    drawScreen(screen);
                    clearTopRows(screen);
                    drawPrompts(screen);
                } else {
                    // Any other key proceeds to the editor
                    break;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void clearTopRows(Screen screen) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        String blank = " ".repeat(cols);
        tg.putString(0, 0, blank);
        tg.putString(0, 1, blank);
        tg.putString(0, 2, blank);
        screen.setCursorPosition(null); // hide cursor
        screen.refresh();
    }

    private void drawPrompts(Screen screen) throws IOException {
        int rows = screen.getTerminalSize().getRows();
        int cols = screen.getTerminalSize().getColumns();
        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        String blank = " ".repeat(cols);
        tg.putString(0, rows - 2, blank);
        tg.putString(0, rows - 1, blank);
        tg.putString(0, rows - 2, "Press F1 for help.");
        tg.putString(0, rows - 1, "Press any key to begin");
        screen.setCursorPosition(null); // hide cursor
        screen.refresh();
    }

    private void drawScreen(Screen screen) throws IOException {
        int cols = screen.getTerminalSize().getColumns();

        TextGraphics tg = screen.newTextGraphics();

        // Fill entire screen with black background
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.fill(' ');

        // Row 0: "Enter file name:" in bright (bold) white
        tg.enableModifiers(SGR.BOLD);
        tg.putString(0, 0, "Enter file name:");
        tg.disableModifiers(SGR.BOLD);

        // Row 2: horizontal rule in light (bold) white
        tg.enableModifiers(SGR.BOLD);
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < cols; i++) rule.append('\u2500'); // ─
        tg.putString(0, 2, rule.toString());
        tg.disableModifiers(SGR.BOLD);

        // Splash content — regular (non-bold) white
        tg.setForegroundColor(TextColor.ANSI.WHITE);

        String[] splashLines = {
            "Neo-Norton Editor",
            "A Classic Programmer's Editor",
            "Version 1.00",
            "(C) Copyright 2026 Lucky Tech"
        };

        // Calculate box dimensions
        int maxLen = 0;
        for (String l : splashLines) if (l.length() > maxLen) maxLen = l.length();
        int innerWidth  = maxLen + 4; // 2 spaces padding each side
        int boxWidth    = innerWidth + 2; // + 2 border chars
        int boxStartCol = Math.max(0, (cols - boxWidth) / 2);
        int boxStartRow = 3; // one row above the first splash line (row 4)
        int textStartRow = boxStartRow + 1;

        // Top border: ╔═══╗
        StringBuilder top = new StringBuilder();
        top.append('\u2554'); // ╔
        for (int i = 0; i < innerWidth; i++) top.append('\u2550'); // ═
        top.append('\u2557'); // ╗
        tg.putString(boxStartCol, boxStartRow, top.toString());

        // Side rows with centered text
        for (int i = 0; i < splashLines.length; i++) {
            String text = splashLines[i];
            int leftPad  = (innerWidth - text.length()) / 2;
            int rightPad = innerWidth - text.length() - leftPad;
            StringBuilder row = new StringBuilder();
            row.append('\u2551'); // ║
            for (int s = 0; s < leftPad;  s++) row.append(' ');
            row.append(text);
            for (int s = 0; s < rightPad; s++) row.append(' ');
            row.append('\u2551'); // ║
            tg.putString(boxStartCol, textStartRow + i, row.toString());
        }

        // Bottom border: ╚═══╝
        StringBuilder bot = new StringBuilder();
        bot.append('\u255A'); // ╚
        for (int i = 0; i < innerWidth; i++) bot.append('\u2550'); // ═
        bot.append('\u255D'); // ╝
        tg.putString(boxStartCol, textStartRow + splashLines.length, bot.toString());

        screen.setCursorPosition(new TerminalPosition(0, 1));
        screen.refresh();
    }
}
