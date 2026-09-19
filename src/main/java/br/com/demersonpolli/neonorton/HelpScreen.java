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
import java.util.ArrayList;
import java.util.List;

/** F1: paged help covering the full current command set. PgUp/PgDn or Left/Right page through
 *  it (clamped at the first/last page, no wraparound); ESC closes from any page. */
public class HelpScreen implements AppScreen {

    private static final String[][] PAGES = {
        { // Page 1: Global & Navigation
            "Global",
            "  F1               Show this help (paged)",
            "  F2               File status screen",
            "  F9               Open a system shell (Y/N confirm)",
            "  Ins              Insert mode (F5 K: toggle vs. always-insert)",
            "  Ctrl-P           Insert a raw byte (hex 00-FF)",
            "  ESC              Close this help / cancel a prompt",
            "",
            "Navigation",
            "  Arrows           Move cursor",
            "  Ctrl-Left/Right  Move by word",
            "  Home / End       Start / end of line",
            "  Ctrl-Home/End    Start / end of file",
            "  PgUp / PgDn      Scroll one screen",
            "  Tab              Switch panes (split) or insert per F5 T's tab mode",
        },
        { // Page 2: Editing & Undo
            "Editing",
            "  Backspace / Delete   Delete character left / right",
            "  Ctrl-W / Alt-W       Delete word left / right",
            "  Ctrl-L / Alt-L       Delete to line begin / end",
            "  Alt-K                Delete entire current line",
            "  Ctrl-V / Alt-V       Toggle case to line begin / end",
            "",
            "Undo",
            "  Ctrl-U     Undo (multi-level; a typed run undoes as one step,",
            "             every other command is its own step)",
        },
        { // Page 3: F3 File operations
            "F3 File operations",
            "  E    Exit with save (or close pane in split mode)",
            "  S    Save",
            "  Q    Quit, with confirmation",
            "  N    New file in this pane",
            "  X    Split into two panes / switch active pane",
            "  A    Append another file's contents at the cursor",
            "  L    Load more (this editor always loads a file in",
            "       full, so this just confirms nothing is left)",
            "  W    Write from start of buffer through the cursor",
            "       to the output file",
            "  C    Close the output file opened by W",
        },
        { // Page 4: F4 Block operations
            "F4 Block operations",
            "  S    Set marker (press twice: begin, then end)",
            "  R    Remove markers",
            "  D    Delete the marked block",
            "  C    Copy the marked block to the cursor",
            "  M    Move the marked block to the cursor",
            "  W    Copy the block marked in the OTHER pane",
            "       (split mode only)",
            "  L    Mark the current line",
            "  E    Mark from cursor to end of line",
            "  F    Jump the cursor between the two markers",
        },
        { // Page 5: F5 Format operations
            "F5 Format operations",
            "  F    Format (reflow) the current paragraph",
            "  L    Set format / word-wrap line width",
            "  W    Toggle word-wrap while typing",
            "  T    Set tab width and mode (literal tab byte /",
            "       insert spaces / move only)",
            "  C    Set cursor style (takes effect next launch)",
            "  D    Set display theme: /DA white, /DB green, /DC amber",
            "  I    Toggle auto-indent (Enter copies leading whitespace)",
            "  S    Save current settings so they persist next launch",
            "  K    Toggle Ins-key behavior (always-insert vs. toggle)",
        },
        { // Page 6: F6 Misc / F7 Printer
            "F6 Miscellaneous",
            "  G       Go to line number",
            "  M       Jump to the matching bracket",
            "  T       Compare panes from their cursors (split mode)",
            "  Ins     Force overwrite mode",
            "  C       Toggle condensed display (next launch)",
            "",
            "F7 Printer (writes a PDF, not a physical printer)",
            "  P       Print the whole buffer to a PDF",
            "  B       Print the marked block to a PDF",
            "  S       Set lines per printed page",
            "  M       Set the print left margin",
            "  E       Eject page (no-op: one PDF per P/B press)",
        },
        { // Page 7: Search & Replace
            "Search & Replace",
            "  Alt-F / Ctrl-F   Find forward / reverse",
            "  Alt-C / Ctrl-C   Continue the last forward / reverse find",
            "",
            "  While typing a search term:",
            "    ESC            Finish and search case-insensitively",
            "    Ctrl-Return    Insert a literal newline into the term",
            "",
            "  Press the SAME find key again (with a term already",
            "  active) to enter a replacement and start replacing:",
            "    Y    Replace this match, continue",
            "    N    Skip this match, continue",
            "    *    Replace this and all remaining matches",
            "    Space  Stop",
        },
    };

