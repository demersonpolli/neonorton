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

    // Lines of text in the document
    private final List<StringBuilder> lines = new ArrayList<>();

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

    public EditorScreen(String fileName) {
        this.fileName = fileName;
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

            String displayName = (fileName.isEmpty() ? "[No Name]" : fileName).toUpperCase();
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
                new int[]   {  0,          9,               26,      33,      40,                59,      65,       73,   76,   79 },
                new String[]{ "F3 FILE:", "Exit-with-save", "Quit", "Save", "eXchange-windows", "New", "Append", "L", "W", "C" }
            );
            redraw(screen);

            while (true) {
                KeyStroke key = screen.readInput();
                KeyType type = key.getKeyType();

                if (type == KeyType.EOF) break;

                // F3 — enter file operation mode
                if (type == KeyType.F3) {
                    handleFileOperation(screen);
                    if (shouldQuit) break;
                    updateStatusBar();
                    redraw(screen);
                    continue;
                }

                // F1 — help
                if (type == KeyType.F1) {
                    new HelpScreen().show(gui);
                    updateStatusBar();
                    redraw(screen);
                    continue;
                }

                handleKey(key, screen);
                updateStatusBar();
                redraw(screen);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleKey(KeyStroke key, Screen screen) {
        KeyType type = key.getKeyType();
        TerminalSize size = screen.getTerminalSize();
        int textRows = size.getRows() - 1; // last row is status bar
        int cols     = size.getColumns();

        switch (type) {
            case Character -> {
                StringBuilder line = lines.get(cursorRow);
                if (insertMode || cursorCol >= line.length()) {
                    line.insert(cursorCol, key.getCharacter());
                } else {
                    line.setCharAt(cursorCol, key.getCharacter());
                }
                cursorCol++;
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
                StringBuilder line = lines.get(cursorRow);
                if (cursorCol < line.length()) {
                    line.deleteCharAt(cursorCol);
                } else if (cursorRow < lines.size() - 1) {
                    line.append(lines.remove(cursorRow + 1));
                }
            }
            case ArrowLeft -> {
                if (cursorCol > 0) {
                    cursorCol--;
                } else if (cursorRow > 0) {
                    cursorRow--;
                    cursorCol = lines.get(cursorRow).length();
                }
            }
            case ArrowRight -> {
                int len = lines.get(cursorRow).length();
                if (cursorCol < len) {
                    cursorCol++;
                } else if (cursorRow < lines.size() - 1) {
                    cursorRow++;
                    cursorCol = 0;
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
            case Home -> cursorCol = 0;
            case End  -> cursorCol = lines.get(cursorRow).length();
            default   -> {}
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
        int rows    = size.getRows();
        int cols    = size.getColumns();
        int textRows = rows - 1;

        TextGraphics tg = screen.newTextGraphics();

        // --- Text area ---
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.setBackgroundColor(TextColor.ANSI.BLACK);

        for (int r = 0; r < textRows; r++) {
            int docRow = r + scrollRow;
            String text = (docRow < lines.size()) ? lines.get(docRow).toString() : "";
            // Pad to full width to clear previous content
            String padded = String.format("%-" + cols + "s", text.length() > cols ? text.substring(0, cols) : text);
            tg.putString(0, r, padded);
        }

        // --- Status bar ---
        updateStatusBar();
        statusBar.render(screen);

        // --- Position cursor ---
        int screenRow = cursorRow - scrollRow;
        int screenCol = Math.min(cursorCol, cols - 1);
        screen.setCursorPosition(new TerminalPosition(screenCol, screenRow));
        screen.refresh();
    }

    private void updateStatusBar() {
        statusBar.setLabel(0, String.format("Line=%-5d", cursorRow + 1));
        statusBar.setLabel(1, String.format("Col=%-5d",  cursorCol + 1));
        // label 2 is the filename — static, no update needed
        statusBar.setLabel(3, insertMode ? "Insert " : "Replace");
        statusBar.setLabel(4, wordWrap   ? "WW=On " : "WW=Off");
    }

    private void handleFileOperation(Screen screen) throws IOException {        // Show the file-operation status bar
        fileOpBar.render(screen);
        screen.refresh();

        KeyStroke key = screen.readInput();
        if (key.getKeyType() != KeyType.Character) return;

        switch (Character.toLowerCase(key.getCharacter())) {
            case 'q' -> shouldQuit = true;              // Quit without save
            case 'e' -> { saveFile(); shouldQuit = true; } // Exit with save
            case 's' -> saveFile();                     // Save
            case 'x' -> { /* TODO: eXchange windows */ }
            case 'n' -> { /* TODO: New file */ }
            case 'a' -> { /* TODO: Append file */ }
            case 'l' -> { /* TODO: L */ }
            case 'w' -> { /* TODO: W */ }
            case 'c' -> { /* TODO: C */ }
            default  -> { /* cancel */ }
        }
    }

    private void saveFile() {
        if (fileName.isEmpty()) return;
        try {
            Path path = Paths.get(fileName);
            List<String> content = new ArrayList<>();
            for (StringBuilder line : lines) content.add(line.toString());
            Files.write(path, content);
        } catch (IOException e) {
            // TODO: surface error to user
        }
    }
}
