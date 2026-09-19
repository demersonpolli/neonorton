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
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorDeviceConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/*
 * NE 1.3C compliance status (see "Norton Editor 1.3C: white-box reconstruction
 * specification"). Compliant: cursor family, Backspace/Del/Ctrl-W/Alt-W/Ctrl-L/
 * Alt-L/Alt-K, F3 E/S/Q/N/X, all F4 block ops, F6 G/M/T, insert-vs-replace EOL
 * behavior, F2 status screen (see StatusScreen.StatusInfo), CLI parsing in Main
 * (+LINE, input/output paths, /DA /DB /DC — parsed and applied; safe mode/
 * encoding are still unapplied, see Main's TODOs), search & replace
 * (Alt-F/Ctrl-F/Alt-C/Ctrl-C, ESC case-insensitive, Ctrl-Return for a literal
 * newline in the search string, Y / N / star (replace all) / Space replace
 * flow — see the "search & replace" section below; literal byte-for-byte
 * matching only, no regex, as specified), and all of F5 Format ops:
 *   - F/L/W/T/I: format paragraph, line length, word-wrap-on-typing, the 3
 *     tab modes + Tab key, auto-indent on Enter (handleFormatOperation() and
 *     handleKey()'s Tab/Enter cases).
 *   - D: document-text display theme (/DA white, /DB green, /DC amber) —
 *     applied live via `displayMode.foreground` in redraw()/drawTextPane().
 *   - S: persists tab/format/display/cursor/print/Ins-key settings to
 *     EditorConfig's on-disk properties file; Main loads it back at startup.
 *   - K: Ins-key behavior (always-insert vs toggle insert/replace); F5 T
 *     already covers "which tab mode Tab uses", so K's scope is just this.
 *   - C: cursor style/shape — the choice is stored and persisted via F5 S,
 *     but Lanterna's TerminalEmulatorDeviceConfiguration is immutable and
 *     has no live-update API, so it only takes effect on the NEXT launch
 *     (Main reads it back before constructing SwingTerminalFrame), not
 *     immediately — F5 C's prompt tells the user this.
 * F7 Printer is also implemented, deliberately deviating from the spec's literal printer
 * feature at the user's request: P (print-all) and B (print-block) each write one paginated
 * PDF (see PdfWriter — dependency-free, Courier/Base-14, Latin-1 text only) named
 * "<file>-print-<yyyy-MM-dd-hh-mm-ss>.pdf" next to the edited file, honoring printMarginLeft
 * (S sets printPageLines, M sets printMarginLeft). E (eject-page) is a documented no-op — a
 * mid-stream form-feed has no meaning when P/B each produce one complete PDF per invocation
 * rather than streaming to an open print job.
 * All of F3 is implemented too: A (append) inserts another file's lines at the cursor; W
 * (write through cursor) writes lines[0..cursor) to `outputPath` (falls back to
 * `activeFileName`); C prompts to close that output target; L (load more) always reports
 * "ENTIRE FILE ALREADY LOADED" — an honest answer given this editor loads every file in full
 * up front (see the File I/O bullet below), not a faked partial-load response.
 * F9 is implemented too, deliberately deviating from the spec's embedded DOS command line at
 * the user's request: it Y/N-confirms, then opens a real, separate, OS-native shell window in
 * the active file's folder (see ShellLauncher) — cmd.exe on Windows, Terminal.app on macOS, the
 * first available terminal emulator on Linux. See ShellLauncher's class doc for the honest
 * verification status: this was built inside a sandboxed agent environment where even a bare,
 * un-Java'd `cmd.exe /K` never produces a real console window (isolated to that sandbox's
 * console-window allocation, not this code — an AWT/Swing GUI window launched the same way
 * works fine there), so none of the three platforms' "does a real window actually appear and
 * accept input" behavior has been empirically confirmed on a real desktop session yet.
 * Ctrl-P (insert a raw byte by hex value) and a real multi-level undo are implemented too: an
 * UndoEntry stack (undoStack, capped at UNDO_STACK_LIMIT), one per pane like the other
 * per-pane state, pushed by saveUndo() before every mutating command — typing included, via
 * insertChar()'s `coalesce` flag, which merges a run of consecutive plain keystrokes into one
 * checkpoint (a deliberate UX/performance tradeoff over one checkpoint per character; every
 * other command still gets its own checkpoint). Each checkpoint is a full-buffer snapshot, not
 * a per-line diff — a simplicity/memory tradeoff reasonable at this editor's scale, not
 * literally unbounded (capped, oldest entries evicted first).
 * Not yet compliant, see TODOs at each site below:
 *   - Tab's LITERAL mode inserts a real '\t' byte but the renderer
 *     (drawTextPane) does no column-width expansion for it, so a literal tab
 *     will visually misalign — a pre-existing renderer limitation, not fixed
 *     here; SPACES/MOVE modes (the default) have no such issue.
 *   - File I/O is line-based UTF-8 text (Files.readAllLines/write): no CRLF
 *     preservation, no binary/byte-safe mode, no atomic save, no incremental
 *     load for large files.
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
    private boolean indent     = false;  // F5 I: auto-indent — Enter copies the current line's leading whitespace

    // Format/print/tab configuration — these are the F5/F7 command targets. The fields exist
    // now (for the F2 status screen) with sensible defaults; F7 still needs to let the user
    // change its fields interactively, see the TODOs on its handler method below.
    private int wrapColumn      = 0;   // F5 L: format/word-wrap line length; 0 = off/unset
    private int tabWidth        = 8;   // F5 T: tab display width
    private int printMarginLeft = 0;   // F7 M: left margin for printing
    private int printPageLines  = 0;   // F7 S: lines per printed page; 0 = no pagination

    /** F5 T's 3 tab modes (spec: "Editing semantics" / tab configuration dialog). */
    private enum TabMode { LITERAL, SPACES, MOVE }
    private TabMode tabMode = TabMode.SPACES; // default avoids the LITERAL mode's rendering caveat, see handleKey's Tab case

    /** F5 D's 3 display themes (CLI's /DA /DB /DC). Palette values aren't recoverable from the
     *  spec's listing, so these are a design choice; only the document-text foreground changes
     *  — background stays black and the status bar / block highlight colors are unaffected. */
    private enum DisplayMode {
        DA(TextColor.ANSI.WHITE), DB(TextColor.ANSI.GREEN), DC(TextColor.ANSI.YELLOW);
        final TextColor.ANSI foreground;
        DisplayMode(TextColor.ANSI foreground) { this.foreground = foreground; }
    }
    private DisplayMode displayMode = DisplayMode.DA; // F5 D; overridable at startup via setDisplayMode()

    // F5 C: cursor shape/style. Lanterna's TerminalEmulatorDeviceConfiguration is immutable and
    // only settable when the SwingTerminalFrame is constructed (no live-update API exists), so
    // this field can't be applied while running — it only takes effect on the NEXT launch, after
    // F5 S saves it and Main reads it back to build the frame. F5 C's prompt says so.
    private TerminalEmulatorDeviceConfiguration.CursorStyle cursorStyle =
            TerminalEmulatorDeviceConfiguration.CursorStyle.REVERSED;

    // F5 K: Ins-key behavior. false (default) = Ins always forces insert mode, matching the
    // current/original behavior; true = Ins toggles insert/replace instead. F5 T already covers
    // "which tab mode Tab uses" from the spec's F5 K description, so K's scope here is just this.
    private boolean insToggles = false;

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

    /** One undo transaction: a full-buffer snapshot plus the cursor position to restore to.
     *  Whole-buffer snapshots (not per-line diffs) are a deliberate simplicity/memory tradeoff
     *  reasonable at this editor's scale — see the class-level compliance note. */
    private record UndoEntry(List<String> lines, int cursorRow, int cursorCol) {}

    // Multi-level undo stack (Ctrl-U) — one per pane, swapped alongside the other per-pane
    // state in swapActivePaneData(). Capped so a very long session can't grow it unbounded.
    private static final int UNDO_STACK_LIMIT = 500;
    private Deque<UndoEntry> undoStack  = new ArrayDeque<>();
    private Deque<UndoEntry> undoStack2 = new ArrayDeque<>();

    // True right after insertChar() runs for a plain typed character (not Ctrl-P, not any
    // other command); lets a run of consecutive keystrokes share one undo step instead of
    // costing one snapshot per character. Reset to false at the top of every handleKey() call,
    // then set back to true only by the plain-character branch — so any other key in between
    // (arrows, Enter, Backspace, a command...) naturally ends the run.
    private boolean typingRunActive = false;

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

    // ---- Search state (Alt-F/Ctrl-F/Alt-C/Ctrl-C) ----
    // The active search string (may contain '\n' via Ctrl-Return); null = no search yet.
    private String lastSearchTerm = null;
    // Set when the term was entered by pressing ESC instead of Enter (spec: ESC during term
    // entry means case-insensitive ASCII a-z matching).
    private boolean lastSearchCaseInsensitive = false;
    // True after Alt-F's/Ctrl-F's term-entry step, until a replace session runs and consumes
    // it. Pressing the SAME direction key again while pending prompts for a replacement and
    // starts the Y/N/*/Space replace loop, per the spec's two-step search-and-replace flow.
    private boolean forwardFindPending = false;
    private boolean reverseFindPending = false;

    /** Result of promptTextLine(): the text typed, and whether it was finished via ESC. */
    private record TextInput(String text, boolean escaped) {}

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

    /** Set the CLI's /DA, /DB, /DC display mode (case-insensitive "da"/"db"/"dc"); invalid or
     *  null values are ignored, leaving whatever applyConfig()/the default already set. */
    public void setDisplayMode(String mode) {
        if (mode == null) return;
        switch (mode.toLowerCase()) {
            case "da" -> displayMode = DisplayMode.DA;
            case "db" -> displayMode = DisplayMode.DB;
            case "dc" -> displayMode = DisplayMode.DC;
            default -> { /* ignore invalid */ }
        }
    }

    /** Apply a loaded EditorConfig (F5 S's counterpart) — call once at startup, before any CLI
     *  flag overrides (e.g. setDisplayMode from /DA /DB /DC), so explicit flags win. Invalid
     *  enum names in the file are ignored, leaving the built-in default for that field. */
    public void applyConfig(EditorConfig cfg) {
        tabWidth = cfg.tabWidth;
        try { tabMode = TabMode.valueOf(cfg.tabMode); } catch (IllegalArgumentException ignored) {}
        wrapColumn = cfg.wrapColumn;
        wordWrap = cfg.wordWrap;
        indent = cfg.indent;
        try { displayMode = DisplayMode.valueOf(cfg.displayMode); } catch (IllegalArgumentException ignored) {}
        try { cursorStyle = TerminalEmulatorDeviceConfiguration.CursorStyle.valueOf(cfg.cursorStyle); }
        catch (IllegalArgumentException ignored) {}
        printMarginLeft = cfg.printMarginLeft;
        printPageLines = cfg.printPageLines;
        insToggles = cfg.insToggles;
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

                // F9 — open an OS shell/command-prompt window (see ShellLauncher)
                if (type == KeyType.F9) {
                    boolean pane2 = splitMode && activePane == 1;
                    String fn = pane2 ? fileName2 : activeFileName;
                    File workDir;
                    if (fn == null || fn.isEmpty()) {
                        workDir = new File(".");
                    } else {
                        Path parent = Paths.get(fn).toAbsolutePath().getParent();
                        workDir = (parent != null) ? parent.toFile() : new File(".");
                    }
                    if (confirmYesNo(screen, "Open a system shell here? (Y or N)")) {
                        try {
                            ShellLauncher.openInteractiveShell(workDir);
                        } catch (ShellLauncher.LaunchException e) {
                            showMessage(screen, "COULDN'T OPEN SHELL: " + e.getMessage());
                        }
                    }
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

    private void handleKey(KeyStroke key, Screen screen) throws IOException {
        KeyType type = key.getKeyType();
        TerminalSize size = screen.getTerminalSize();
        int textRows;
        if (splitMode) {
            textRows = (activePane == 0) ? 12 : size.getRows() - 13;
        } else {
            textRows = size.getRows() - 1; // last row is status bar
        }
        int cols = size.getColumns();

        // Reset by default; only the plain-character branch below sets this back to true, so
        // any other key ends a coalesced typing run (see the field's own comment).
        boolean wasTyping = typingRunActive;
        typingRunActive = false;

        switch (type) {
            case Character -> {
                char ch = key.getCharacter();
                if (key.isCtrlDown()) {
                    switch (Character.toLowerCase(ch)) {
                        case 'w' -> deleteWordLeft();
                        case 'l' -> deleteToLineBegin();
                        case 'u' -> undo();
                        case 'v' -> toggleCaseToLineBegin();
                        case 'f' -> searchReverse(screen);
                        case 'c' -> continueReverse(screen);
                        case 'p' -> {
                            Integer b = promptHexByte(screen);
                            if (b != null) insertChar((char) b.intValue(), false);
                        }
                        default  -> {}
                    }
                } else if (key.isAltDown()) {
                    switch (Character.toLowerCase(ch)) {
                        case 'w' -> deleteWordRight();
                        case 'l' -> deleteToLineEnd();
                        case 'k' -> killLine();
                        case 'v' -> toggleCaseToLineEnd();
                        case 'f' -> searchForward(screen);
                        case 'c' -> continueForward(screen);
                        default  -> {}
                    }
                } else {
                    insertChar(ch, wasTyping);
                    typingRunActive = true;
                }
            }
            case Insert -> insertMode = insToggles ? !insertMode : true; // F5 K controls which
            case Enter -> {
                saveUndo();
                StringBuilder current = lines.get(cursorRow);
                String tail = current.substring(cursorCol);
                current.delete(cursorCol, current.length());
                String leadingWs = "";
                if (indent) {
                    String cur = current.toString();
                    int i = 0;
                    while (i < cur.length() && (cur.charAt(i) == ' ' || cur.charAt(i) == '\t')) i++;
                    leadingWs = cur.substring(0, i);
                }
                lines.add(cursorRow + 1, new StringBuilder(leadingWs + tail));
                cursorRow++;
                cursorCol = leadingWs.length();
            }
            case Tab -> {
                switch (tabMode) {
                    case LITERAL -> {
                        saveUndo();
                        lines.get(cursorRow).insert(cursorCol, '\t');
                        cursorCol++;
                    }
                    case SPACES -> {
                        saveUndo();
                        int n = tabWidth - (cursorCol % tabWidth);
                        lines.get(cursorRow).insert(cursorCol, " ".repeat(n));
                        cursorCol += n;
                    }
                    // MOVE doesn't mutate the buffer, so no undo checkpoint is needed here.
                    case MOVE -> cursorCol = Math.min(
                            cursorCol + (tabWidth - (cursorCol % tabWidth)), lines.get(cursorRow).length());
                }
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
            // NOTE: in split mode, Tab is still consumed earlier in show() to switch panes and
            // never reaches this switch at all — only single-pane mode gets the case above.
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

    /** F5 W follow-up: after a character is typed, if word-wrap is on and the line now exceeds
     *  wrapColumn, push the trailing word onto a new line — breaking at the last space at/before
     *  the column limit. A single word longer than wrapColumn is left to overflow rather than
     *  broken mid-word. */
    private void applyWordWrap() {
        if (!wordWrap || wrapColumn <= 0) return;
        StringBuilder line = lines.get(cursorRow);
        if (line.length() <= wrapColumn) return;
        int sp = -1;
        for (int c = Math.min(wrapColumn, line.length() - 1); c >= 0; c--) {
            if (line.charAt(c) == ' ') { sp = c; break; }
        }
        if (sp < 0) return;
        String overflow = line.substring(sp + 1);
        line.delete(sp, line.length()); // also removes the breaking space
        lines.add(cursorRow + 1, new StringBuilder(overflow));
        if (cursorCol > sp) {
            cursorRow++;
            cursorCol -= (sp + 1);
        }
    }

    /** Insert or overwrite `ch` at the cursor per insert/replace mode, advance the cursor, and
     *  apply word-wrap if enabled. Shared by plain typing (handleKey's Character case, which
     *  coalesces consecutive keystrokes via `coalesce`) and Ctrl-P's literal-byte insertion
     *  (which always passes false — each is its own distinct, deliberate undo step). */
    private void insertChar(char ch, boolean coalesce) {
        if (!coalesce || undoStack.isEmpty()) saveUndo();
        StringBuilder line = lines.get(cursorRow);
        if (insertMode || cursorCol >= line.length()) {
            line.insert(cursorCol, ch);
        } else {
            line.setCharAt(cursorCol, ch);
        }
        cursorCol++;
        applyWordWrap();
    }

    /** Ctrl-P's raw-byte prompt: reads up to 2 hex digits and returns the resulting byte value
     *  (0-255), or null on Escape, empty input, or a non-hex character. */
    private Integer promptHexByte(Screen screen) throws IOException {
        int cols      = screen.getTerminalSize().getColumns();
        int promptRow = (splitMode && activePane == 1) ? 13 : 0;
        int inputRow  = promptRow + 1;
        int ruleRow   = promptRow + 2;

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, promptRow, String.format("%-" + cols + "s", "Insert byte (hex 00-FF):"));
        tg.putString(0, inputRow,  String.format("%-" + cols + "s", ""));
        tg.putString(0, ruleRow,   "─".repeat(cols));
        screen.setCursorPosition(new TerminalPosition(0, inputRow));
        screen.refresh();

        StringBuilder hex = new StringBuilder();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() == KeyType.Escape) return null;
            if (k.getKeyType() == KeyType.Enter) break;
            if (k.getKeyType() == KeyType.Backspace) {
                if (hex.length() > 0) hex.deleteCharAt(hex.length() - 1);
            } else if (k.getKeyType() == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()
                    && hex.length() < 2 && isHexDigit(k.getCharacter())) {
                hex.append(Character.toUpperCase(k.getCharacter()));
            }
            tg.putString(0, inputRow, String.format("%-" + cols + "s", hex.toString()));
            screen.setCursorPosition(new TerminalPosition(hex.length(), inputRow));
            screen.refresh();
        }
        if (hex.length() == 0) return null;
        try {
            return Integer.parseInt(hex.toString(), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // ------------------------------------------------------------------ search & replace

    /** Alt-F: forward find, or — if a term is already pending on this direction — prompt for a
     *  replacement and run the forward replace loop (the spec's two-step search-and-replace:
     *  invoking the same direction key twice turns "find" into "find & replace"). */
    private void searchForward(Screen screen) throws IOException {
        if (!forwardFindPending) {
            TextInput in = promptTextLine(screen, "Search (forward), ESC = case-insensitive:");
            if (in.text().isEmpty()) return;
            lastSearchTerm = in.text();
            lastSearchCaseInsensitive = in.escaped();
            forwardFindPending = true;
            reverseFindPending = false;
            runFind(screen, true, false);
        } else {
            runReplace(screen, true);
            forwardFindPending = false;
            reverseFindPending = false;
        }
    }

    /** Ctrl-F: reverse find / start a reverse replace session — mirror of searchForward(). */
    private void searchReverse(Screen screen) throws IOException {
        if (!reverseFindPending) {
            TextInput in = promptTextLine(screen, "Search (reverse), ESC = case-insensitive:");
            if (in.text().isEmpty()) return;
            lastSearchTerm = in.text();
            lastSearchCaseInsensitive = in.escaped();
            reverseFindPending = true;
            forwardFindPending = false;
            runFind(screen, false, false);
        } else {
            runReplace(screen, false);
            forwardFindPending = false;
            reverseFindPending = false;
        }
    }

    /** Alt-C: repeat the last forward find, non-overlapping with the current cursor position. */
    private void continueForward(Screen screen) throws IOException {
        if (lastSearchTerm == null) { showMessage(screen, "NO PREVIOUS SEARCH"); return; }
        runFind(screen, true, true);
    }

    /** Ctrl-C: repeat the last reverse find, non-overlapping with the current cursor position. */
    private void continueReverse(Screen screen) throws IOException {
        if (lastSearchTerm == null) { showMessage(screen, "NO PREVIOUS SEARCH"); return; }
        runFind(screen, false, true);
    }

    /** Move the cursor to the next/previous match of lastSearchTerm. A fresh find starts AT the
     *  cursor (advanceFirst = false); "continue" starts one position past the cursor so it can
     *  never re-match what's already selected (advanceFirst = true). */
    private void runFind(Screen screen, boolean forward, boolean advanceFirst) throws IOException {
        int row = cursorRow, col = cursorCol;
        if (advanceFirst) {
            if (forward) {
                col++;
                if (col > lines.get(row).length()) {
                    row++; col = 0;
                    if (row >= lines.size()) { showMessage(screen, "SEARCH STRING NOT FOUND"); return; }
                }
            } else {
                col--;
                if (col < 0) {
                    row--;
                    if (row < 0) { showMessage(screen, "SEARCH STRING NOT FOUND"); return; }
                    col = lines.get(row).length();
                }
            }
        }
        int[] m = forward
                ? findForward(lines, lastSearchTerm, lastSearchCaseInsensitive, row, col)
                : findReverse(lines, lastSearchTerm, lastSearchCaseInsensitive, row, col);
        if (m == null) { showMessage(screen, "SEARCH STRING NOT FOUND"); return; }
        moveCursorTo(screen, m[0], m[1]);
    }

    /** Prompt for a replacement string, then walk matches of lastSearchTerm from the cursor in
     *  the given direction, replacing per the classic Y (replace, continue) / N (skip, continue)
     *  / * (replace this and all remaining without asking) / Space (stop) prompt. */
    private void runReplace(Screen screen, boolean forward) throws IOException {
        if (lastSearchTerm == null || lastSearchTerm.isEmpty()) {
            showMessage(screen, "INVALID SEARCH & REPLACE ARGUMENTS");
            return;
        }
        TextInput in = promptTextLine(screen, "Replace with:");
        String replacement = in.text();

        boolean replaceAll = false;
        boolean any = false;
        int row = cursorRow, col = cursorCol;
        while (true) {
            int[] m = forward
                    ? findForward(lines, lastSearchTerm, lastSearchCaseInsensitive, row, col)
                    : findReverse(lines, lastSearchTerm, lastSearchCaseInsensitive, row, col);
            if (m == null) {
                if (!any) showMessage(screen, "SEARCH STRING NOT FOUND");
                return;
            }
            moveCursorTo(screen, m[0], m[1]);
            redraw(screen); // show the match before asking what to do with it

            boolean doReplace;
            if (replaceAll) {
                doReplace = true;
            } else {
                char resp = promptReplaceAction(screen);
                if (resp == ' ') return; // quit
                if (resp == '*') { replaceAll = true; doReplace = true; }
                else doReplace = (resp == 'y');
            }

            if (doReplace) {
                saveUndo();
                int[] end = replaceMatch(m[0], m[1], m[2], m[3], replacement);
                any = true;
                row = end[0]; col = end[1];
                if (!forward) {
                    // continue scanning backward from just before the inserted replacement
                    col--;
                    if (col < 0) {
                        row--;
                        if (row < 0) return;
                        col = lines.get(row).length();
                    }
                }
            } else {
                // skip this match without replacing, non-overlapping with it
                if (forward) {
                    row = m[2]; col = m[3];
                } else {
                    col = m[1] - 1;
                    if (col < 0) {
                        row = m[0] - 1;
                        if (row < 0) return;
                        col = lines.get(row).length();
                    }
                }
            }
        }
    }

    /** Show the classic replace prompt and return the response: 'y', 'n', '*', or ' ' (quit). */
    private char promptReplaceAction(Screen screen) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        int row  = (splitMode && activePane == 1) ? 13 : 0;
        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, row, String.format("%-" + cols + "s", "Replace?  Y=yes  N=no  *=all  Space=quit"));
        screen.setCursorPosition(null);
        screen.refresh();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() != KeyType.Character) continue;
            char ch = k.getCharacter();
            if (ch == ' ' || ch == '*') return ch;
            char lower = Character.toLowerCase(ch);
            if (lower == 'y' || lower == 'n') return lower;
        }
    }

    /** Move the cursor to (row, col) and scroll it into view, mirroring handleKey's own
     *  end-of-method scroll adjustment (recomputed here since it's called from other methods). */
    private void moveCursorTo(Screen screen, int row, int col) {
        cursorRow = row;
        cursorCol = col;
        int textRows = splitMode
                ? ((activePane == 0) ? 12 : screen.getTerminalSize().getRows() - 13)
                : screen.getTerminalSize().getRows() - 1;
        if (cursorRow < scrollRow) scrollRow = cursorRow;
        else if (cursorRow >= scrollRow + textRows) scrollRow = cursorRow - textRows + 1;
    }

    /** Show a one-line message on the prompt row and wait for any keypress to dismiss it. */
    private void showMessage(Screen screen, String msg) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        int row  = (splitMode && activePane == 1) ? 13 : 0;
        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, row, String.format("%-" + cols + "s", msg + "  (press any key)"));
        screen.setCursorPosition(null);
        screen.refresh();
        screen.readInput();
    }

    /**
     * Draws a 3-row prompt (label / input / rule), matching goToLineNumber()'s layout, and reads
     * a line of text. Backspace deletes; Ctrl-Return inserts a literal '\n' (shown as '¶' in
     * the input preview) without finishing entry — this is the spec's "search string may include
     * newline via Ctrl-Return"; Enter finishes normally; Escape finishes too, reported via
     * TextInput.escaped (search-term entry uses this to mean "case-insensitive", per spec).
     */
    private TextInput promptTextLine(Screen screen, String label) throws IOException {
        int cols      = screen.getTerminalSize().getColumns();
        int promptRow = (splitMode && activePane == 1) ? 13 : 0;
        int inputRow  = promptRow + 1;
        int ruleRow   = promptRow + 2;

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, promptRow, String.format("%-" + cols + "s", label));
        tg.putString(0, inputRow,  String.format("%-" + cols + "s", ""));
        tg.putString(0, ruleRow,   "─".repeat(cols));
        screen.setCursorPosition(new TerminalPosition(0, inputRow));
        screen.refresh();

        StringBuilder text = new StringBuilder();
        boolean escaped = false;
        while (true) {
            KeyStroke k = screen.readInput();
            KeyType t = k.getKeyType();
            if (t == KeyType.Enter && k.isCtrlDown()) {
                text.append('\n');
            } else if (t == KeyType.Enter) {
                break;
            } else if (t == KeyType.Escape) {
                escaped = true;
                break;
            } else if (t == KeyType.Backspace) {
                if (text.length() > 0) text.deleteCharAt(text.length() - 1);
            } else if (t == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()) {
                text.append(k.getCharacter());
            }
            String display = text.toString().replace('\n', '¶');
            String shown = display.length() > cols ? display.substring(display.length() - cols) : display;
            tg.putString(0, inputRow, String.format("%-" + cols + "s", shown));
            screen.setCursorPosition(new TerminalPosition(shown.length(), inputRow));
            screen.refresh();
        }
        return new TextInput(text.toString(), escaped);
    }

    /** ASCII-only case fold (a-z -> A-Z) — the spec's confirmed case-insensitive matching rule. */
    private static String foldAsciiUpper(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return out.toString();
    }

    /**
     * Tests whether `term` (pre-split on '\n' into termLines) matches `paneLines` starting at
     * (row, col). A multi-line term requires each embedded newline to land on a real line
     * boundary: every segment before the last must run exactly to the end of its line, and the
     * final segment matches as a prefix of its line. Returns the position just past the match
     * as {endRow, endCol}, or null if it doesn't match here.
     */
    private int[] matchAt(List<StringBuilder> paneLines, int row, int col, String[] termLines,
                           boolean caseInsensitive) {
        for (int i = 0; i < termLines.length; i++) {
            int r = row + i;
            if (r >= paneLines.size()) return null;
            String line = paneLines.get(r).toString();
            int startCol = (i == 0) ? col : 0;
            if (startCol > line.length()) return null;
            String hay    = caseInsensitive ? foldAsciiUpper(line)          : line;
            String needle = caseInsensitive ? foldAsciiUpper(termLines[i])  : termLines[i];
            boolean lastSeg = (i == termLines.length - 1);
            if (lastSeg) {
                if (startCol + needle.length() > hay.length()) return null;
                if (!hay.regionMatches(startCol, needle, 0, needle.length())) return null;
                return new int[]{ r, startCol + needle.length() };
            } else {
                // a segment before an embedded newline must consume the rest of this line
                if (hay.length() - startCol != needle.length()) return null;
                if (!hay.regionMatches(startCol, needle, 0, needle.length())) return null;
            }
        }
        return null; // unreachable (termLines always has at least one element)
    }

    /** Forward scan for `term` starting at/after (fromRow, fromCol). Returns {matchRow, matchCol,
     *  endRow, endCol}, or null if not found. */
    private int[] findForward(List<StringBuilder> paneLines, String term, boolean caseInsensitive,
                               int fromRow, int fromCol) {
        if (term == null || term.isEmpty()) return null;
        String[] termLines = term.split("\n", -1);
        for (int r = fromRow; r < paneLines.size(); r++) {
            int startC = (r == fromRow) ? fromCol : 0;
            int lineLen = paneLines.get(r).length();
            for (int c = startC; c <= lineLen; c++) {
                int[] end = matchAt(paneLines, r, c, termLines, caseInsensitive);
                if (end != null) return new int[]{ r, c, end[0], end[1] };
            }
        }
        return null;
    }

    /** Reverse scan for `term`: the match whose START position is nearest to, and not after,
     *  (fromRow, fromCol). Returns {matchRow, matchCol, endRow, endCol}, or null if not found. */
    private int[] findReverse(List<StringBuilder> paneLines, String term, boolean caseInsensitive,
                               int fromRow, int fromCol) {
        if (term == null || term.isEmpty()) return null;
        String[] termLines = term.split("\n", -1);
        for (int r = fromRow; r >= 0; r--) {
            int startC = (r == fromRow) ? fromCol : paneLines.get(r).length();
            for (int c = startC; c >= 0; c--) {
                int[] end = matchAt(paneLines, r, c, termLines, caseInsensitive);
                if (end != null) return new int[]{ r, c, end[0], end[1] };
            }
        }
        return null;
    }

    /** Delete lines[beginRow:beginCol .. endRow:endCol) in place. Independent of the F4 block
     *  markers on purpose — search & replace must not disturb the user's own block selection. */
    private void deleteRange(int beginRow, int beginCol, int endRow, int endCol) {
        if (beginRow == endRow) {
            lines.get(beginRow).delete(beginCol, endCol);
        } else {
            String prefix = lines.get(beginRow).substring(0, beginCol);
            String suffix = lines.get(endRow).substring(endCol);
            lines.get(beginRow).setLength(0);
            lines.get(beginRow).append(prefix).append(suffix);
            for (int r = endRow; r > beginRow; r--) lines.remove(r);
        }
    }

    /** Insert `text` (may contain '\n') at (row, col). Returns the position just past it. */
    private int[] insertTextAt(int row, int col, String text) {
        String[] parts = text.split("\n", -1);
        StringBuilder target = lines.get(row);
        String after = target.substring(col);
        target.setLength(col);
        target.append(parts[0]);
        if (parts.length == 1) {
            target.append(after);
            return new int[]{ row, col + parts[0].length() };
        }
        for (int i = 1; i < parts.length - 1; i++) {
            lines.add(row + i, new StringBuilder(parts[i]));
        }
        int lastIdx = row + parts.length - 1;
        String lastPart = parts[parts.length - 1];
        lines.add(lastIdx, new StringBuilder(lastPart + after));
        return new int[]{ lastIdx, lastPart.length() };
    }

    /** Replace lines[beginRow:beginCol .. endRow:endCol) with `replacement`. Returns the
     *  position just past the inserted replacement text. */
    private int[] replaceMatch(int beginRow, int beginCol, int endRow, int endCol, String replacement) {
        deleteRange(beginRow, beginCol, endRow, endCol);
        return insertTextAt(beginRow, beginCol, replacement);
    }

    private void redraw(Screen screen) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        int rows = size.getRows();
        int cols = size.getColumns();

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(displayMode.foreground);
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
                tg.setForegroundColor(displayMode.foreground);
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
                    TextColor fg = inBlock ? TextColor.ANSI.BLACK : displayMode.foreground;
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

        // "Printer" output is redirected to a PDF file next to the edited document (see
        // PdfWriter) rather than an OS print command or a parallel port — a deliberate
        // deviation from the spec's literal printer feature, at the user's request.
        switch (Character.toLowerCase(key.getCharacter())) {
            case 'p' -> {
                List<String> content = new ArrayList<>();
                for (StringBuilder line : lines) content.add(line.toString());
                printToPdf(screen, content);
            }
            case 'b' -> {
                if (!hasFullMarkers()) { showMessage(screen, "TWO BLOCK MARKERS NEEDED"); return; }
                printToPdf(screen, extractBlockContent());
            }
            // Eject-page (a mid-stream form-feed) doesn't apply to this batch-PDF model: P/B
            // each write one complete, already-paginated PDF per invocation, so there's no open
            // print job to send a form-feed to. Left as a no-op rather than removed, so the
            // overlay's key list still matches the spec's F7 command set.
            case 'e' -> { /* no-op: doesn't apply to batch PDF output */ }
            case 's' -> {
                Integer v = promptInt(screen, "Lines per printed page (0 = single page):");
                if (v != null && v >= 0) printPageLines = v;
            }
            case 'm' -> {
                Integer v = promptInt(screen, "Print left margin (spaces):");
                if (v != null && v >= 0) printMarginLeft = v;
            }
            default  -> { /* cancel */ }
        }
    }

    /** F7 P/B: write `content` (padded by `printMarginLeft`, paginated by `printPageLines`) as
     *  a PDF named "<file>-print-<yyyy-MM-dd-hh-mm-ss>.pdf" next to the edited file. */
    private void printToPdf(Screen screen, List<String> content) throws IOException {
        if (content == null || content.isEmpty()) { showMessage(screen, "NOTHING TO PRINT"); return; }
        String margin = " ".repeat(Math.max(0, printMarginLeft));
        List<String> withMargin = new ArrayList<>(content.size());
        for (String l : content) withMargin.add(margin + l);

        Path out = buildPrintOutputPath();
        try {
            PdfWriter.write(out, withMargin, printPageLines);
            showMessage(screen, "PRINTED TO " + out.getFileName());
        } catch (IOException e) {
            showMessage(screen, "PRINT FAILED: " + e.getMessage());
        }
    }

    /** Builds <folder-of-active-file>/<filename-without-extension>-print-<timestamp>.pdf, per
     *  the requested "yyyy-MM-dd-hh-mm-ss" format (note: lowercase hh is a 12-hour clock hour
     *  with no AM/PM marker, exactly as specified). An unsaved buffer falls back to "untitled"
     *  in the current working directory. */
    private Path buildPrintOutputPath() {
        Path dir;
        String baseName;
        if (activeFileName == null || activeFileName.isEmpty()) {
            dir = Paths.get(".");
            baseName = "untitled";
        } else {
            Path p = Paths.get(activeFileName).toAbsolutePath();
            dir = (p.getParent() != null) ? p.getParent() : Paths.get(".");
            String fn = p.getFileName().toString();
            int dot = fn.lastIndexOf('.');
            baseName = (dot > 0) ? fn.substring(0, dot) : fn;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-hh-mm-ss"));
        return dir.resolve(baseName + "-print-" + timestamp + ".pdf");
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
            case 'f' -> formatParagraph(screen);
            case 'l' -> {
                Integer v = promptInt(screen, "Format/word-wrap line length:");
                if (v != null && v > 0) wrapColumn = v;
            }
            case 'w' -> wordWrap = !wordWrap;
            case 't' -> {
                Integer w = promptInt(screen, "Tab width:");
                if (w != null && w > 0) tabWidth = w;
                TabMode m = promptTabMode(screen);
                if (m != null) tabMode = m;
            }
            case 'c' -> {
                TerminalEmulatorDeviceConfiguration.CursorStyle s = promptCursorStyle(screen);
                if (s != null) {
                    cursorStyle = s;
                    showMessage(screen, "CURSOR STYLE SET - TAKES EFFECT AFTER F5 S AND RESTART");
                }
            }
            case 'd' -> {
                DisplayMode m = promptDisplayMode(screen);
                if (m != null) displayMode = m;
            }
            case 'i' -> indent = !indent;
            case 's' -> {
                EditorConfig cfg = new EditorConfig();
                cfg.tabWidth = tabWidth;
                cfg.tabMode = tabMode.name();
                cfg.wrapColumn = wrapColumn;
                cfg.wordWrap = wordWrap;
                cfg.indent = indent;
                cfg.displayMode = displayMode.name();
                cfg.cursorStyle = cursorStyle.name();
                cfg.printMarginLeft = printMarginLeft;
                cfg.printPageLines = printPageLines;
                cfg.insToggles = insToggles;
                boolean ok = EditorConfig.save(cfg);
                showMessage(screen, ok ? "CONFIGURATION SAVED" : "FAILED TO SAVE CONFIGURATION");
            }
            case 'k' -> insToggles = !insToggles;
            default  -> { /* cancel */ }
        }
    }

    /** F5 F: reflow the paragraph containing the cursor (a blank-line-delimited run of lines)
     *  at `wrapColumn`, collapsing internal whitespace to single spaces and re-applying the
     *  paragraph's own leading indent to every produced line. Refuses if a marked block
     *  overlaps the paragraph, so F4's selection is never silently reformatted away. */
    private void formatParagraph(Screen screen) throws IOException {
        if (wrapColumn <= 0) { showMessage(screen, "SET FORMAT WIDTH FIRST (F5 L)"); return; }
        if (lines.get(cursorRow).toString().isBlank()) return; // cursor not inside a paragraph

        int start = cursorRow;
        while (start > 0 && !lines.get(start - 1).toString().isBlank()) start--;
        int end = cursorRow;
        while (end < lines.size() - 1 && !lines.get(end + 1).toString().isBlank()) end++;

        if (hasFullMarkers() && !(markerEndRow < start || markerBeginRow > end)) {
            showMessage(screen, "CAN'T FORMAT: BLOCK MARKED IN THIS PARAGRAPH");
            return;
        }

        String first = lines.get(start).toString();
        int indentLen = 0;
        while (indentLen < first.length() && first.charAt(indentLen) == ' ') indentLen++;
        String paraIndent = " ".repeat(indentLen);

        StringBuilder joined = new StringBuilder();
        for (int r = start; r <= end; r++) {
            if (joined.length() > 0) joined.append(' ');
            joined.append(lines.get(r).toString().trim());
        }

        List<String> wrapped = new ArrayList<>();
        StringBuilder cur = new StringBuilder(paraIndent);
        boolean lineHasWord = false;
        for (String w : joined.toString().split("\\s+")) {
            if (w.isEmpty()) continue;
            int extra = lineHasWord ? 1 : 0;
            if (lineHasWord && cur.length() + extra + w.length() > wrapColumn) {
                wrapped.add(cur.toString());
                cur = new StringBuilder(paraIndent);
                lineHasWord = false;
            }
            if (lineHasWord) cur.append(' ');
            cur.append(w);
            lineHasWord = true;
        }
        wrapped.add(cur.toString());

        saveUndo();
        for (int r = end; r >= start; r--) lines.remove(r);
        for (int i = wrapped.size() - 1; i >= 0; i--) lines.add(start, new StringBuilder(wrapped.get(i)));
        cursorRow = start;
        cursorCol = Math.min(indentLen, lines.get(start).length());
        clampCursor();
    }

    /** Prompt for a non-negative integer using the same 3-row layout as goToLineNumber().
     *  Returns null on Escape, empty input, or a non-numeric entry. */
    private Integer promptInt(Screen screen, String label) throws IOException {
        int cols      = screen.getTerminalSize().getColumns();
        int promptRow = (splitMode && activePane == 1) ? 13 : 0;
        int inputRow  = promptRow + 1;
        int ruleRow   = promptRow + 2;

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, promptRow, String.format("%-" + cols + "s", label));
        tg.putString(0, inputRow,  String.format("%-" + cols + "s", ""));
        tg.putString(0, ruleRow,   "─".repeat(cols));
        screen.setCursorPosition(new TerminalPosition(0, inputRow));
        screen.refresh();

        StringBuilder num = new StringBuilder();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() == KeyType.Escape) return null;
            if (k.getKeyType() == KeyType.Enter) break;
            if (k.getKeyType() == KeyType.Backspace) {
                if (num.length() > 0) num.deleteCharAt(num.length() - 1);
            } else if (k.getKeyType() == KeyType.Character && !k.isCtrlDown() && !k.isAltDown()
                    && Character.isDigit(k.getCharacter())) {
                num.append(k.getCharacter());
            }
            tg.putString(0, inputRow, String.format("%-" + cols + "s", num.toString()));
            screen.setCursorPosition(new TerminalPosition(num.length(), inputRow));
            screen.refresh();
        }
        if (num.length() == 0) return null;
        try { return Integer.parseInt(num.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** F5 T's mode sub-prompt: L = literal TAB byte, S = insert spaces, M = move only. */
    private TabMode promptTabMode(Screen screen) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        int row  = (splitMode && activePane == 1) ? 13 : 0;
        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, row, String.format("%-" + cols + "s",
                "Tab mode:  L=literal TAB byte  S=insert spaces  M=move only"));
        screen.setCursorPosition(null);
        screen.refresh();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() == KeyType.Escape) return null;
            if (k.getKeyType() != KeyType.Character) continue;
            switch (Character.toLowerCase(k.getCharacter())) {
                case 'l': return TabMode.LITERAL;
                case 's': return TabMode.SPACES;
                case 'm': return TabMode.MOVE;
                default:  // ignore other keys, keep waiting
            }
        }
    }

    /** F5 D's mode sub-prompt: A/B/C select the CLI's /DA, /DB, /DC display themes. */
    private DisplayMode promptDisplayMode(Screen screen) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        int row  = (splitMode && activePane == 1) ? 13 : 0;
        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, row, String.format("%-" + cols + "s",
                "Display:  A=/DA white  B=/DB green  C=/DC amber"));
        screen.setCursorPosition(null);
        screen.refresh();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() == KeyType.Escape) return null;
            if (k.getKeyType() != KeyType.Character) continue;
            switch (Character.toLowerCase(k.getCharacter())) {
                case 'a': return DisplayMode.DA;
                case 'b': return DisplayMode.DB;
                case 'c': return DisplayMode.DC;
                default:  // ignore other keys, keep waiting
            }
        }
    }

    /** F5 C's mode sub-prompt: R/F/U/V select one of Lanterna's 4 cursor styles. Note (shown to
     *  the user by the caller): this can't be applied live — see the `cursorStyle` field. */
    private TerminalEmulatorDeviceConfiguration.CursorStyle promptCursorStyle(Screen screen) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        int row  = (splitMode && activePane == 1) ? 13 : 0;
        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, row, String.format("%-" + cols + "s",
                "Cursor style:  R=reversed  F=fixed background  U=under bar  V=vertical bar"));
        screen.setCursorPosition(null);
        screen.refresh();
        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() == KeyType.Escape) return null;
            if (k.getKeyType() != KeyType.Character) continue;
            switch (Character.toLowerCase(k.getCharacter())) {
                case 'r': return TerminalEmulatorDeviceConfiguration.CursorStyle.REVERSED;
                case 'f': return TerminalEmulatorDeviceConfiguration.CursorStyle.FIXED_BACKGROUND;
                case 'u': return TerminalEmulatorDeviceConfiguration.CursorStyle.UNDER_BAR;
                case 'v': return TerminalEmulatorDeviceConfiguration.CursorStyle.VERTICAL_BAR;
                default:  // ignore other keys, keep waiting
            }
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
            case 'a' -> {
                TextInput in = promptTextLine(screen, "Insert file:");
                String path = in.text().trim();
                if (path.isEmpty()) return;
                try {
                    List<String> fileLines = Files.readAllLines(Paths.get(path));
                    if (!fileLines.isEmpty()) {
                        saveUndo();
                        insertBlockAt(cursorRow, cursorCol, fileLines);
                    }
                } catch (IOException e) {
                    showMessage(screen, "CAN'T READ FILE: " + e.getMessage());
                }
            }
            // L "load more" only has meaning for a partially-loaded document. This editor
            // always reads the whole file up front (see the constructor and handleNewFile), so
            // there is never an unread remainder — this honestly reflects that instead of
            // faking a chunked load. A future incremental FileSource (see the class-level
            // compliance comment) would replace this with a real "load next chunk".
            case 'l' -> showMessage(screen, "ENTIRE FILE ALREADY LOADED");
            case 'w' -> {
                List<String> prefix = new ArrayList<>();
                for (int r = 0; r < cursorRow; r++) prefix.add(lines.get(r).toString());
                String lastLine = lines.get(cursorRow).toString();
                prefix.add(lastLine.substring(0, Math.min(cursorCol, lastLine.length())));
                String target = (outputPath != null && !outputPath.isEmpty()) ? outputPath : activeFileName;
                if (target == null || target.isEmpty()) {
                    showMessage(screen, "NO OUTPUT FILE SET");
                } else {
                    try {
                        Files.write(Paths.get(target), prefix);
                        showMessage(screen, "WROTE THROUGH CURSOR TO " + target);
                    } catch (IOException e) {
                        showMessage(screen, "WRITE FAILED: " + e.getMessage());
                    }
                }
            }
            case 'c' -> {
                if (outputPath == null || outputPath.isEmpty()) {
                    showMessage(screen, "NO OUTPUT FILE OPEN");
                } else if (confirmYesNo(screen, "Close output file " + outputPath + "? (Y or N)")) {
                    outputPath = null;
                }
            }
            default  -> { /* cancel */ }
        }
    }

    /** Generic Y/N confirmation on the prompt row (see confirmQuit() for the quit-specific
     *  variant this mirrors). Returns true only if the user pressed Y. */
    private boolean confirmYesNo(Screen screen, String message) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        int promptRow = (splitMode && activePane == 1) ? 13 : 0;
        int ruleRow = promptRow + 1;

        TextGraphics tg = screen.newTextGraphics();
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, promptRow, String.format("%-" + cols + "s", message));
        tg.putString(0, ruleRow,   "─".repeat(cols));
        screen.setCursorPosition(null);
        screen.refresh();

        while (true) {
            KeyStroke k = screen.readInput();
            if (k.getKeyType() != KeyType.Character) continue;
            char ch = Character.toLowerCase(k.getCharacter());
            if (ch == 'y') return true;
            if (ch == 'n') return false;
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
        undoStack.clear();
        typingRunActive = false;
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
        undoStack    = undoStack2;   undoStack2  = new ArrayDeque<>();
        typingRunActive = false;
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
        Deque<UndoEntry> tmpU = undoStack; undoStack = undoStack2; undoStack2 = tmpU;
        typingRunActive = false; // don't let a coalesced run span across a pane switch
    }

    /** Draw the split-mode filename-entry UI and load the second file. */
    private void enterSplitMode(Screen screen, MultiWindowTextGUI gui) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        int rows = size.getRows();
        int cols = size.getColumns();
        TextGraphics tg = screen.newTextGraphics();

        // Redraw pane 1 content in rows 0-11
        tg.setForegroundColor(displayMode.foreground);
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
        undoStack2 = new ArrayDeque<>();
        splitMode = true;
        activePane = 1; // focus moves to the newly opened pane
    }

    // ------------------------------------------------------------------ delete helpers

    /** Push the CURRENT buffer state as an undo checkpoint — call before any mutation (typing
     *  coalesces via insertChar()'s `coalesce` flag instead of calling this directly). */
    private void saveUndo() {
        List<String> snapshot = new ArrayList<>(lines.size());
        for (StringBuilder sb : lines) snapshot.add(sb.toString());
        undoStack.addLast(new UndoEntry(snapshot, cursorRow, cursorCol));
        if (undoStack.size() > UNDO_STACK_LIMIT) undoStack.removeFirst();
    }

    /** Ctrl-U: pop and restore the most recent undo checkpoint. One step per press — a run of
     *  consecutive typed characters was pushed as a single checkpoint by insertChar(), so
     *  undoing it removes the whole run at once; every other mutation is its own checkpoint. */
    private void undo() {
        if (undoStack.isEmpty()) return;
        UndoEntry entry = undoStack.removeLast();
        lines.clear();
        for (String s : entry.lines()) lines.add(new StringBuilder(s));
        cursorRow = Math.min(entry.cursorRow(), lines.size() - 1);
        cursorCol = Math.min(entry.cursorCol(), lines.get(cursorRow).length());
        typingRunActive = false;
    }

    /** Ctrl+W – delete one word to the left */
    private void deleteWordLeft() {
        if (cursorCol == 0) return;
        saveUndo();
        String s = lines.get(cursorRow).toString();
        int c = cursorCol - 1;
        while (c > 0 && !Character.isLetterOrDigit(s.charAt(c))) c--;
        while (c > 0 && Character.isLetterOrDigit(s.charAt(c - 1))) c--;
        lines.get(cursorRow).delete(c, cursorCol);
        cursorCol = c;
    }

    /** Alt+W – delete one word to the right */
    private void deleteWordRight() {
        String s = lines.get(cursorRow).toString();
        int len = s.length();
        if (cursorCol >= len) return;
        saveUndo();
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
        saveUndo();
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
        saveUndo();
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
