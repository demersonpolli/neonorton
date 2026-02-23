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
            for (int r = 0; r < textRows; r++) {
                int docRow = r + scrollRow;
                String text = (docRow < lines.size()) ? lines.get(docRow).toString() : "";
                String padded = String.format("%-" + cols + "s",
                        text.length() > cols ? text.substring(0, cols) : text);
                tg.putString(0, r, padded);
            }
            updateStatusBar();
            statusBar.render(screen);
            int screenRow = cursorRow - scrollRow;
            int screenCol = Math.min(cursorCol, cols - 1);
            screen.setCursorPosition(new TerminalPosition(screenCol, screenRow));
        } else {
            // ---- Split-pane mode ----
            // Pane 1 always in lines / cursorRow / scrollRow (after any swap-back)
            for (int r = 0; r < 12; r++) {
                int docRow = r + scrollRow;
                String text = (docRow < lines.size()) ? lines.get(docRow).toString() : "";
                String padded = String.format("%-" + cols + "s",
                        text.length() > cols ? text.substring(0, cols) : text);
                tg.putString(0, r, padded);
            }
            // Status bar at row 12
            updateStatusBar();
            statusBar.render(screen);
            // Pane 2 always in lines2 / cursorRow2 / scrollRow2
            int pane2Rows = rows - 13;
            for (int r = 0; r < pane2Rows; r++) {
                int docRow = r + scrollRow2;
                String text = (docRow < lines2.size()) ? lines2.get(docRow).toString() : "";
                String padded = String.format("%-" + cols + "s",
                        text.length() > cols ? text.substring(0, cols) : text);
                tg.putString(0, 13 + r, padded);
            }
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
