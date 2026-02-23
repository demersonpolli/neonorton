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

    // Terminal width cached for status bar centering
    private int statusBarCols = 80;

    // Status bar cached for file-operation overlay
    private StatusBar statusBar;
    private StatusBar fileOpBar;
    private StatusBar blockOpBar;
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
                new int[]   {  0,        11,         centerCol,    58,         67       },
                new String[]{ "Line=1", "Col=1", displayName, "Insert", "WW=Off" }
            );

            // File-operation overlay bar (shown while F3 mode is active)
            fileOpBar = new StatusBar(
                rows - 1,
                new int[]   {  0,          9,               26,     33,     40,                 59,    65,       73,  76,  79 },
                new String[]{ "F3 FILE:", "Exit-with-save", "Quit", "Save", "eXchange-windows", "New", "Append", "L", "W", "C" }
            );

            // Block-operation overlay bar (shown while F4 mode is active)
            blockOpBar = new StatusBar(
                rows - 1,
                new int[]   {  0,           11,            24,     31,     38,               53,                69,  72,  75,  79  },
                new String[]{ "F4 BLOCK:", "Set-marker", "Copy", "Move", "Delete-block", "Remove-marker", "W", "L", "E", "F" }
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

                // F1 — help
                if (type == KeyType.F1) {
                    new HelpScreen().show(gui);
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
                        default  -> {}
                    }
                } else if (key.isAltDown()) {
                    switch (Character.toLowerCase(ch)) {
                        case 'w' -> deleteWordRight();
                        case 'l' -> deleteToLineEnd();
                        case 'k' -> killLine();
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
            case Insert -> insertMode = !insertMode;
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
            case 'a' -> { /* TODO: Append file */ }
            case 'l' -> { /* TODO: L */ }
            case 'w' -> { /* TODO: W */ }
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
