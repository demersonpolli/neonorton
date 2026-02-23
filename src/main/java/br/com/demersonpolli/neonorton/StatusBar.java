package br.com.demersonpolli.neonorton;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;

public class StatusBar {

    private final int row;
    private final int[] columns;
    private final String[] labels;

    /**
     * @param row     Screen row where the status bar is drawn (0-based).
     * @param columns Column positions for each label (0-based).
     * @param labels  Text to display at the corresponding column.
     *                If arrays differ in size, the minimum length is used.
     */
    public StatusBar(int row, int[] columns, String[] labels) {
        this.row     = row;
        this.columns = columns;
        this.labels  = labels;
    }

    /** Update a label at runtime (e.g. show current line number). */
    public void setLabel(int index, String text) {
        if (index >= 0 && index < labels.length) {
            labels[index] = text;
        }
    }

    /** Render the status bar onto the screen. Does NOT call screen.refresh(). */
    public void render(Screen screen) throws IOException {
        int cols = screen.getTerminalSize().getColumns();
        TextGraphics tg = screen.newTextGraphics();

        // Reversed colors: black text on white background
        tg.setForegroundColor(TextColor.ANSI.BLACK);
        tg.setBackgroundColor(TextColor.ANSI.WHITE);

        // Fill the entire row first so it looks like a solid bar
        tg.putString(0, row, " ".repeat(cols));

        // Place each label at its column, clip if it would overflow
        int count = Math.min(columns.length, labels.length);
        for (int i = 0; i < count; i++) {
            int col = columns[i];
            String text = labels[i];
            if (col >= cols) continue;
            if (col + text.length() > cols) {
                text = text.substring(0, cols - col);
            }
            tg.putString(col, row, text);
        }
    }
}
