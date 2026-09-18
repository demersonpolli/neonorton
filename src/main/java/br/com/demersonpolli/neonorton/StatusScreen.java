package br.com.demersonpolli.neonorton;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;

// TODO(spec: Formatting, status, and printer — status fields): the confirmed original status
// display also shows output file, format line length, tab display width, print margin/page
// lines, characters in edit buffer, unread input characters, and unused buffer/output-drive
// space — none of which exist yet (most depend on the F5/F7/CLI features that are still
// stubbed elsewhere). Extend the constructor/infoLines below once those fields exist rather
// than bolting them on ad hoc; keep fileName/lineCount/cursor/insertMode/wordWrap as-is.
public class StatusScreen implements AppScreen {

    private final String fileName;
    private final int    lineCount;
    private final int    cursorRow;
    private final int    cursorCol;
    private final boolean insertMode;
    private final boolean wordWrap;

    public StatusScreen(String fileName, int lineCount, int cursorRow, int cursorCol,
                        boolean insertMode, boolean wordWrap) {
        this.fileName   = fileName.isEmpty() ? "[No Name]" : fileName;
        this.lineCount  = lineCount;
        this.cursorRow  = cursorRow;
        this.cursorCol  = cursorCol;
        this.insertMode = insertMode;
        this.wordWrap   = wordWrap;
    }

    @Override
    public void show(MultiWindowTextGUI gui) {
        Screen screen = gui.getScreen();
        try {
            draw(screen);
            while (true) {
                KeyStroke key = screen.readInput();
                if (key.getKeyType() == KeyType.Escape) break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void draw(Screen screen) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        int rows = size.getRows();
        int cols = size.getColumns();

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.fill(' ');

        String[] infoLines = {
            "NeoNorton Editor - File Status",
            "",
            "  File       : " + fileName,
            "  Total lines: " + lineCount,
            "  Cursor     : Line " + (cursorRow + 1) + "  Col " + (cursorCol + 1),
            "  Edit mode  : " + (insertMode ? "Insert" : "Overwrite"),
            "  Word wrap  : " + (wordWrap   ? "On"     : "Off"),
            "",
            "  Press ESC to return to the editor",
        };

        int maxLen = 0;
        for (String l : infoLines) if (l.length() > maxLen) maxLen = l.length();
        int innerWidth  = maxLen + 4;
        int boxWidth    = innerWidth + 2;
        int boxHeight   = infoLines.length + 2;
        int boxStartCol = Math.max(0, (cols - boxWidth) / 2);
        int boxStartRow = Math.max(0, (rows - boxHeight) / 2);
        int textStartRow = boxStartRow + 1;

        // Top border ╔══╗
        StringBuilder top = new StringBuilder();
        top.append('\u2554');
        for (int i = 0; i < innerWidth; i++) top.append('\u2550');
        top.append('\u2557');
        tg.putString(boxStartCol, boxStartRow, top.toString());

        // Content rows
        for (int i = 0; i < infoLines.length; i++) {
            String text = infoLines[i];
            int rightPad = innerWidth - 2 - text.length();
            StringBuilder row = new StringBuilder();
            row.append('\u2551').append(" ").append(text);
            for (int s = 0; s < rightPad; s++) row.append(' ');
            row.append(' ').append('\u2551');
            if (i == 0) tg.enableModifiers(SGR.BOLD);
            tg.putString(boxStartCol, textStartRow + i, row.toString());
            if (i == 0) tg.disableModifiers(SGR.BOLD);
        }

        // Bottom border ╚══╝
        StringBuilder bot = new StringBuilder();
        bot.append('\u255A');
        for (int i = 0; i < innerWidth; i++) bot.append('\u2550');
        bot.append('\u255D');
        tg.putString(boxStartCol, textStartRow + infoLines.length, bot.toString());

        screen.setCursorPosition(null);
        screen.refresh();
    }
}