    private int page = 0;

    @Override
    public void show(MultiWindowTextGUI gui) {
        Screen screen = gui.getScreen();
        try {
            drawHelp(screen);
            while (true) {
                KeyStroke key = screen.readInput();
                KeyType t = key.getKeyType();
                if (t == KeyType.Escape) break;
                if ((t == KeyType.PageDown || t == KeyType.ArrowRight) && page < PAGES.length - 1) {
                    page++;
                    drawHelp(screen);
                } else if ((t == KeyType.PageUp || t == KeyType.ArrowLeft) && page > 0) {
                    page--;
                    drawHelp(screen);
                }
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

        // Keep the box a stable size across every page: width from the longest line anywhere,
        // height from the longest page (shorter pages are padded with blank trailing lines).
        int maxLen = ("NeoNorton Editor - Help  (page " + PAGES.length + "/" + PAGES.length + ")").length();
        int maxPageLines = 0;
        for (String[] p : PAGES) {
            maxPageLines = Math.max(maxPageLines, p.length);
            for (String l : p) maxLen = Math.max(maxLen, l.length());
        }
        String footer = "PgUp/PgDn or ←/→ to page, ESC to close";
        maxLen = Math.max(maxLen, footer.length());

        List<String> content = new ArrayList<>();
        content.add(String.format("NeoNorton Editor - Help  (page %d/%d)", page + 1, PAGES.length));
        content.add("");
        for (String l : PAGES[page]) content.add(l);
        while (content.size() < maxPageLines + 2) content.add("");
        content.add("");
        content.add(footer);

        int innerWidth  = maxLen + 4;
        int boxWidth    = innerWidth + 2;
        int boxHeight   = content.size() + 2;
        int boxStartCol = Math.max(0, (cols - boxWidth) / 2);
        int boxStartRow = Math.max(0, (rows - boxHeight) / 2);
        int textStartRow = boxStartRow + 1;

        // Top border ╔═══╗
        StringBuilder top = new StringBuilder();
        top.append('╔');
        for (int i = 0; i < innerWidth; i++) top.append('═');
        top.append('╗');
        tg.putString(boxStartCol, boxStartRow, top.toString());

        // Content rows
        for (int i = 0; i < content.size(); i++) {
            String text = content.get(i);
            int rightPad = Math.max(0, innerWidth - 2 - text.length());
            StringBuilder row = new StringBuilder();
            row.append('║').append(" ").append(text);
            for (int s = 0; s < rightPad; s++) row.append(' ');
            row.append(' ').append('║');
            boolean bold = (i == 0) || (i == content.size() - 1);
            if (bold) tg.enableModifiers(SGR.BOLD);
            tg.putString(boxStartCol, textStartRow + i, row.toString());
            if (bold) tg.disableModifiers(SGR.BOLD);
        }

        // Bottom border ╚═══╝
        StringBuilder bot = new StringBuilder();
        bot.append('╚');
        for (int i = 0; i < innerWidth; i++) bot.append('═');
        bot.append('╝');
        tg.putString(boxStartCol, textStartRow + content.size(), bot.toString());

        screen.setCursorPosition(null);
        screen.refresh();
    }
}
