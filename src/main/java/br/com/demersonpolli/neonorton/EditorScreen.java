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

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/*
 * NE 1.3C compliance status (see "Norton Editor 1.3C: white-box reconstruction
 * specification"). Compliant: cursor family, Backspace/Del/Ctrl-W/Alt-W/Ctrl-L/
 * Alt-L/Alt-K, F3 E/S/Q/N/X, all F4 block ops, F6 G/M/T, insert-vs-replace EOL
 * behavior, F2 status screen (see StatusScreen.StatusInfo), CLI parsing in Main
 * (+LINE, input/output paths, /DA /DB /DC — parsed, though display mode/safe
 * mode/encoding aren't applied to behavior yet, see Main's TODOs).
 * Not yet compliant, see TODOs at each site below:
 *   - Search & replace (Alt-F/Ctrl-F/Alt-C/Ctrl-C, ESC case-insensitive,
 *     Y / N / star (replace all) / Space replace flow) is entirely unimplemented
 *     — no keys wired.
 *   - F5 Format, F7 Printer: overlay bars exist but every command is a stub
 *     (their target fields — wrapColumn/tabWidth/indent/printMarginLeft/
 *     printPageLines/outputPath — already exist and feed the F2 status screen;
 *     only the interactive prompts to change them are missing).
 *   - F3 W (write-through-cursor), A (append), L (load more), C (close output).
 *   - F9 DOS/shell command processor is not implemented.
 *   - Ctrl-P (insert control/extended byte) is not implemented.
 *   - Tab key inserts nothing in single-pane mode (only repurposed for pane
 *     switch in split mode) — none of the spec's 3 tab modes exist.
 *   - Undo is single-level and only snapshotted around delete-class ops, not
 *     an unbounded stack covering every insert/delete/replace transaction.
 *   - File I/O is line-based UTF-8 text (Files.readAllLines/write): no CRLF
 *     preservation, no binary/byte-safe mode, no atomic save, no incremental
 *     load for large files.
 *   - wordWrap field is read by the status bar/F2 screen but nothing ever sets
 *     it true or wraps text on insertion (F5 W is a stub).
 */
public class EditorScreen implements AppScreen {

    private final String fileName;
    private String activeFileName; // mutable; always the active pane's filename

    // Lines of text in the document
    private List<StringBuilder> lines = new ArrayList<>();

    // Cursor position in the document
    private int cursorRow = 0;
    private int cursorCol = 0;

    // Top visible row (for scrolling)
    private int scrollRow = 0;

    // Editor modes
    private boolean insertMode = true;   // true = Insert, false = Replace
    private boolean wordWrap   = false;  // true = WW=On, false = WW=Off
    private boolean indent     = false;  // F5 I: auto-indent (not yet wired to Enter)

    // Format/print/tab configuration — these are the F5/F7 command targets. The fields exist
    // now (for the F2 status screen) with sensible defaults; F5/F7 still need to let the user
    // change them interactively, see the TODOs on their handler methods below.
    private int wrapColumn      = 0;   // F5 L: format/word-wrap line length; 0 = off/unset
    private int tabWidth        = 8;   // F5 T: tab display width
    private int printMarginLeft = 0;   // F7 M: left margin for printing
    private int printPageLines  = 0;   // F7 S: lines per printed page; 0 = no pagination

    // F3 W "write through cursor" target; null means "same as activeFileName" (the CLI's
    // default when only an input path is given). Set via setOutputPath() from Main.
    private String outputPath = null;

    // Terminal width cached for status bar centering
    private int statusBarCols = 80;

    // Status bar cached for file-operation overlay
    private StatusBar statusBar;
    private StatusBar fileOpBar;
    private StatusBar blockOpBar;
    private StatusBar formatOpBar;
    private StatusBar miscOpBar;
    private StatusBar printOpBar;
    // Signal to break out of main loop after file operations
    private boolean shouldQuit = false;

    // Single-level undo snapshot for delete commands
    private List<String> undoLines = null;
    private int undoRow = 0, undoCol = 0;

    // ---- Split-pane state ----
    private boolean splitMode = false;
    private int activePane = 0; // 0 = top (rows 0-11), 1 = bottom (rows 13+)

    // Pane 2 backing data (swapped into live fields when pane 2 is active)
    private List<StringBuilder> lines2 = new ArrayList<>();
    private int cursorRow2 = 0, cursorCol2 = 0, scrollRow2 = 0;
    private String fileName2 = "";
    private boolean insertMode2 = true;

    // ---- Block markers (pane 1 live) ----
    private int markerBeginRow = -1, markerBeginCol = 0;
    private int markerEndRow   = -1, markerEndCol   = 0;
    // ---- Block markers (pane 2 backing) ----
    private int markerBeginRow2 = -1, markerBeginCol2 = 0;
    private int markerEndRow2   = -1, markerEndCol2   = 0;

    public EditorScreen(String fileName) {
        this.fileName = fileName;
        this.activeFileName = fileName;
        if (!fileName.isEmpty()) {
            Path path = Paths.get(fileName);
            if (Files.exists(path)) {
                try {
                    List<String> fileLines = Files.readAllLines(path);
                    for (String line : fileLines) {
                        lines.add(new StringBuilder(line));
                    }
                    if (lines.isEmpty()) lines.add(new StringBuilder());
                } catch (IOException e) {
                    lines.add(new StringBuilder()); // fallback to empty on read error
                }
            } else {
                lines.add(new StringBuilder()); // new file
            }
        } else {
            lines.add(new StringBuilder()); // no name, empty buffer
        }
    }

    /** Position the initial cursor at a 1-based line number (CLI's +LINE). Call before show(). */
    public void setStartLine(int line) {
        if (line <= 0 || lines.isEmpty()) return;
        cursorRow = Math.max(0, Math.min(line - 1, lines.size() - 1));
        cursorCol = 0;
        scrollRow = cursorRow;
    }

    /** Set the CLI's --output path (F3 W's target). Null/empty means "same as input file". */
    public void setOutputPath(String path) {
        this.outputPath = (path == null || path.isEmpty()) ? null : path;
    }

