package br.com.demersonpolli.neonorton;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * F9 "DOS command prompt": opens a real, separate, interactive OS shell window rather than
 * running one captured command — a deliberate deviation from the original NE's embedded
 * command line, at the user's request. This app runs inside a Lanterna SwingTerminalFrame
 * (a GUI window, not a real OS console), so there's no existing terminal to hand off to a
 * child process; each platform gets its own window instead:
 *   - Windows: cmd.exe with inheritIO(). Since this app has no console of its own (launched via
 *     javaw), Windows is documented to auto-allocate a new console window for a console-
 *     subsystem child in that situation.
 *   - macOS: Terminal.app via `osascript "tell application \"Terminal\" to do script ..."`,
 *     the standard idiom for opening a new terminal window with a command from a non-terminal
 *     app.
 *   - Linux: the first available terminal emulator from a short list (there is no single
 *     standard the way Windows/macOS have one).
 * IMPORTANT — verification status: this was developed and tested inside a sandboxed agent tool
 * environment where even a completely bare `cmd.exe /K`, launched directly with no Java
 * involved at all, never produces a real console window (`tasklist /v` shows it stuck at
 * Status "Unknown" with no window, confirmed visually via a screenshot too) — while an AWT/Swing
 * GUI window launched the same way works fine. That isolates the problem to this sandbox's
 * console-window allocation specifically, not to this code, but it also means the Windows path
 * could only be exercised up to "the process starts and inheritIO() is wired up correctly," not
 * "a user can actually see and type into the window" — that part relies on Windows' documented
 * auto-console-allocation behavior, unconfirmed on a real desktop session. macOS/Linux are
 * implemented per their standard, well-documented conventions but are entirely untested (no
 * access to that hardware). All three should be treated as reviewed-but-not-empirically-proven
 * until exercised on a real desktop.
 * Blocking-until-close is expected to work for Windows (waitFor() on cmd.exe itself), but is a
 * known weak point on the other platforms: macOS's `osascript do script` and some Linux
 * terminal emulators (a client that hands off to an already-running terminal-server process)
 * are documented to return once the window OPENS rather than when it closes, so the editor may
 * regain control before the shell window is actually closed there.
 */
public class ShellLauncher {

    /** Thrown when no usable terminal/shell could be found or started. */
    public static class LaunchException extends Exception {
        public LaunchException(String message, Throwable cause) { super(message, cause); }
        public LaunchException(String message) { super(message); }
    }

    /** Launch the platform's shell/command-prompt in `workDir`. */
    public static void openInteractiveShell(File workDir) throws LaunchException {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                runAndWait(workDir, "cmd.exe", "/K", "cd /d \"" + workDir.getAbsolutePath() + "\"");
            } else if (os.contains("mac") || os.contains("darwin")) {
                openMacTerminal(workDir);
            } else {
                openLinuxTerminal(workDir);
            }
        } catch (IOException e) {
            throw new LaunchException("Could not start a shell: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchException("Interrupted while waiting for the shell to close", e);
        }
    }

    private static void openMacTerminal(File workDir) throws IOException, InterruptedException {
        String shell = System.getenv().getOrDefault("SHELL", "/bin/zsh");
        String script = "tell application \"Terminal\" to do script "
                + "\"cd " + appleScriptQuote(workDir.getAbsolutePath()) + " && exec " + shell + "\"";
        runAndWait(workDir, "osascript", "-e", script);
    }

    private static void openLinuxTerminal(File workDir) throws IOException, InterruptedException {
        String shell = System.getenv().getOrDefault("SHELL", "/bin/sh");
        List<String[]> candidates = List.of(
            new String[]{"x-terminal-emulator", "-e", shell},
            new String[]{"gnome-terminal", "--", shell},
            new String[]{"konsole", "-e", shell},
            new String[]{"xfce4-terminal", "-e", shell},
            new String[]{"xterm", "-e", shell}
        );
        for (String[] cmd : candidates) {
            if (isOnPath(cmd[0])) {
                runAndWait(workDir, cmd);
                return;
            }
        }
        throw new IOException("No terminal emulator found (tried x-terminal-emulator, "
                + "gnome-terminal, konsole, xfce4-terminal, xterm)");
    }

    private static boolean isOnPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, program).canExecute()) return true;
        }
        return false;
    }

    private static void runAndWait(File workDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        // Without inheritIO(), Java pipes the child's stdin/stdout/stderr back to this process
        // instead of leaving them unset — and since nobody drains those pipes, a console app
        // (cmd.exe) blocks on write-buffer backpressure almost immediately instead of writing
        // to its own console screen buffer, leaving a hung, invisible window. Confirmed this
        // empirically: without inheritIO(), the spawned cmd.exe showed Status "Unknown" and no
        // window at all in `tasklist /v`.
        pb.inheritIO();
        Process p = pb.start();
        p.waitFor();
    }

    /** Minimal escaping for a path embedded in a double-quoted AppleScript string literal. */
    private static String appleScriptQuote(String path) {
        return "\\\"" + path.replace("\\", "\\\\").replace("\"", "\\\\\\\"") + "\\\"";
    }
}
