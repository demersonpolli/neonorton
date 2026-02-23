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

    // Status bar
    private StatusBar statusBar;

    public EditorScreen(String fileName) {
        this.fileName = fileName;
        lines.add(new StringBuilder());
    }

    @Override
    public void show(MultiWindowTextGUI gui) {
        Screen screen = gui.getScreen();
        try {
            int rows = screen.getTerminalSize().getRows();
            statusBarCols = screen.getTerminalSize().getColumns();

            String displayName = (fileName.isEmpty() ? "[No Name]" : fileName).toUpperCase();
            int centerCol = Math.max(12, (statusBarCols - displayName.length()) / 2);

            // Status bar at last row, fixed columns:
            //  col  0        : Line=N
            //  col 11        : Col=M
            //  col center    : FILENAME (uppercase, centered)
            //  col 58        : Insert / Replace
            //  col 67        : WW=On / WW=Off
            statusBar = new StatusBar(
                rows - 1,
                new int[]   {  0,         11,        centerCol,    58,         67       },
                new String[]{ "Line=1", "Col=1", displayName, "Insert", "WW=Off" }
            );
            redraw(screen);

            boolean waitingForQ = false;

            while (true) {
                KeyStroke key = screen.readInput();
                KeyType type = key.getKeyType();

                if (type == KeyType.EOF) break;

                // F3+Q sequence — quit
                if (waitingForQ) {
                    waitingForQ = false;
                    if (type == KeyType.Character && key.getCharacter() == 'q') {
                        break;
                    }
                    // Not Q — treat F3 as nothing, process this key normally
                }

                if (type == KeyType.F3) {
                    waitingForQ = true;
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
}
