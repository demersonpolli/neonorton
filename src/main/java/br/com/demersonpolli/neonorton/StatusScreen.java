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

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;

public class StatusScreen implements AppScreen {

    /**
     * Snapshot of everything the F2 status screen shows, per the spec's confirmed field list:
     * input file, output file, format line length, tab display width, print margin/page lines,
     * characters in edit buffer, unread input characters, unused buffer/output-drive space,
     * line/column, Insert or Replace, word-wrap state, and Indent.
     */
    public record StatusInfo(
        String inputFile,
        String outputFile,
        int totalLines,
        int cursorRow,
        int cursorCol,
        boolean insertMode,
        boolean wordWrap,
        boolean indent,
        int wrapColumn,
        int tabWidth,
        int printMarginLeft,
        int printPageLines,
        long bufferCharCount,
        long unreadInputChars,
        long freeDiskSpaceBytes
    ) {}

    private final StatusInfo info;

    public StatusScreen(StatusInfo info) {
        this.info = info;
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

    /** Human-readable byte count (B/KB/MB/GB); -1 means "unknown". */
    private static String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.1f GB", mb / 1024.0);
    }

    private void draw(Screen screen) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        int rows = size.getRows();
        int cols = size.getColumns();

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.fill(' ');

        String inputDisplay  = info.inputFile().isEmpty() ? "[No Name]" : info.inputFile();
        String outputDisplay = (info.outputFile() == null || info.outputFile().isEmpty())
                ? inputDisplay + " (same as input)"
                : info.outputFile();
        String formatWidth   = info.wrapColumn() > 0 ? info.wrapColumn() + " cols" : "Off";
        String printPage     = info.printPageLines() > 0
                ? info.printPageLines() + " lines/page"
                : "No pagination";

        String[] infoLines = {
            "NeoNorton Editor - File Status",
            "",
            "  Input file    : " + inputDisplay,
            "  Output file   : " + outputDisplay,
            "  Total lines   : " + info.totalLines(),
            "  Format width  : " + formatWidth,
            "  Tab width     : " + info.tabWidth(),
            "  Print margin  : " + info.printMarginLeft(),
            "  Print page    : " + printPage,
            "  Buffer size   : " + String.format("%,d chars", info.bufferCharCount()),
            "  Unread input  : " + String.format("%,d chars", info.unreadInputChars()),
            "  Free disk     : " + formatBytes(info.freeDiskSpaceBytes()),
            "",
            "  Cursor        : Line " + (info.cursorRow() + 1) + "  Col " + (info.cursorCol() + 1),
            "  Edit mode     : " + (info.insertMode() ? "Insert" : "Overwrite"),
            "  Word wrap     : " + (info.wordWrap() ? "On" : "Off"),
            "  Indent        : " + (info.indent() ? "On" : "Off"),
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
        top.append('╔');
        for (int i = 0; i < innerWidth; i++) top.append('═');
        top.append('╗');
        tg.putString(boxStartCol, boxStartRow, top.toString());

        // Content rows
        for (int i = 0; i < infoLines.length; i++) {
            String text = infoLines[i];
            int rightPad = innerWidth - 2 - text.length();
            StringBuilder row = new StringBuilder();
            row.append('║').append(" ").append(text);
            for (int s = 0; s < rightPad; s++) row.append(' ');
            row.append(' ').append('║');
            if (i == 0) tg.enableModifiers(SGR.BOLD);
            tg.putString(boxStartCol, textStartRow + i, row.toString());
            if (i == 0) tg.disableModifiers(SGR.BOLD);
        }

        // Bottom border ╚══╝
        StringBuilder bot = new StringBuilder();
        bot.append('╚');
        for (int i = 0; i < innerWidth; i++) bot.append('═');
        bot.append('╝');
        tg.putString(boxStartCol, textStartRow + infoLines.length, bot.toString());

        screen.setCursorPosition(null);
        screen.refresh();
    }
}
