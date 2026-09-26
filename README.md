# NeoNorton

**A retro, keyboard-driven text editor for the terminal — inspired by the classic Norton Editor of the 1980s.**

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](#requirements)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey.svg)](#requirements)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](#contributing)
![GitHub last commit](https://img.shields.io/github/last-commit/demersonpolli/neonorton)
![GitHub repo size](https://img.shields.io/github/repo-size/demersonpolli/neonorton?cachebust=1)

> NeoNorton brings back the full-screen, function-key-driven editing experience of the original
> Norton Editor — no mouse, no menus, just a fast, muscle-memory-friendly workflow for plain text
> and source code. It's a nostalgic tribute and learning project, not a production IDE.

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Getting started](#getting-started)
- [Command-line arguments](#command-line-arguments)
- [Editor overview](#editor-overview)
- [Key reference](#key-reference)
- [F3 — File operations](#f3--file-operations)
- [F4 — Block operations](#f4--block-operations)
- [F5 — Format operations](#f5--format-operations)
- [F6 — Miscellaneous operations](#f6--miscellaneous-operations)
- [F7 — Printer operations](#f7--printer-operations)
- [Search and replace](#search-and-replace)
- [Split-pane mode](#split-pane-mode)
- [Configuration](#configuration)
- [Contributing](#contributing)
- [Acknowledgments](#acknowledgments)
- [License](#license)

---

## Features

- **Full-screen, keyboard-only editing** — every command lives on a function key or a chord; there are no menus and no mouse interaction, faithful to the original Norton Editor.
- **Split-pane editing** — open a second file side-by-side, jump between panes with <kbd>Tab</kbd>, and copy blocks or diff two files against each other.
- **Multi-level undo** — a run of consecutively typed characters undoes as a single step; every other edit is its own step.
- **Block operations** — mark, copy, move, or delete arbitrary regions of text, including across panes.
- **Literal multi-line search & replace** — find/replace terms that can span multiple lines, with an interactive yes/no/all replace loop.
- **Three retro display themes** — classic white, green, or amber phosphor, selectable from the command line or saved as a default.
- **"Print to PDF"** — export the whole buffer or just a marked block to a paginated PDF file, no physical printer or external library required.
- **Handles large files gracefully** — files are loaded incrementally in chunks, and saving is always safe: unread portions of a file are carried through untouched, so nothing is ever truncated.
- **Safe, atomic saves** that preserve the original file's line-ending style (CRLF vs. LF).
- **Persistent configuration** — tab width/mode, word-wrap, format width, indentation, display theme, cursor style, and print settings can all be saved and reloaded automatically on the next launch.
- **Quick shell access** — drop into a system shell in the current file's folder without leaving the editor.
- **Cross-platform** — built on the pure-Java [Lanterna](https://github.com/mabe02/lanterna) terminal library; Windows is the primary target, with macOS and Linux support included.

---

## Requirements

| Item  | Version |
|-------|---------|
| Java  | 17 (LTS recommended) |
| OS    | Windows primary; macOS/Linux supported but less exercised |
| Maven | Not bundled — install it yourself, or use the no-Maven fallback below |

> **JDK note:** build and run with a stable LTS JDK (17 or 21). Some newer/non-LTS builds have shown a
> real compatibility problem with the Lanterna library this project depends on — the editor hangs at
> ~100% CPU on the splash screen instead of showing a window. If you hit that, switch to a JDK 17/21
> install and it should go away.

---

## Getting started

```bat
mvn package
run.bat
```
```sh
mvn package
./run.sh
```

- `mvn package` compiles the project and, via the shade plugin, produces `target/retro-text-editor-1.0-SNAPSHOT-shaded.jar`, a self-contained executable jar.
- `run.bat` (Windows) launches that jar with `javaw`; `run.sh` (macOS/Linux) launches it with `java`. Both detach immediately rather than blocking the shell/console that ran them, and both print a clear error (instead of a cryptic Java stack trace) if the jar hasn't been built yet.

**No Maven available?** The build is simple enough to do by hand:

```bat
:: download com.googlecode.lanterna:lanterna:3.1.1 from Maven Central first
javac -encoding UTF-8 -cp lanterna-3.1.1.jar -d out src\main\java\br\com\demersonpolli\neonorton\*.java
java -cp "out;lanterna-3.1.1.jar" br.com.demersonpolli.neonorton.Main
```

---

## Command-line arguments

```
run.bat [+LINE] [INPUT [OUTPUT]] [/DA|/DB|/DC]
./run.sh [+LINE] [INPUT [OUTPUT]] [/DA|/DB|/DC]
run.bat [--line LINE] [--input INPUT] [--output OUTPUT] [--display da|db|dc]
```

| Argument | Effect |
|----------|--------|
| `+LINE` / `--line LINE` | Position the cursor at line `LINE` on startup |
| `INPUT` / `--input` | File to open (skips the splash screen's filename prompt) |
| `OUTPUT` / `--output` | Target for **F3 W** "write through cursor" (defaults to `INPUT` if omitted) |
| `/DA`, `/DB`, `/DC` / `--display da\|db\|dc` | Display theme: white, green, or amber text (overrides a saved **F5 S** config) |
| `--safe`, `--encoding bytes\|utf8` | Accepted for compatibility; currently no-ops — see [Configuration](#configuration) |

If no `INPUT` is given, the splash screen prompts for a filename as before.

---

## Editor overview

NeoNorton presents a full-screen terminal window. On startup a splash screen asks for a filename
(unless one was given on the command line). All commands are keyboard-driven; there are no menus
or mouse interactions.

The bottom row is a **status bar** showing:

```
Line=N    Col=M    FILENAME    Insert/Replace    WW=On/Off
```

Function keys **F3–F7** replace the status bar with a command overlay while active.

---

## Key reference

### Navigation

| Key | Action |
|-----|--------|
| Arrow keys | Move cursor |
| Ctrl + ← / → | Move word left / right |
| Home / End | Start / end of line |
| Ctrl + Home / End | Start / end of file |
| Page Up / Page Down | Scroll one screen |

### Editing

| Key | Action |
|-----|--------|
| Any character | Insert or overwrite depending on mode |
| INS | Enter **Insert** mode (or toggle Insert/Replace — see **F5 K**) |
| F6 → INS | Enter **Overwrite** mode |
| Tab | Insert per the mode set in **F5 T** (literal tab byte / spaces to next stop / move only) |
| Enter | Split line / new line (copies leading whitespace if **F5 I** auto-indent is on) |
| Backspace | Delete character left |
| Delete | Delete character right |
| Ctrl-W | Delete word left |
| Alt-W | Delete word right |
| Ctrl-L | Delete from cursor to beginning of line |
| Alt-L | Delete from cursor to end of line |
| Alt-K | Delete entire current line |
| Ctrl-U | **Undo**, multi-level — a run of consecutively typed characters undoes as a single step; every other edit is its own step |
| Ctrl-V | Toggle upper/lowercase from beginning of line to cursor |
| Alt-V | Toggle upper/lowercase from cursor to end of line |
| Ctrl-P | Insert a raw byte by 2-digit hex value (`00`–`FF`) |

### Special keys

| Key | Action |
|-----|--------|
| F1 | Paged help — PgUp/PgDn or ←/→ to turn pages, ESC to close |
| F2 | File status screen (file, line/tab/format/print settings, buffer size, free disk space, cursor, mode) |
| F9 | Open a system shell in the active file's folder, after a Y/N confirmation — `cmd.exe` on Windows, `Terminal.app` on macOS, the first available terminal emulator on Linux |
| Tab *(split mode)* | Switch active pane |

---

## F3 — File operations

Press **F3** to activate the file command bar, then press the highlighted letter:

| Key | Command |
|-----|---------|
| E | Exit with save (or close active pane in split mode) |
| Q | Quit with confirmation prompt |
| S | Save current file |
| X | Open second pane / switch between panes |
| N | Open a new file in the active pane |
| A | Append another file's contents at the cursor |
| L | Load more of the file — files over 256 KB open with only the first 256 KB loaded; **L** pulls in the next 256 KB, repeatable until the whole file is loaded (a status message reports how much, if any, remains) |
| W | Write from the start of the buffer through the cursor to the output file (see `--output`, or **F3 C** to close it) |
| C | Close the output file opened by **W** |

Saving (**F3 E** / **F3 S**) with part of the file still unread never loses that part — whatever
hasn't been loaded is carried forward from disk untouched, so it's always safe to edit and save
the beginning of a large file without loading the rest first.

---

## F4 — Block operations

Press **F4** to activate the block command bar:

| Key | Command |
|-----|---------|
| S | Set marker at cursor position |
| R | Remove markers |
| D | Delete marked block |
| C | Copy marked block to cursor |
| M | Move marked block to cursor |
| W | Copy marked block from the other pane |
| L | Mark current line |
| E | Mark from cursor to end of line |
| F | Find / jump to next marker |

Marked regions are highlighted: **cyan** background for the region, **yellow** background for the boundary characters.

---

## F5 — Format operations

Press **F5** to activate the format command bar:

| Key | Command |
|-----|---------|
| F | Format (reflow) the current paragraph at the configured width |
| L | Set the format / word-wrap line width |
| W | Toggle word-wrap while typing |
| T | Set tab width and mode (literal tab byte / insert spaces / move only) |
| C | Set cursor style — takes effect on the **next launch**, not immediately |
| D | Set display theme (same as `/DA` `/DB` `/DC`) |
| I | Toggle auto-indent |
| S | Save the current settings so they persist across launches — see [Configuration](#configuration) |
| K | Toggle Ins-key behavior: always-insert (default) vs. toggle Insert/Replace |

---

## F6 — Miscellaneous operations

Press **F6** to activate the misc command bar:

| Key | Command |
|-----|---------|
| G | **Go to line number** — prompts for a line number and moves the cursor there |
| M | **Match bracket** — jumps cursor to the matching `()`, `[]`, `{}`, or `<>` |
| T | **Text compare** *(split mode only)* — compares both panes from their current cursors and moves to the first difference |
| INS | Enter **Overwrite** mode |
| C | Toggle **condensed display** (a smaller font) — takes effect on the **next launch**, not immediately |

---

## F7 — Printer operations

**F7 doesn't send anything to a physical printer.** Instead it exports to a PDF file named
`<file>-print-<yyyy-MM-dd-hh-mm-ss>.pdf`, saved next to the file being edited.

| Key | Command |
|-----|---------|
| P | Print the whole buffer to a new PDF |
| B | Print the marked block to a new PDF |
| S | Set lines per printed page |
| M | Set the print left margin |
| E | Eject page — a no-op here, since **P**/**B** each produce one complete, already-paginated PDF per press rather than streaming to an open print job |

---

## Search and replace

| Key | Action |
|-----|--------|
| Alt-F | Find forward |
| Ctrl-F | Find reverse |
| Alt-C | Continue the last forward find |
| Ctrl-C | Continue the last reverse find |

While typing a search term: **ESC** finishes entry and makes the search case-insensitive (instead of
canceling); **Ctrl-Return** inserts a literal newline, letting a search span multiple lines.

Pressing the **same** find key again while a term is already active starts a replace: you're prompted
for a replacement string, then for each match:

| Key | Action |
|-----|--------|
| Y | Replace this match, continue |
| N | Skip this match, continue |
| `*` | Replace this and every remaining match without asking again |
| Space | Stop |

---

## Split-pane mode

- Press **F3 → X** to open a second file alongside the current one.
- Press **Tab** or **F3 → X** again to switch between panes.
- Press **F3 → E** in the active pane to close it and return to single-pane mode.
- **F4 → W** copies the marked block from the inactive pane into the active pane.
- **F6 → T** compares both panes character-by-character from each pane's cursor.

---

## Configuration

**F5 S** saves the current tab width/mode, format width, word-wrap, indent, display theme, cursor
style, print margin/page length, Ins-key behavior, and condensed-display setting to
`~/.neonorton/config.properties` (a plain Java `.properties` file). It's loaded back automatically the
next time the editor starts; a missing or corrupt file just falls back to defaults.

Cursor style (**F5 C**) and condensed display (**F6 C**) can't apply immediately — the underlying
terminal library only supports setting them when the window is first created — so both only take
effect after you save (**F5 S**) and restart the editor.

---

## Contributing

Issues and pull requests are welcome. This is a small, dependency-light hobby project — there's no
CI pipeline or automated test suite yet, so please build and exercise the editor manually
(`mvn package` + `run.bat`/`run.sh`) before submitting a change, and describe what you tested in
your PR description.

Good first contributions include: fixing a key binding that doesn't match `README.md`/the in-app
**F1** help, improving macOS/Linux support (only the Windows shell-launch path has been thoroughly
exercised), or rounding out edge cases in file loading/saving.

---

## Acknowledgments

- Inspired by the **Norton Editor**, Peter Norton's classic DOS-era text editor.
- Built on [Lanterna](https://github.com/mabe02/lanterna), a pure-Java library for terminal/console text UIs.

---

## License

NeoNorton is released under the [MIT License](LICENSE).