    @Override
    public void show(MultiWindowTextGUI gui) {
        Screen screen = gui.getScreen();
        try {
            int rows = screen.getTerminalSize().getRows();
            statusBarCols = screen.getTerminalSize().getColumns();

            String displayName = (activeFileName.isEmpty() ? "[No Name]" : activeFileName).toUpperCase();
            int centerCol = Math.max(12, (statusBarCols - displayName.length()) / 2);

            // Normal status bar
            statusBar = new StatusBar(
                rows - 1,
                new int[]   {  0,        11,     centerCol,   58,       67       },
                new String[]{ "Line=1", "Col=1", displayName, "Insert", "WW=Off" }
            );

            // File-operation overlay bar (shown while F3 mode is active)
            fileOpBar = new StatusBar(
                rows - 1,
                new int[]    { 0,          9,                26,     33,     40,                 59,    65,       73,  76,  79  },
                new String[] { "F3 FILE:", "Exit-with-save", "Quit", "Save", "eXchange-windows", "New", "Append", "L", "W", "C" }
            );

            // Block-operation overlay bar (shown while F4 mode is active)
            blockOpBar = new StatusBar(
                rows - 1,
                new int[]    { 0,           11,           24,     31,     38,             53,              69,  72,  75,  79  },
                new String[] { "F4 BLOCK:", "Set-marker", "Copy", "Move", "Delete-block", "Remove-marker", "W", "L", "E", "F" }
            );

            // Format-operation overlay bar (shown while F5 mode is active)
            formatOpBar = new StatusBar(
                rows - 1,
                new int[]    { 0,            12,                 31,            45,          57,  60,  63,  66,  69,  72  },
                new String[] { "F5 FORMAT:", "Format-paragraph", "Line-length", "Word-wrap", "T", "C", "D", "I", "S", "K" }
            );

            // Miscellaneous overlay bar (shown while F6 mode is active)
            miscOpBar = new StatusBar(
                rows - 1,
                new int[]    { 0,          10,                  30,              46,             61,               79  },
                new String[] { "F6 MISC:", "Go-to-line-number", "Match-bracket", "Text-compare", "INS-overstrike", "C" }
            );

            // Print overlay bar (shown while F7 mode is active)
            printOpBar = new StatusBar(
                rows - 1,
                new int[]    {  0,            13,          25,            39,           52,                   73       },
                new String[] { "F7 PRINTER:", "Print-all", "Block-print", "Eject-page", "Set-lines-per-page", "Margin" }
            );
            redraw(screen);

            while (true) {
                KeyStroke key = screen.readInput();
                KeyType type = key.getKeyType();

                if (type == KeyType.EOF) break;

                // F3 — enter file operation mode
                if (type == KeyType.F3) {
                    boolean wasPane1 = splitMode && activePane == 1;
                    if (wasPane1) swapActivePaneData();
                    handleFileOperation(screen, gui);
                    if (wasPane1 && splitMode) swapActivePaneData(); // restore only if still split
                    if (shouldQuit) break;
                    redraw(screen);
                    continue;
                }

                // F4 — enter block operation mode
                if (type == KeyType.F4) {
                    boolean wasPane1 = splitMode && activePane == 1;
                    if (wasPane1) swapActivePaneData();
                    handleBlockOperation(screen);
                    if (wasPane1 && splitMode) swapActivePaneData();
                    redraw(screen);
                    continue;
                }

                // F5 — enter format operation mode
                if (type == KeyType.F5) {
                    boolean wasPane1 = splitMode && activePane == 1;
                    if (wasPane1) swapActivePaneData();
                    handleFormatOperation(screen);
                    if (wasPane1 && splitMode) swapActivePaneData();
                    redraw(screen);
                    continue;
                }

                // F6 — enter miscellaneous operation mode
                if (type == KeyType.F6) {
                    boolean wasPane1 = splitMode && activePane == 1;
                    if (wasPane1) swapActivePaneData();
                    handleMiscOperation(screen);
                    if (wasPane1 && splitMode) swapActivePaneData();
                    redraw(screen);
                    continue;
                }

                // F7 — enter print operation mode
                if (type == KeyType.F7) {
                    boolean wasPane1 = splitMode && activePane == 1;
                    if (wasPane1) swapActivePaneData();
                    handlePrintOperation(screen);
                    if (wasPane1 && splitMode) swapActivePaneData();
                    redraw(screen);
                    continue;
                }

                // F1 — help
                if (type == KeyType.F1) {
                    new HelpScreen().show(gui);
                    redraw(screen);
                    continue;
                }

                // F2 — status screen
                if (type == KeyType.F2) {
                    boolean pane2 = splitMode && activePane == 1;
                    String fn  = pane2 ? fileName2    : activeFileName;
                    int    lc  = pane2 ? lines2.size(): lines.size();
                    int    cr  = pane2 ? cursorRow2   : cursorRow;
                    int    cc  = pane2 ? cursorCol2   : cursorCol;
                    boolean im = pane2 ? insertMode2  : insertMode;
                    List<StringBuilder> paneLines = pane2 ? lines2 : lines;
                    StatusScreen.StatusInfo info = new StatusScreen.StatusInfo(
                        fn, outputPath, lc, cr, cc, im, wordWrap, indent,
                        wrapColumn, tabWidth, printMarginLeft, printPageLines,
                        countBufferChars(paneLines),
                        0L, // unread input chars: always 0 until F3 L "load more" exists
                        freeDiskSpaceBytes(fn)
                    );
                    new StatusScreen(info).show(gui);
                    redraw(screen);
                    continue;
                }

                // Tab — switch active pane in split mode
                if (type == KeyType.Tab && splitMode) {
                    activePane = 1 - activePane;
                    redraw(screen);
                    continue;
                }

                // Route key events to the active pane via the swap idiom
                if (splitMode && activePane == 1) swapActivePaneData();
                handleKey(key, screen);
                if (splitMode && activePane == 1) swapActivePaneData();
                redraw(screen);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleKey(KeyStroke key, Screen screen) {
        KeyType type = key.getKeyType();
        TerminalSize size = screen.getTerminalSize();
        int textRows;
        if (splitMode) {
            textRows = (activePane == 0) ? 12 : size.getRows() - 13;
        } else {
            textRows = size.getRows() - 1; // last row is status bar
        }
        int cols = size.getColumns();

        switch (type) {
            case Character -> {
                char ch = key.getCharacter();
                if (key.isCtrlDown()) {
                    switch (Character.toLowerCase(ch)) {
                        case 'w' -> deleteWordLeft();
                        case 'l' -> deleteToLineBegin();
                        case 'u' -> undoLastDelete();
                        case 'v' -> toggleCaseToLineBegin();
                        // TODO: Ctrl-F, reverse find (continue). Needs a new search.c-equivalent:
                        // a `lastSearchTerm`/`lastSearchCaseSensitive` pair of fields set by a
                        // search-entry prompt (goToLineNumber()-style single-line input, but
                        // ESC during entry sets case-insensitive instead of canceling, and
                        // Ctrl-Return inserts a literal newline into the term). Ctrl-F with no
                        // prior term should prompt for one (reverse direction) and jump the
                        // cursor to the first match at/before the cursor; show "SEARCH STRING
                        // NOT FOUND" on failure.
                        case 'f' -> { /* TODO: reverse find */ }
                        // TODO: Ctrl-C, continue reverse search. Requires 'f' above to exist
                        // first (shares `lastSearchTerm`); repeats the same reverse match
                        // starting one position before the current cursor, non-overlapping.
                        case 'c' -> { /* TODO: continue reverse search */ }
                        // TODO: Ctrl-P, insert literal/extended byte. Prompt for a raw
                        // byte/codepoint (e.g. two hex digits) and insert it directly into the
                        // current line via `lines.get(cursorRow).insert(cursorCol, ch)` the same
                        // way handleKey's plain Character case does, bypassing normal
                        // printable-character filtering so control bytes can be entered.
                        case 'p' -> { /* TODO: insert literal byte */ }
                        default  -> {}
                    }
                } else if (key.isAltDown()) {
                    switch (Character.toLowerCase(ch)) {
                        case 'w' -> deleteWordRight();
                        case 'l' -> deleteToLineEnd();
                        case 'k' -> killLine();
                        case 'v' -> toggleCaseToLineEnd();
                        // TODO: Alt-F, forward find. Same search-entry prompt as Ctrl-F (see its
                        // TODO above) but scanning forward from just after the cursor; store the
                        // term/case-sensitivity in the same `lastSearchTerm` fields so Ctrl-F,
                        // Alt-C and Ctrl-C can all reuse it. After a match, entering find again
                        // in the same direction with the SAME term should instead prompt for a
                        // replacement string and start the Y/N/*/Space replace loop described in
                        // the class-level compliance comment.
                        case 'f' -> { /* TODO: forward find */ }
                        // TODO: Alt-C, continue forward search. Repeats the last forward match
                        // starting one position after the current cursor, non-overlapping with
                        // the previous match; show "SEARCH STRING NOT FOUND" when exhausted.
                        case 'c' -> { /* TODO: continue forward search */ }
                        default  -> {}
                    }
                } else {
                    StringBuilder line = lines.get(cursorRow);
                    if (insertMode || cursorCol >= line.length()) {
                        line.insert(cursorCol, ch);
                    } else {
                        line.setCharAt(cursorCol, ch);
                    }
                    cursorCol++;
                }
            }
            case Insert -> insertMode = true;
            case Enter -> {
                StringBuilder current = lines.get(cursorRow);
                StringBuilder newLine = new StringBuilder(current.substring(cursorCol));
                current.delete(cursorCol, current.length());
                lines.add(cursorRow + 1, newLine);
                cursorRow++;
                cursorCol = 0;
            }
            case Backspace -> {
                saveUndo();
                if (cursorCol > 0) {
                    lines.get(cursorRow).deleteCharAt(cursorCol - 1);
                    cursorCol--;
                } else if (cursorRow > 0) {
                    StringBuilder above = lines.get(cursorRow - 1);
                    cursorCol = above.length();
                    above.append(lines.remove(cursorRow));
                    cursorRow--;
                }
            }
            case Delete -> {
                saveUndo();
                StringBuilder line = lines.get(cursorRow);
                if (cursorCol < line.length()) {
                    line.deleteCharAt(cursorCol);
                } else if (cursorRow < lines.size() - 1) {
                    line.append(lines.remove(cursorRow + 1));
                }
            }
            case ArrowLeft -> {
                if (key.isCtrlDown()) {
                    // Ctrl+Left: move to start of previous word
                    if (cursorCol > 0) {
                        int c = cursorCol - 1;
                        String s = lines.get(cursorRow).toString();
                        // skip non-word chars, then skip word chars
                        while (c > 0 && !Character.isLetterOrDigit(s.charAt(c))) c--;
                        while (c > 0 && Character.isLetterOrDigit(s.charAt(c - 1))) c--;
                        cursorCol = c;
                    } else if (cursorRow > 0) {
                        cursorRow--;
                        cursorCol = lines.get(cursorRow).length();
                    }
                } else {
                    if (cursorCol > 0) {
                        cursorCol--;
                    } else if (cursorRow > 0) {
                        cursorRow--;
                        cursorCol = lines.get(cursorRow).length();
                    }
                }
            }
            case ArrowRight -> {
                if (key.isCtrlDown()) {
                    // Ctrl+Right: move to start of next word
                    String s = lines.get(cursorRow).toString();
                    int len = s.length();
                    if (cursorCol < len) {
                        int c = cursorCol;
                        // skip current word chars, then skip non-word chars
                        while (c < len && Character.isLetterOrDigit(s.charAt(c))) c++;
                        while (c < len && !Character.isLetterOrDigit(s.charAt(c))) c++;
                        cursorCol = c;
                    } else if (cursorRow < lines.size() - 1) {
                        cursorRow++;
                        cursorCol = 0;
                    }
                } else {
                    int len = lines.get(cursorRow).length();
                    if (cursorCol < len) {
                        cursorCol++;
                    } else if (cursorRow < lines.size() - 1) {
                        cursorRow++;
                        cursorCol = 0;
                    }
                }
            }
            case ArrowUp -> {
                if (cursorRow > 0) {
                    cursorRow--;
                    cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
                }
            }
            case ArrowDown -> {
                if (cursorRow < lines.size() - 1) {
                    cursorRow++;
                    cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
                }
            }
            case Home -> {
                if (key.isCtrlDown()) {
                    // Ctrl+Home: beginning of file
                    cursorRow = 0;
                    cursorCol = 0;
                } else {
                    cursorCol = 0;
                }
            }
            case End -> {
                if (key.isCtrlDown()) {
                    // Ctrl+End: end of file
                    cursorRow = lines.size() - 1;
                    cursorCol = lines.get(cursorRow).length();
                } else {
                    cursorCol = lines.get(cursorRow).length();
                }
            }
            case PageUp -> {
                cursorRow = Math.max(0, cursorRow - textRows);
                cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
            }
            case PageDown -> {
                cursorRow = Math.min(lines.size() - 1, cursorRow + textRows);
                cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
            }
            // TODO(spec: Editing semantics — tab modes): KeyType.Tab has no case here, so
            // pressing Tab in single-pane mode does nothing (in split mode it's consumed
            // earlier in show() to switch panes and never reaches handleKey at all). The
            // spec requires 3 configurable tab modes selectable via F5 T: (1) insert a
            // literal TAB byte, (2) insert spaces to the next tab stop, (3) move the cursor
            // to the next tab stop without writing text. None exist; add a case here plus
            // a `tabMode`/`tabWidth` field wired up by handleFormatOperation's 'T' command.
            default -> {}
        }

        // Clamp horizontal cursor
        cursorCol = Math.max(0, Math.min(cursorCol, lines.get(cursorRow).length()));

        // Scroll to keep cursor visible
        if (cursorRow < scrollRow) {
            scrollRow = cursorRow;
        } else if (cursorRow >= scrollRow + textRows) {
            scrollRow = cursorRow - textRows + 1;
        }
    }

    private void redraw(Screen screen) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        int rows = size.getRows();
        int cols = size.getColumns();

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);

        if (!splitMode) {
            // ---- Single-pane mode ----
            int textRows = rows - 1;
            drawTextPane(tg, lines, scrollRow, 0, textRows, cols,
                    markerBeginRow, markerBeginCol, markerEndRow, markerEndCol);
            updateStatusBar();
            statusBar.render(screen);
            int screenRow = cursorRow - scrollRow;
            int screenCol = Math.min(cursorCol, cols - 1);
            screen.setCursorPosition(new TerminalPosition(screenCol, screenRow));
        } else {
            // ---- Split-pane mode ----
            drawTextPane(tg, lines, scrollRow, 0, 12, cols,
                    markerBeginRow, markerBeginCol, markerEndRow, markerEndCol);
            // Status bar at row 12
            updateStatusBar();
            statusBar.render(screen);
            // Pane 2 always in lines2 / cursorRow2 / scrollRow2
            int pane2Rows = rows - 13;
            drawTextPane(tg, lines2, scrollRow2, 13, pane2Rows, cols,
                    markerBeginRow2, markerBeginCol2, markerEndRow2, markerEndCol2);
            // Cursor in the active pane
            if (activePane == 0) {
                int screenRow = Math.min(cursorRow - scrollRow, 11);
                int screenCol = Math.min(cursorCol, cols - 1);
                screen.setCursorPosition(new TerminalPosition(screenCol, screenRow));
            } else {
                int screenRow = 13 + Math.min(cursorRow2 - scrollRow2, pane2Rows - 1);
                int screenCol = Math.min(cursorCol2, cols - 1);
                screen.setCursorPosition(new TerminalPosition(screenCol, screenRow));
            }
        }
        screen.refresh();
    }

    /**
     * Draw one pane's text with optional block-marker highlighting. No square characters.
     * - One marker set: the character under the marker is shown with yellow background.
     * - Both markers set: the whole region is shown with cyan background; the first and
     *   last characters of the selection keep a yellow background to mark the boundaries.
     */
    private void drawTextPane(TextGraphics tg,
                               List<StringBuilder> paneLines, int paneScroll,
                               int screenRowStart, int screenRowCount, int cols,
                               int bRow, int bCol, int eRow, int eCol) {
        boolean hasFull  = bRow >= 0 && eRow >= 0;
        boolean hasBegin = bRow >= 0;

        for (int r = 0; r < screenRowCount; r++) {
            int docRow = r + paneScroll;
            String text = (docRow < paneLines.size()) ? paneLines.get(docRow).toString() : "";
            String padded = String.format("%-" + cols + "s",
                    text.length() > cols ? text.substring(0, cols) : text);
            int screenRow = screenRowStart + r;

            if (!hasFull) {
                // Plain drawing with optional single-marker highlight
                tg.setForegroundColor(TextColor.ANSI.WHITE);
                tg.setBackgroundColor(TextColor.ANSI.BLACK);
                tg.putString(0, screenRow, padded);
                if (hasBegin && docRow == bRow && bCol < cols) {
                    tg.setForegroundColor(TextColor.ANSI.BLACK);
                    tg.setBackgroundColor(TextColor.ANSI.YELLOW);
                    tg.putString(bCol, screenRow, String.valueOf(padded.charAt(bCol)));
                }
            } else {
                // Char-by-char: block region = cyan, boundary chars = yellow
                for (int c = 0; c < cols; c++) {
                    boolean inBlock;
                    if (docRow < bRow || docRow > eRow) {
                        inBlock = false;
                    } else if (docRow == bRow && docRow == eRow) {
                        inBlock = c >= bCol && c < eCol;
                    } else if (docRow == bRow) {
                        inBlock = c >= bCol;
                    } else if (docRow == eRow) {
                        inBlock = c < eCol;
                    } else {
                        inBlock = true;
                    }

                    // Boundary: first char of selection (bRow/bCol) or last char (eRow/eCol-1)
                    boolean isBeginChar = docRow == bRow && c == bCol;
                    boolean isEndChar   = docRow == eRow && c == eCol - 1 && eCol > bCol || // same-row guard
                                         docRow == eRow && c == eCol - 1 && !(docRow == bRow && eCol <= bCol);

                    TextColor bg = inBlock
                            ? (isBeginChar || isEndChar ? TextColor.ANSI.YELLOW : TextColor.ANSI.CYAN)
                            : TextColor.ANSI.BLACK;
                    TextColor fg = inBlock ? TextColor.ANSI.BLACK : TextColor.ANSI.WHITE;
                    tg.setForegroundColor(fg);
                    tg.setBackgroundColor(bg);
                    tg.putString(c, screenRow, String.valueOf(padded.charAt(c)));
                }
            }
        }
    }

    private void updateStatusBar() {
        // In split mode always read from the active-pane's own fields (swap is already reversed)
        int sRow = (splitMode && activePane == 1) ? cursorRow2 : cursorRow;
        int sCol = (splitMode && activePane == 1) ? cursorCol2 : cursorCol;
        boolean im = (splitMode && activePane == 1) ? insertMode2 : insertMode;
        String name = (splitMode && activePane == 1)
            ? (fileName2.isEmpty() ? "[No Name]" : fileName2).toUpperCase()
            : (activeFileName.isEmpty() ? "[No Name]" : activeFileName).toUpperCase();
        // Re-centre filename label column for the current name length
        int centerCol = Math.max(12, (statusBarCols - name.length()) / 2);
        statusBar.setColumn(2, centerCol);
        statusBar.setLabel(0, String.format("Line=%-5d", sRow + 1));
        statusBar.setLabel(1, String.format("Col=%-5d",  sCol + 1));
        statusBar.setLabel(2, name);
        statusBar.setLabel(3, im ? "Insert " : "Replace");
        statusBar.setLabel(4, wordWrap ? "WW=On " : "WW=Off");
    }

    /** Total character count in a buffer: line lengths plus one newline per line break. */
    private long countBufferChars(List<StringBuilder> paneLines) {
        long total = 0;
        for (StringBuilder line : paneLines) total += line.length();
        total += Math.max(0, paneLines.size() - 1);
        return total;
    }

    /** Free space on the filesystem holding `path`; walks up to an existing ancestor if the
     *  file itself doesn't exist yet, and falls back to the current directory. Returns -1 if
     *  it can't be determined. */
    private long freeDiskSpaceBytes(String path) {
        try {
            Path p = (path == null || path.isEmpty()) ? Paths.get(".") : Paths.get(path).toAbsolutePath();
            while (p != null && !Files.exists(p)) p = p.getParent();
            if (p == null) p = Paths.get(".");
            return Files.getFileStore(p).getUsableSpace();
        } catch (IOException e) {
            return -1L;
        }
    }

    private void handlePrintOperation(Screen screen) throws IOException {
        printOpBar.setRow(splitMode ? 12 : screen.getTerminalSize().getRows() - 1);
        printOpBar.render(screen);
        screen.refresh();

        KeyStroke key = screen.readInput();
        if (key.getKeyType() != KeyType.Character) return;

        // Printer output must go through a PrinterSink abstraction (named file / OS print
        // command / stdout) — never a parallel port — so P/B below share one sink instance
        // configured by the S/M commands.
        switch (Character.toLowerCase(key.getCharacter())) {
            // TODO: Print-all. `printMarginLeft`/`printPageLines` fields already exist (set by
            // 'm'/'s' below, and shown on F2's status screen) — open/obtain the PrinterSink,
            // then stream every line in `lines` through it: pad each line with
            // `printMarginLeft` spaces, expand tabs if the F5 tab-expansion-on-print option is
            // set, and insert a form feed every `printPageLines` output lines (skip pagination
            // entirely when it's 0). Let Ctrl-C during the loop abort and close the sink early.
            case 'p' -> { /* TODO: Print-all */ }
            // TODO: Block-print. Call hasFullMarkers() first and show "TWO BLOCK MARKERS
            // NEEDED" (see StatusBar/overlay pattern used elsewhere) if unset; otherwise reuse
            // extractBlockContent() to get the marked lines and feed exactly that list through
            // the same PrinterSink pipeline as Print-all (margin/page-length/tab rules apply
            // identically).
            case 'b' -> { /* TODO: Block-print */ }
            // TODO: Eject-page. Send a raw form-feed byte (0x0C) to the currently open
            // PrinterSink; if no sink/print job is open, this is a no-op (nothing to eject).
            case 'e' -> { /* TODO: Eject-page */ }
            // TODO: Set-lines-per-page. Reuse the goToLineNumber() digit-prompt pattern to read
            // an integer into the existing `printPageLines` field; 0 means "no pagination" per
            // spec (already the default). Validate non-negative; ignore/cancel on empty or
            // non-numeric input.
            case 's' -> { /* TODO: Set-lines-per-page */ }
            // TODO: Margin. Same digit-prompt pattern as 's' above, writing the existing
            // `printMarginLeft` field; this many spaces get prepended to every printed line.
            case 'm' -> { /* TODO: Margin */ }
            default  -> { /* cancel */ }
        }
    }

    /** Compare active pane vs other pane starting from their respective cursors.
     *  On first difference: jump the OTHER pane's cursor there and switch active pane.
     *  No-op if not in split mode or no difference found.
     */
    private void textCompare() {
        if (!splitMode) return;
        // Inside handleMiscOperation: lines/cursorRow/cursorCol = active pane,
        //                             lines2/cursorRow2/cursorCol2 = other pane.
        int rowA = cursorRow,  colA = cursorCol;
        int rowB = cursorRow2, colB = cursorCol2;

        while (true) {
            // Read next char from each pane: -1 = past EOF, '\n' = end of line
            int chA, chB;
            if (rowA < lines.size()) {
                String ln = lines.get(rowA).toString();
                chA = (colA < ln.length()) ? ln.charAt(colA) : '\n';
            } else chA = -1;

            if (rowB < lines2.size()) {
                String ln = lines2.get(rowB).toString();
                chB = (colB < ln.length()) ? ln.charAt(colB) : '\n';
            } else chB = -1;

            if (chA == -1 && chB == -1) return; // identical from cursors onward

            if (chA != chB) {
                // Move other pane's cursor to the diff position
                if (rowB < lines2.size()) {
                    cursorRow2 = rowB;
                    cursorCol2 = Math.min(colB, lines2.get(rowB).length());
                } else {
                    cursorRow2 = Math.max(0, lines2.size() - 1);
                    cursorCol2 = 0;
                }
                // Adjust other pane's scroll so the diff line is visible at the top
                scrollRow2 = cursorRow2;
                // Switch to the other pane
                activePane = 1 - activePane;
                return;
            }

            // Advance both cursors
            if (chA == '\n') { rowA++; colA = 0; } else colA++;
            if (chB == '\n') { rowB++; colB = 0; } else colB++;
        }
    }

    private void matchBracket() {
        if (cursorRow >= lines.size()) return;
        String line = lines.get(cursorRow).toString();
        if (cursorCol >= line.length()) return;
        char ch = line.charAt(cursorCol);

        final String OPEN  = "([{<";
        final String CLOSE = ")]}>";
        int idx = OPEN.indexOf(ch);
        boolean searchForward = idx >= 0;
        if (!searchForward) {
            idx = CLOSE.indexOf(ch);
            if (idx < 0) return; // not a bracket char
        }
        char open  = OPEN.charAt(idx);
        char close = CLOSE.charAt(idx);

        int depth = 0;
        if (searchForward) {
            for (int r = cursorRow; r < lines.size(); r++) {
                String ln = lines.get(r).toString();
                int startC = (r == cursorRow) ? cursorCol : 0;
                for (int c = startC; c < ln.length(); c++) {
                    char cur = ln.charAt(c);
                    if (cur == open)  depth++;
                    else if (cur == close) {
                        depth--;
                        if (depth == 0) { cursorRow = r; cursorCol = c; return; }
                    }
                }
            }
        } else {
            for (int r = cursorRow; r >= 0; r--) {
                String ln = lines.get(r).toString();
                int startC = (r == cursorRow) ? cursorCol : ln.length() - 1;
                for (int c = startC; c >= 0; c--) {
                    char cur = ln.charAt(c);
                    if (cur == close) depth++;
                    else if (cur == open) {
                        depth--;
                        if (depth == 0) { cursorRow = r; cursorCol = c; return; }
                    }
                }
            }
        }
    }

    private void goToLineNumber(Screen screen) throws IOException {
        int cols       = screen.getTerminalSize().getColumns();
        int promptRow  = (splitMode && activePane == 1) ? 13 : 0;
        int inputRow   = promptRow + 1;
        int ruleRow    = promptRow + 2;

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, promptRow, String.format("%-" + cols + "s", "Enter line number:"));
        tg.putString(0, inputRow,  String.format("%-" + cols + "s", ""));
        tg.putString(0, ruleRow,   "\u2500".repeat(cols));
        screen.setCursorPosition(new TerminalPosition(0, inputRow));
        screen.refresh();

        StringBuilder numBuilder = new StringBuilder();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() == KeyType.Escape) return;
            if (k.getKeyType() == KeyType.Enter)  break;
            if (k.getKeyType() == KeyType.Backspace) {
                if (numBuilder.length() > 0) numBuilder.deleteCharAt(numBuilder.length() - 1);
            } else if (k.getKeyType() == KeyType.Character
                    && !k.isCtrlDown() && !k.isAltDown()
                    && Character.isDigit(k.getCharacter())) {
                numBuilder.append(k.getCharacter());
            }
            tg.putString(0, inputRow, String.format("%-" + cols + "s", numBuilder.toString()));
            screen.setCursorPosition(new TerminalPosition(numBuilder.length(), inputRow));
            screen.refresh();
        }

        String txt = numBuilder.toString().trim();
        if (txt.isEmpty()) return;
        int target;
        try { target = Integer.parseInt(txt); } catch (NumberFormatException e) { return; }
        target = Math.max(1, Math.min(target, lines.size()));
        cursorRow = target - 1;
        cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
        // Adjust scroll so the target line is visible
        int visibleRows = screen.getTerminalSize().getRows() - 1 - (splitMode ? 13 : 0);
        if (cursorRow < scrollRow) scrollRow = cursorRow;
        else if (cursorRow >= scrollRow + visibleRows) scrollRow = cursorRow - visibleRows + 1;
    }

    private void handleMiscOperation(Screen screen) throws IOException {
        miscOpBar.setRow(splitMode ? 12 : screen.getTerminalSize().getRows() - 1);
        miscOpBar.render(screen);
        screen.refresh();

        KeyStroke key = screen.readInput();
        if (key.getKeyType() == KeyType.Insert) {
            insertMode = false;  // F6-INS = overwrite mode
            return;
        }
        if (key.getKeyType() != KeyType.Character) return;

        switch (Character.toLowerCase(key.getCharacter())) {
            case 'g' -> goToLineNumber(screen);
            case 'm' -> matchBracket();
            case 't' -> textCompare();
            // NOTE: this 'i' case is dead — KeyType.Insert is already intercepted above
            // (`if (key.getKeyType() == KeyType.Insert)`) and returns before reaching this
            // switch, which correctly implements the spec's "F6+Ins forces replace/overstrike".
            // Typing the literal character 'i' after F6 falls into this no-op instead; remove
            // this case (or repurpose the letter) once compliance work touches this method.
            case 'i' -> { /* TODO: INS-overstrike */ }
            // TODO: Condensed display. Add a `condensed` boolean field; toggle it here. Wire
            // it into drawTextPane()/redraw() to render at a denser column width (e.g. treat
            // the pane as if `cols` were larger — skip characters or use half-width rendering
            // if the terminal backend ever supports it) — exact column count/behavior isn't
            // recoverable from the spec's listing, so pick a concrete number (e.g. 132 cols
            // worth of text scaled into the real terminal width) and document it as a design
            // choice. Reflect the state in the status bar the same way WW=On/Off is shown.
            case 'c' -> { /* TODO: C */ }
            default  -> { /* cancel */ }
        }
    }

    private void handleFormatOperation(Screen screen) throws IOException {
        formatOpBar.setRow(splitMode ? 12 : screen.getTerminalSize().getRows() - 1);
        formatOpBar.render(screen);
        screen.refresh();

        KeyStroke key = screen.readInput();
        if (key.getKeyType() != KeyType.Character) return;

        switch (Character.toLowerCase(key.getCharacter())) {
            // TODO: Format-paragraph. Find the paragraph the cursor is in (scan up/down from
            // cursorRow to the nearest blank line or buffer edge on each side). Join its lines
            // into one string, collapse runs of whitespace to single spaces, then greedily
            // rewrap at the existing `wrapColumn` field (settable via 'l' below; 0 = off, so
            // treat 0 as "don't reflow"), re-emitting the original leading indent on every
            // produced line. Replace the paragraph's lines in `lines` with the rewrapped ones
            // in a single saveUndo() transaction. Must not touch a marked block
            // (hasFullMarkers()) that overlaps — refuse or skip it if so.
            case 'f' -> { /* TODO: Format-paragraph */ }
            // TODO: Line-length. Reuse the goToLineNumber() digit-prompt pattern to read an
            // integer into the existing `wrapColumn` field (shared with Format-paragraph and
            // the word-wrap-on-typing behavior below; also shown on F2's status screen).
            // Validate > 0; ignore on cancel/empty input.
            case 'l' -> { /* TODO: Line-length */ }
            // TODO: Word-wrap. Flip the existing `wordWrap` field (it's declared and read by
            // updateStatusBar() already, just never toggled). Once true, handleKey's Character
            // case must check line length against `wrapColumn` after each insertion and, when
            // exceeded, push the trailing word down to a new line the way Enter does.
            case 'w' -> { /* TODO: Word-wrap */ }
            // TODO: Tab display/insert mode. Prompt (goToLineNumber-style digit read) for tab
            // width into the existing `tabWidth` field, then offer a 3-way selector (e.g. read
            // one more char: 'l' = insert literal TAB byte, 's' = insert spaces to next stop,
            // 'm' = move cursor to next stop without writing) stored in a new `tabMode` enum
            // field. handleKey's missing `case Tab ->` (see the TODO there) reads both fields.
            case 't' -> { /* TODO: T */ }
            // TODO: Cursor type/shape. Prompt for block/underline/etc. and forward the choice
            // to the terminal backend's cursor-style call (Lanterna's TextGraphics/Screen
            // doesn't expose cursor shape directly — this may require dropping to the
            // underlying Terminal object). Persist the choice in a new `cursorStyle` field.
            case 'c' -> { /* TODO: C */ }
            // TODO: Display/color theme. Selects among the CLI's /DA, /DB, /DC palettes (see
            // Main's parsed-but-unused `displayMode`) — add a `displayMode` field here too (or
            // thread Main's through a setter, like setOutputPath) so F5 D and the CLI flag are
            // the same setting, and re-render the WHITE/BLACK TextColor.ANSI literals in
            // redraw()/drawTextPane()/StatusBar via that field instead of the hardcoded colors
            // used throughout today.
            case 'd' -> { /* TODO: D */ }
            // TODO: Indentation toggle. Flip the existing `indent` field. When true, Enter (in
            // handleKey's `case Enter ->`) should copy the leading whitespace of the current
            // line onto the new line instead of starting at column 0.
            case 'i' -> { /* TODO: I */ }
            // TODO: Save editor configuration. Persist tabWidth/tabMode, insert-key behavior,
            // cursorStyle, displayMode, wrapColumn/wordWrap, indent, and print settings
            // (printMarginLeft/printPageLines — all these fields already exist on EditorScreen)
            // to a config file under the platform's standard user-config directory (spec leaves
            // the format/filename undecided — pick one, e.g. config.toml, and load it back on
            // startup in Main/EditorScreen's constructor with safe defaults on a missing/corrupt
            // file).
            case 's' -> { /* TODO: S */ }
            // TODO: TAB/INS key configuration. A small dialog choosing (a) which of the 3 tab
            // modes from 'T' above Tab uses by default, and (b) whether Ins toggles
            // insert/replace (current behavior, `case Insert -> insertMode = true` plus F6+Ins)
            // or always forces insert mode. Store as fields read by handleKey's Insert case.
            case 'k' -> { /* TODO: K */ }
            default  -> { /* cancel */ }
        }
    }

    private void handleBlockOperation(Screen screen) throws IOException {
        // Move the block bar to the correct row before rendering
        blockOpBar.setRow(splitMode ? 12 : screen.getTerminalSize().getRows() - 1);
        blockOpBar.render(screen);
        screen.refresh();

        KeyStroke key = screen.readInput();
        if (key.getKeyType() != KeyType.Character) return;

        switch (Character.toLowerCase(key.getCharacter())) {
            case 's' -> blockSetMarker();
            case 'r' -> blockRemoveMarkers();
            case 'd' -> blockDelete();
            case 'c' -> blockCopy();
            case 'm' -> blockMove();
            case 'w' -> blockCopyFromOtherPane();
            case 'l' -> blockMarkLine();
            case 'e' -> blockMarkToLineEnd();
            case 'f' -> blockFindNext();
            default  -> { /* cancel */ }
        }
    }

    // ------------------------------------------------------------------ block helpers

    private boolean hasFullMarkers() {
        return markerBeginRow >= 0 && markerEndRow >= 0;
    }

    private void normalizeMarkers() {
        if (!hasFullMarkers()) return;
        boolean beginAfterEnd = markerBeginRow > markerEndRow
                || (markerBeginRow == markerEndRow && markerBeginCol > markerEndCol);
        if (beginAfterEnd) {
            int tr = markerBeginRow; markerBeginRow = markerEndRow; markerEndRow = tr;
            int tc = markerBeginCol; markerBeginCol = markerEndCol; markerEndCol = tc;
        }
    }

    /** F4-S: first press = begin marker, second press = end marker, third = restart. */
    private void blockSetMarker() {
        if (markerBeginRow < 0) {
            markerBeginRow = cursorRow; markerBeginCol = cursorCol;
        } else if (markerEndRow < 0) {
            markerEndRow = cursorRow; markerEndCol = cursorCol;
            normalizeMarkers();
        } else {
            markerBeginRow = cursorRow; markerBeginCol = cursorCol;
            markerEndRow = -1;
        }
    }

    /** F4-R: clear both markers. */
    private void blockRemoveMarkers() {
        markerBeginRow = -1; markerEndRow = -1;
    }

    /** F4-L: mark entire current line (begin of line → begin of next line). */
    private void blockMarkLine() {
        markerBeginRow = cursorRow; markerBeginCol = 0;
        if (cursorRow + 1 < lines.size()) {
            markerEndRow = cursorRow + 1; markerEndCol = 0;
        } else {
            markerEndRow = cursorRow; markerEndCol = lines.get(cursorRow).length();
        }
    }

    /** F4-E: mark from cursor to end of line (no CR). */
    private void blockMarkToLineEnd() {
        markerBeginRow = cursorRow; markerBeginCol = cursorCol;
        markerEndRow   = cursorRow; markerEndCol   = lines.get(cursorRow).length();
    }

    /** Extract the text between the markers as a list of strings (one per line fragment). */
    private List<String> extractBlockContent() {
        if (!hasFullMarkers()) return null;
        List<String> result = new ArrayList<>();
        if (markerBeginRow == markerEndRow) {
            String ln = lines.get(markerBeginRow).toString();
            int from = Math.min(markerBeginCol, ln.length());
            int to   = Math.min(markerEndCol,   ln.length());
            result.add(ln.substring(from, Math.max(from, to)));
        } else {
            String first = lines.get(markerBeginRow).toString();
            result.add(first.substring(Math.min(markerBeginCol, first.length())));
            for (int r = markerBeginRow + 1; r < markerEndRow; r++) {
                result.add(lines.get(r).toString());
            }
            if (markerEndRow < lines.size()) {
                String last = lines.get(markerEndRow).toString();
                result.add(last.substring(0, Math.min(markerEndCol, last.length())));
            }
        }
        return result;
    }

    /** Delete the block content in-place; cursor moves to begin marker; markers cleared. */
    private void deleteBlockContent() {
        if (!hasFullMarkers()) return;
        if (markerBeginRow == markerEndRow) {
            StringBuilder ln = lines.get(markerBeginRow);
            int from = Math.min(markerBeginCol, ln.length());
            int to   = Math.min(markerEndCol,   ln.length());
            ln.delete(from, to);
        } else {
            String prefix = lines.get(markerBeginRow).toString()
                    .substring(0, Math.min(markerBeginCol, lines.get(markerBeginRow).length()));
            String suffix = (markerEndRow < lines.size())
                    ? lines.get(markerEndRow).toString()
                      .substring(Math.min(markerEndCol, lines.get(markerEndRow).length()))
                    : "";
            lines.get(markerBeginRow).setLength(0);
            lines.get(markerBeginRow).append(prefix).append(suffix);
            for (int r = markerEndRow; r > markerBeginRow; r--) lines.remove(r);
        }
        cursorRow = markerBeginRow; cursorCol = markerBeginCol;
        markerBeginRow = -1; markerEndRow = -1;
        clampCursor();
    }

    /** Insert block lines at (row, col); cursor lands at end of inserted text. */
    private void insertBlockAt(int row, int col, List<String> block) {
        if (block == null || block.isEmpty()) return;
        StringBuilder tl = lines.get(row);
        int safeCol = Math.min(col, tl.length());
        String after = tl.substring(safeCol);
        tl.setLength(safeCol);
        if (block.size() == 1) {
            tl.append(block.get(0)).append(after);
            cursorRow = row; cursorCol = safeCol + block.get(0).length();
        } else {
            tl.append(block.get(0));
            for (int i = 1; i < block.size() - 1; i++) {
                lines.add(row + i, new StringBuilder(block.get(i)));
            }
            int lastIdx = row + block.size() - 1;
            lines.add(lastIdx, new StringBuilder(block.get(block.size() - 1) + after));
            cursorRow = lastIdx; cursorCol = block.get(block.size() - 1).length();
        }
    }

    private void clampCursor() {
        cursorRow = Math.max(0, Math.min(cursorRow, lines.size() - 1));
        cursorCol = Math.max(0, Math.min(cursorCol, lines.get(cursorRow).length()));
    }

    /** F4-D: delete the marked block. */
    private void blockDelete() {
        if (!hasFullMarkers()) return;
        saveUndo();
        deleteBlockContent();
    }

    /** F4-C: copy block to cursor position. */
    private void blockCopy() {
        List<String> block = extractBlockContent();
        if (block == null) return;
        saveUndo();
        insertBlockAt(cursorRow, cursorCol, block);
    }

    /** F4-M: move block to cursor position. */
    private void blockMove() {
        List<String> block = extractBlockContent();
        if (block == null) return;
        saveUndo();
        int destRow = cursorRow, destCol = cursorCol;
        // If destination is after the block, re-anchor after deletion
        boolean destAfterBlock = destRow > markerEndRow
                || (destRow == markerEndRow && destCol >= markerEndCol);
        deleteBlockContent(); // clears markers, moves cursor to begin
        if (destAfterBlock) {
            destRow = cursorRow; destCol = cursorCol;
        }
        insertBlockAt(destRow, destCol, block);
    }

    /** F4-W: copy block marked in the OTHER pane into the current cursor position. */
    private void blockCopyFromOtherPane() {
        if (!splitMode || markerBeginRow2 < 0 || markerEndRow2 < 0) return;
        saveUndo();
        List<String> block = new ArrayList<>();
        if (markerBeginRow2 == markerEndRow2) {
            String ln = lines2.get(markerBeginRow2).toString();
            int from = Math.min(markerBeginCol2, ln.length());
            int to   = Math.min(markerEndCol2,   ln.length());
            block.add(ln.substring(from, Math.max(from, to)));
        } else {
            String first = lines2.get(markerBeginRow2).toString();
            block.add(first.substring(Math.min(markerBeginCol2, first.length())));
            for (int r = markerBeginRow2 + 1; r < markerEndRow2; r++) {
                block.add(lines2.get(r).toString());
            }
            if (markerEndRow2 < lines2.size()) {
                String last = lines2.get(markerEndRow2).toString();
                block.add(last.substring(0, Math.min(markerEndCol2, last.length())));
            }
        }
        insertBlockAt(cursorRow, cursorCol, block);
    }

    /** F4-F: cycle cursor between begin marker and end marker. */
    private void blockFindNext() {
        if (markerBeginRow < 0) return;
        boolean beforeBegin = cursorRow < markerBeginRow
                || (cursorRow == markerBeginRow && cursorCol <= markerBeginCol);
        if (beforeBegin) {
            cursorRow = markerBeginRow; cursorCol = markerBeginCol;
        } else if (markerEndRow >= 0 && (cursorRow < markerEndRow
                || (cursorRow == markerEndRow && cursorCol <= markerEndCol))) {
            cursorRow = markerEndRow; cursorCol = markerEndCol;
        } else {
            cursorRow = markerBeginRow; cursorCol = markerBeginCol; // wrap
        }
    }

    private void handleFileOperation(Screen screen, MultiWindowTextGUI gui) throws IOException {
        // Show the file-operation status bar
        fileOpBar.render(screen);
        screen.refresh();

        KeyStroke key = screen.readInput();
        if (key.getKeyType() != KeyType.Character) return;

        switch (Character.toLowerCase(key.getCharacter())) {
            case 'q' -> confirmQuit(screen);                 // Quit with confirmation
            case 'e' -> {                                       // Exit-with-save (or close pane in split)
                saveFile();
                if (splitMode) closeSplitPane(screen);
                else shouldQuit = true;
            }
            case 's' -> saveFile();                           // Save
            case 'x' -> {                                      // Split or switch panes
                if (splitMode) activePane = 1 - activePane;
                else enterSplitMode(screen, gui);
            }
            case 'n' -> handleNewFile(screen);               // New file in pane
            // TODO: Append file. Reuse handleNewFile()'s filename-prompt code (rows/redraw
            // logic) to read a path, then Files.readAllLines(path) it and splice the resulting
            // lines into `lines` at (cursorRow, cursorCol) the same way insertBlockAt() splices
            // a block — the cursor stays put on failure. On a missing file or IOException,
            // leave the document untouched and show an error on the status line (see the
            // saveFile() TODO for the same error-surfacing gap) instead of throwing.
            case 'a' -> { /* TODO: Append file */ }
            // TODO: Load more (partial load). This requires the bigger FileSource change
            // described at the class-level compliance comment: replace the constructor's
            // Files.readAllLines() with an incremental reader that keeps a file offset and
            // remaining-byte count, loads only the first N KiB up front, and appends the next
            // chunk here on 'l'. Until FileSource exists, this case has nothing to load (every
            // file is already read in full), so leave it a no-op with this TODO rather than
            // faking partial loads.
            case 'l' -> { /* TODO: L */ }
            // TODO: Write through cursor. Serialize lines[0..cursorRow].substring(0..cursorCol)
            // (i.e. everything before the cursor, using extractBlockContent()-style slicing
            // with markerBeginRow/Col = (0,0) and markerEndRow/Col = cursor) and write it to
            // the existing `outputPath` field (falls back to `activeFileName` when null — see
            // setOutputPath()) without touching `activeFileName`'s saved state or clearing the
            // in-memory buffer.
            case 'w' -> { /* TODO: W */ }
            // TODO: Close output file. Only meaningful once 'w' above actually opens/tracks a
            // write target; prompt "close and discard further writes to <path>? (Y/N)" using
            // the confirmQuit()-style Y/N prompt, then reset `outputPath` to null (via
            // setOutputPath(null)) so a later 'w' falls back to activeFileName instead of
            // silently reusing the closed path.
            case 'c' -> { /* TODO: C */ }
            default  -> { /* cancel */ }
        }
    }

    /** F3-N: offer to open a new file in the current pane. */
    private void handleNewFile(Screen screen) throws IOException {
        int cols         = screen.getTerminalSize().getColumns();
        int paneFirstRow = (splitMode && activePane == 1) ? 13 : 0;

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);

        String msg = "Edit a new file?   (N no,   E yes & save current file,   Q yes but don't save file)";
        tg.putString(0, paneFirstRow,     String.format("%-" + cols + "s", msg));
        tg.putString(0, paneFirstRow + 1, "\u2500".repeat(cols));
        screen.setCursorPosition(null);
        screen.refresh();

        char choice = 0;
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() != KeyType.Character) continue;
            char ch = Character.toLowerCase(k.getCharacter());
            if (ch == 'n' || ch == 'e' || ch == 'q') { choice = ch; break; }
        }

        if (choice == 'n') return;
        if (choice == 'e') saveFile();
        // E or Q: prompt for new filename

        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, paneFirstRow,     String.format("%-" + cols + "s", "Enter file name:"));
        tg.putString(0, paneFirstRow + 1, String.format("%-" + cols + "s", ""));
        tg.putString(0, paneFirstRow + 2, "\u2500".repeat(cols));
        screen.setCursorPosition(new TerminalPosition(0, paneFirstRow + 1));
        screen.refresh();

        StringBuilder nameBuilder = new StringBuilder();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() == KeyType.Escape) return; // cancel
            if (k.getKeyType() == KeyType.Enter) break;
            if (k.getKeyType() == KeyType.Backspace) {
                if (nameBuilder.length() > 0) nameBuilder.deleteCharAt(nameBuilder.length() - 1);
            } else if (k.getKeyType() == KeyType.Character
                    && !k.isCtrlDown() && !k.isAltDown()) {
                nameBuilder.append(k.getCharacter());
            }
            tg.putString(0, paneFirstRow + 1,
                    String.format("%-" + cols + "s", nameBuilder.toString()));
            screen.setCursorPosition(new TerminalPosition(nameBuilder.length(), paneFirstRow + 1));
            screen.refresh();
        }

        // Load new file into active pane's live buffer
        String newName = nameBuilder.toString().trim();
        lines.clear();
        if (!newName.isEmpty()) {
            Path path = Paths.get(newName);
            if (Files.exists(path)) {
                try {
                    for (String line : Files.readAllLines(path))
                        lines.add(new StringBuilder(line));
                } catch (IOException e) {
                    lines.add(new StringBuilder());
                }
            }
        }
        if (lines.isEmpty()) lines.add(new StringBuilder());
        activeFileName = newName;
        cursorRow = 0; cursorCol = 0; scrollRow = 0;
    }

    /** Ask user to confirm quit; on Y: exit (single) or close pane (split). */
    private void confirmQuit(Screen screen) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        // First row of the active pane
        int promptRow = (splitMode && activePane == 1) ? 13 : 0;
        int ruleRow   = promptRow + 1;

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        String msg = "Quit - changes will be lost (Y or N)";
        tg.putString(0, promptRow, String.format("%-" + cols + "s", msg));
        tg.putString(0, ruleRow,   "\u2500".repeat(cols));
        screen.setCursorPosition(null); // hide cursor during confirmation
        screen.refresh();

        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() != KeyType.Character) continue;
            char ch = Character.toLowerCase(k.getCharacter());
            if (ch == 'y') {
                if (splitMode) closeSplitPane(screen);
                else shouldQuit = true;
                return;
            } else if (ch == 'n') {
                return; // just fall through to redraw
            }
        }
    }

    /** Close the active pane, keeping the other pane's content as the single pane. */
    private void closeSplitPane(Screen screen) {
        // At this point the live fields always hold the active pane's data
        // (the main loop pre-swaps when pane 2 is active, so fields2 holds the OTHER pane).
        // Either way, we want to keep what is currently in fields2.
        lines        = lines2;       lines2      = new ArrayList<>(); lines2.add(new StringBuilder());
        cursorRow    = cursorRow2;   cursorRow2  = 0;
        cursorCol    = cursorCol2;   cursorCol2  = 0;
        scrollRow    = scrollRow2;   scrollRow2  = 0;
        insertMode   = insertMode2;  insertMode2 = true;
        activeFileName = fileName2;  fileName2   = "";
        splitMode  = false;
        activePane = 0;
        // Move status bar back to the bottom row
        int lastRow = screen.getTerminalSize().getRows() - 1;
        statusBar.setRow(lastRow);
        fileOpBar.setRow(lastRow);
    }

    /** Swap all mutable live-pane fields between pane 1 and pane 2. */
    private void swapActivePaneData() {
        List<StringBuilder> tmpLines = lines; lines = lines2; lines2 = tmpLines;
        int tmp;
        tmp = cursorRow;  cursorRow  = cursorRow2;  cursorRow2  = tmp;
        tmp = cursorCol;  cursorCol  = cursorCol2;  cursorCol2  = tmp;
        tmp = scrollRow;  scrollRow  = scrollRow2;  scrollRow2  = tmp;
        boolean tmpB = insertMode; insertMode = insertMode2; insertMode2 = tmpB;
        String tmpS = activeFileName; activeFileName = fileName2; fileName2 = tmpS;
        tmp = markerBeginRow; markerBeginRow = markerBeginRow2; markerBeginRow2 = tmp;
        tmp = markerBeginCol; markerBeginCol = markerBeginCol2; markerBeginCol2 = tmp;
        tmp = markerEndRow;   markerEndRow   = markerEndRow2;   markerEndRow2   = tmp;
        tmp = markerEndCol;   markerEndCol   = markerEndCol2;   markerEndCol2   = tmp;
    }

    /** Draw the split-mode filename-entry UI and load the second file. */
    private void enterSplitMode(Screen screen, MultiWindowTextGUI gui) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        int rows = size.getRows();
        int cols = size.getColumns();
        TextGraphics tg = screen.newTextGraphics();

        // Redraw pane 1 content in rows 0-11
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        for (int r = 0; r < 12; r++) {
            int docRow = r + scrollRow;
            String text = (docRow < lines.size()) ? lines.get(docRow).toString() : "";
            String padded = String.format("%-" + cols + "s",
                    text.length() > cols ? text.substring(0, cols) : text);
            tg.putString(0, r, padded);
        }

        // Move status/fileOp bars to row 12 for split mode
        statusBar.setRow(12);
        fileOpBar.setRow(12);
        updateStatusBar();
        statusBar.render(screen);

        // Clear rows 13+
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        for (int r = 13; r < rows; r++) {
            tg.putString(0, r, String.format("%-" + cols + "s", ""));
        }

        // Row 13: prompt in bright white
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, 13, "Enter file name:");

        // Row 15: horizontal rule in bright white
        tg.putString(0, 15, "\u2500".repeat(cols));

        screen.setCursorPosition(new TerminalPosition(0, 14));
        screen.refresh();

        // Read filename into row 14
        StringBuilder nameBuilder = new StringBuilder();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() == KeyType.Enter) break;
            if (k.getKeyType() == KeyType.Escape) return; // cancel, no split
            if (k.getKeyType() == KeyType.Backspace) {
                if (nameBuilder.length() > 0) nameBuilder.deleteCharAt(nameBuilder.length() - 1);
            } else if (k.getKeyType() == KeyType.Character
                    && !k.isCtrlDown() && !k.isAltDown()) {
                nameBuilder.append(k.getCharacter());
            }
            tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
            tg.setBackgroundColor(TextColor.ANSI.BLACK);
            tg.putString(0, 14, String.format("%-" + cols + "s", nameBuilder.toString()));
            screen.setCursorPosition(new TerminalPosition(nameBuilder.length(), 14));
            screen.refresh();
        }

        fileName2 = nameBuilder.toString().trim();

        // Load file into lines2
        lines2.clear();
        if (!fileName2.isEmpty()) {
            Path path = Paths.get(fileName2);
            if (Files.exists(path)) {
                try {
                    for (String line : Files.readAllLines(path))
                        lines2.add(new StringBuilder(line));
                } catch (IOException e) {
                    lines2.add(new StringBuilder());
                }
            }
        }
        if (lines2.isEmpty()) lines2.add(new StringBuilder());

        cursorRow2 = 0; cursorCol2 = 0; scrollRow2 = 0; insertMode2 = true;
        splitMode = true;
        activePane = 1; // focus moves to the newly opened pane
    }

    // ------------------------------------------------------------------ delete helpers

    // TODO(spec: Editing semantics — Undelete/undo): this is a single-slot snapshot
    // (saveUndo overwrites undoLines every call, undoLastDelete consumes it once) covering
    // only the delete-class commands that already call saveUndo(). The spec's compatible
    // contract just says Ctrl-U maps to "undo" with unspecified depth, but its portable
    // design choice explicitly asks for an unbounded undo STACK where every insert/delete/
    // replace — including plain typing and Enter, which never call saveUndo() today — is
    // one transaction. Replace this pair with an UndoStack of transactions (e.g. row-range
    // diffs, not full-buffer string copies) and call it from every mutating handler,
    // including handleKey's Character/Enter cases which currently have no undo support at all.
    private void saveUndo() {
        undoLines = new ArrayList<>();
        for (StringBuilder sb : lines) undoLines.add(sb.toString());
        undoRow = cursorRow;
        undoCol = cursorCol;
    }

    private void undoLastDelete() {
        if (undoLines == null) return;
        lines.clear();
        for (String s : undoLines) lines.add(new StringBuilder(s));
        cursorRow = Math.min(undoRow, lines.size() - 1);
        cursorCol = Math.min(undoCol, lines.get(cursorRow).length());
        undoLines = null;
    }

    /** Ctrl+W – delete one word to the left */
    private void deleteWordLeft() {
        saveUndo();
        if (cursorCol == 0) return;
        String s = lines.get(cursorRow).toString();
        int c = cursorCol - 1;
        while (c > 0 && !Character.isLetterOrDigit(s.charAt(c))) c--;
        while (c > 0 && Character.isLetterOrDigit(s.charAt(c - 1))) c--;
        lines.get(cursorRow).delete(c, cursorCol);
        cursorCol = c;
    }

    /** Alt+W – delete one word to the right */
    private void deleteWordRight() {
        saveUndo();
        String s = lines.get(cursorRow).toString();
        int len = s.length();
        if (cursorCol >= len) return;
        int c = cursorCol;
        while (c < len && !Character.isLetterOrDigit(s.charAt(c))) c++;
        while (c < len && Character.isLetterOrDigit(s.charAt(c))) c++;
        lines.get(cursorRow).delete(cursorCol, c);
    }

    /** Ctrl+L – delete from cursor to beginning of line */
    private void deleteToLineBegin() {
        saveUndo();
        lines.get(cursorRow).delete(0, cursorCol);
        cursorCol = 0;
    }

    /** Alt+L – delete from cursor to end of line */
    private void deleteToLineEnd() {
        saveUndo();
        StringBuilder line = lines.get(cursorRow);
        line.delete(cursorCol, line.length());
    }

    /** Alt+K – delete the entire current line */
    private void killLine() {
        saveUndo();
        if (lines.size() == 1) {
            lines.get(0).setLength(0);
            cursorCol = 0;
        } else {
            lines.remove(cursorRow);
            if (cursorRow >= lines.size()) cursorRow = lines.size() - 1;
            cursorCol = Math.min(cursorCol, lines.get(cursorRow).length());
        }
    }

    /** Ctrl+V – toggle upper/lowercase from cursor back to beginning of line */
    private void toggleCaseToLineBegin() {
        if (cursorCol == 0) return;
        StringBuilder line = lines.get(cursorRow);
        for (int c = 0; c < cursorCol; c++) {
            char ch = line.charAt(c);
            line.setCharAt(c, Character.isUpperCase(ch) ? Character.toLowerCase(ch)
                                                        : Character.toUpperCase(ch));
        }
    }

    /** Alt+V – toggle upper/lowercase from cursor to end of line */
    private void toggleCaseToLineEnd() {
        StringBuilder line = lines.get(cursorRow);
        int end = line.length();
        if (cursorCol >= end) return;
        for (int c = cursorCol; c < end; c++) {
            char ch = line.charAt(c);
            line.setCharAt(c, Character.isUpperCase(ch) ? Character.toLowerCase(ch)
                                                        : Character.toUpperCase(ch));
        }
    }

    // TODO(spec: File I/O — atomic save, binary safety, CRLF preservation, error surfacing):
    // this method has several gaps against the "Portable contract" for file I/O:
    //   - Files.write(path, content) writes UTF-8 text and joins lines with
    //     System.lineSeparator(), so it can't round-trip non-UTF-8/binary content and does
    //     not remember/preserve the source file's original newline style (the document model
    //     itself, List<StringBuilder> lines, has already thrown that information away on
    //     load — this is a load-bearing architectural gap, not just a save-time fix).
    //   - Not atomic: a crash or full disk mid-write can truncate the target file. Spec wants
    //     write-to-temp-file-in-same-directory + flush/fsync + atomic rename.
    //   - The catch block silently swallows IOException; spec requires a clear status-line
    //     error message (it documents specific short compatibility-style messages for I/O
    //     failures) rather than losing the failure entirely.
    private void saveFile() {
        if (activeFileName.isEmpty()) return;
        try {
            Path path = Paths.get(activeFileName);
            List<String> content = new ArrayList<>();
            for (StringBuilder line : lines) content.add(line.toString());
            Files.write(path, content);
        } catch (IOException e) {
            // TODO: surface error to user
        }
    }
}
