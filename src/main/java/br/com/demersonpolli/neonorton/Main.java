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

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFontConfiguration;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorAutoCloseTrigger;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorColorConfiguration;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorDeviceConfiguration;

import java.io.IOException;

public class Main {

    /**
     * Holds command-line arguments for the Norton Editor.
     * Supports both classic Norton Editor syntax (+LINE, /DA) and modern long-form options.
     */
    static class CommandLineArgs {
        int startLine = 0;
        String inputPath = null;
        // outputPath now reaches EditorScreen via setOutputPath() below, and F3 W "write
        // through cursor" is fully implemented (writes lines[0..cursor) to it).
        String outputPath = null;
        // displayMode now reaches EditorScreen via setDisplayMode() below, applied after
        // EditorConfig's saved value so an explicit CLI flag always wins over a saved default.
        String displayMode = null; // "da", "db", "dc"
        // safeMode is parsed but still unused — and, unlike outputPath/displayMode above, there
        // isn't really anything left for it to gate. The spec's "Portable-safe" behaviors it was
        // meant to opt into are now unconditional defaults instead: saveFile() always does an
        // atomic save, and F9 always Y/N-confirms before opening a shell. Its third behavior
        // (Unicode-safe rendering vs. caret-notation control bytes) doesn't apply either — this
        // editor targets plain text/source code, not arbitrary binary files, by design (see
        // EditorScreen's class-level compliance note), so there's no byte/caret-notation mode to
        // toggle into in the first place. Kept accepting the flag for CLI compatibility; it's
        // effectively a no-op now rather than a pending TODO.
        boolean safeMode = false;
        // encoding is parsed but unused, for the same reason as safeMode above: the document
        // model is deliberately plain-text/UTF-8 (List<StringBuilder> of Java Strings), not a
        // byte-oriented model, so there's no "bytes" mode to switch into. Kept accepting the
        // flag for CLI compatibility.
        String encoding = "utf8"; // "bytes" or "utf8"
    }

    /**
     * Parse an integer argument value.
     * 
     * @param value the string value to parse
     * @return the parsed integer, or 0 if parsing fails
     */
    private static int parseIntArg(String value) {
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            System.err.println(String.format("Invalid line number: %s", value));
            return 0;
        }
    }
    
    /**
     * Parse command-line arguments according to Norton Editor spec:
     *   ne [+LINE] [INPUT [OUTPUT]] [/DA|/DB|/DC]
     * Plus long-form aliases:
     *   ne [--line LINE] [--input INPUT] [--output OUTPUT]
     *   ne [--display da|db|dc] [--safe] [--encoding bytes|utf8]
     * 
     * @param args the command-line arguments to parse
     * @return a CommandLineArgs object containing the parsed arguments
     */
    private static CommandLineArgs parseCommandLineArgs(String[] args) {
        CommandLineArgs result = new CommandLineArgs();
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            // Handle +LINE format
            if (arg.startsWith("+")) {
                result.startLine = parseIntArg(arg.substring(1));
            }
            // Handle /DA, /DB, /DC display modes
            else if (arg.matches("(?i)/D[ABC]")) {
                result.displayMode = String.format("d%s", arg.substring(2).toLowerCase());
            }
            // Handle long-form options
            else if (arg.equals("--line") && i + 1 < args.length) {
                result.startLine = parseIntArg(args[++i]);
            } else if (arg.equals("--input") && i + 1 < args.length) {
                result.inputPath = args[++i];
            } else if (arg.equals("--output") && i + 1 < args.length) {
                result.outputPath = args[++i];
            } else if (arg.equals("--display") && i + 1 < args.length) {
                result.displayMode = args[++i].toLowerCase();
            } else if (arg.equals("--safe")) {
                result.safeMode = true;
            } else if (arg.equals("--encoding") && i + 1 < args.length) {
                result.encoding = args[++i].toLowerCase();
            }
            // Handle positional arguments (INPUT [OUTPUT])
            else if (!arg.startsWith("-")) {
                if (result.inputPath == null) {
                    result.inputPath = arg;
                }
                else if (result.outputPath == null) {
                    result.outputPath = arg;
                }
            }
        }
        
        return result;
    }

    
    public static void main(String[] args) {
        try {
            // F5 S/C's saved config: cursor style must be known BEFORE the SwingTerminalFrame is
            // constructed, since Lanterna's TerminalEmulatorDeviceConfiguration is immutable and
            // has no live-update API (see EditorScreen's `cursorStyle` field comment).
            EditorConfig savedConfig = EditorConfig.load();
            TerminalEmulatorDeviceConfiguration.CursorStyle cursorStyle;
            try {
                cursorStyle = TerminalEmulatorDeviceConfiguration.CursorStyle.valueOf(savedConfig.cursorStyle);
            } catch (IllegalArgumentException e) {
                cursorStyle = TerminalEmulatorDeviceConfiguration.CursorStyle.REVERSED;
            }

            SwingTerminalFrame terminal = new SwingTerminalFrame(
                    "NeoNorton",
                    TerminalEmulatorDeviceConfiguration.getDefault().withCursorStyle(cursorStyle),
                    SwingTerminalFontConfiguration.getDefault(),
                    TerminalEmulatorColorConfiguration.getDefault(),
                    TerminalEmulatorAutoCloseTrigger.CloseOnExitPrivateMode);
            terminal.setVisible(true);

            // Parse command-line arguments
            CommandLineArgs cliArgs = parseCommandLineArgs(args);

            Screen screen = new TerminalScreen(terminal);
            screen.startScreen();

            MultiWindowTextGUI gui = new MultiWindowTextGUI(
                    screen,
                    new DefaultWindowManager(),
                    new EmptySpace(TextColor.ANSI.BLUE));

            String fileName = cliArgs.inputPath;

            // If no input file specified via CLI, show splash screen to get filename
            if (fileName == null || fileName.isEmpty()) {
                SplashScreen splash = new SplashScreen();
                splash.show(gui);
                fileName = splash.getFileName();
            }

            // Create editor screen with parsed arguments
            EditorScreen editor = new EditorScreen(fileName);
            editor.applyConfig(savedConfig);
            if (cliArgs.startLine > 0) {
                editor.setStartLine(cliArgs.startLine);
            }
            editor.setOutputPath(cliArgs.outputPath);
            if (cliArgs.displayMode != null) {
                editor.setDisplayMode(cliArgs.displayMode); // explicit CLI flag overrides saved config
            }
            editor.show(gui);

            screen.stopScreen();
            terminal.dispose();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}