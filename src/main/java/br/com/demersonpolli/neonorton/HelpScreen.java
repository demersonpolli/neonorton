package br.com.demersonpolli.neonorton;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;

public class HelpScreen implements AppScreen {

    private static final String[] HELP_LINES = {
        "NeoNorton Editor - Help",
        "",
        "  F1          Show this help",
        "  ESC         Close help",
        "  F3          File operations (Quit, Save, ...)",
        "  Arrows      Move cursor",
        "  Home / End  Start / end of line",
        "  Enter       New line",
        "  Backspace   Delete character left",
        "  Delete      Delete character right",
        "",
        "  Press ESC to go back",
    };

    @Override
    public void show(MultiWindowTextGUI gui) {
        Screen screen = gui.getScreen();
        try {
            drawHelp(screen);
            // Wait until ESC is pressed
            while (true) {
                KeyStroke key = screen.readInput();
                if (key.getKeyType() == KeyType.Escape) break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void drawHelp(Screen screen) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        int rows = size.getRows();
        int cols = size.getColumns();

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.fill(' ');

        // Box dimensions
        int maxLen = 0;
        for (String l : HELP_LINES) if (l.length() > maxLen) maxLen = l.length();
        int innerWidth  = maxLen + 4;
        int boxWidth    = innerWidth + 2;
        int boxHeight   = HELP_LINES.length + 2;
        int boxStartCol = Math.max(0, (cols - boxWidth) / 2);
        int boxStartRow = Math.max(0, (rows - boxHeight) / 2);
        int textStartRow = boxStartRow + 1;

        // Top border ╔═══╗
        StringBuilder top = new StringBuilder();
        top.append('\u2554');
        for (int i = 0; i < innerWidth; i++) top.append('\u2550');
        top.append('\u2557');
        tg.putString(boxStartCol, boxStartRow, top.toString());

        // Content rows
        for (int i = 0; i < HELP_LINES.length; i++) {
            String text = HELP_LINES[i];
            int rightPad = innerWidth - 2 - text.length();
            StringBuilder row = new StringBuilder();
            row.append('\u2551').append(" ").append(text);
            for (int s = 0; s < rightPad; s++) row.append(' ');
            row.append(' ').append('\u2551');
            // Bold the title line
            if (i == 0) tg.enableModifiers(SGR.BOLD);
            tg.putString(boxStartCol, textStartRow + i, row.toString());
            if (i == 0) tg.disableModifiers(SGR.BOLD);
        }

        // Bottom border ╚═══╝
        StringBuilder bot = new StringBuilder();
        bot.append('\u255A');
        for (int i = 0; i < innerWidth; i++) bot.append('\u2550');
        bot.append('\u255D');
        tg.putString(boxStartCol, textStartRow + HELP_LINES.length, bot.toString());

        screen.setCursorPosition(null);
        screen.refresh();
    }
}
