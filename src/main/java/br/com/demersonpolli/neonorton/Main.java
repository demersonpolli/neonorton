package br.com.demersonpolli.neonorton;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorAutoCloseTrigger;

import java.io.IOException;

public class Main {
    // TODO(spec: Startup/CLI): args is accepted but never read — there is no command-line
    // parsing at all, so the app always falls back to SplashScreen's interactive filename
    // prompt. The spec's compatible syntax is:
    //   ne [+LINE] [INPUT [OUTPUT]] [/DA|/DB|/DC]
    // plus these portable long-form aliases:
    //   ne [--line LINE] [--input INPUT] [--output OUTPUT]
    //   ne [--display da|db|dc] [--safe] [--encoding bytes|utf8]
    // Needed: parse args into (startLine, inputPath, outputPath, displayMode, safeMode,
    // encoding) before building the GUI; only fall through to SplashScreen when no input
    // path was given. `+LINE` should position the initial cursor (EditorScreen has no way
    // to accept a starting line today). Quoted paths with spaces must be accepted — that's
    // free from the JVM's own argv splitting, just don't re-split args[] on spaces.
    // Also missing: F9 DOS/shell command processor — there is no key handling for F9
    // anywhere in EditorScreen's main loop, and no `shell.c`-equivalent child-process
    // integration (spec wants an explicit-confirmation shell command using COMSPEC on
    // Windows / $SHELL -c on POSIX, not the original's raw wildcard-delete aliases).
    public static void main(String[] args) {
        try {
            SwingTerminalFrame terminal = new SwingTerminalFrame(
                    "NeoNorton",
                    TerminalEmulatorAutoCloseTrigger.CloseOnExitPrivateMode);
            terminal.setVisible(true);

            Screen screen = new TerminalScreen(terminal);
            screen.startScreen();

            MultiWindowTextGUI gui = new MultiWindowTextGUI(
                    screen,
                    new DefaultWindowManager(),
                    new EmptySpace(TextColor.ANSI.BLUE));

            SplashScreen splash = new SplashScreen();
            splash.show(gui);
            String fileName = splash.getFileName();

            new EditorScreen(fileName).show(gui);

            screen.stopScreen();
            terminal.dispose();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}