# NeoNorton

> **Historical / hobby software — not recommended for professional use.**  
> NeoNorton is a retro-style, keyboard-driven text editor inspired by the classic Norton Editor of the 1980s.  
> It is written in Java using the [Lanterna](https://github.com/mabe02/lanterna) terminal-UI library and is
> intended as a nostalgic exercise and learning project, not as a production tool.

---

## Requirements

| Item | Version |
|------|---------|
| Java | 17 or newer |
| OS   | Windows (SwingTerminalFrame) |
| Maven | bundled under `apache-maven/` |

---

## Building and running

```bat
.\apache-maven\bin\mvn package
.\run.bat
```

`run.bat` launches the shaded JAR with `javaw`.  
You may also pass a filename as argument:

```bat
run.bat myfile.txt
```

---

## Editor overview

NeoNorton presents a full-screen terminal window.  
On startup a splash screen asks for a filename.  
All commands are keyboard-driven; there are no menus or mouse interactions.

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
| INS | Enter **Insert** mode |
| F6 → INS | Enter **Overwrite** mode |
| Enter | Split line / new line |
| Backspace | Delete character left |
| Delete | Delete character right |
| Ctrl-W | Delete word left |
| Alt-W | Delete word right |
| Ctrl-L | Delete from cursor to beginning of line |
| Alt-L | Delete from cursor to end of line |
| Alt-K | Delete entire current line |
| Ctrl-U | Undo last delete |
| Ctrl-V | Toggle upper/lowercase from beginning of line to cursor |
| Alt-V | Toggle upper/lowercase from cursor to end of line |

### Special keys

| Key | Action |
|-----|--------|
| F1 | Help screen |
| F2 | File status screen (lines, cursor, mode) |
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

## F5 — Format operations *(stubs)*

Press **F5** to activate the format command bar:

| Key | Command |
|-----|---------|
| F | Format paragraph |
| L | Set line length |
| W | Toggle word wrap |
| T / C / D / I / S / K | Reserved |

---

## F6 — Miscellaneous operations

Press **F6** to activate the misc command bar:

| Key | Command |
|-----|---------|
| G | **Go to line number** — prompts for a line number and moves the cursor there |
| M | **Match bracket** — jumps cursor to the matching `()`, `[]`, `{}`, or `<>` |
| T | **Text compare** *(split mode only)* — compares both panes from their current cursors and moves to the first difference |
| INS | Enter **Overwrite** mode |

---

## F7 — Printer operations *(stubs)*

Press **F7** to activate the printer command bar:

| Key | Command |
|-----|---------|
| P | Print all |
| B | Block print |
| E | Eject page |
| S | Set lines per page |
| M | Margin |

---

## Split-pane mode

- Press **F3 → X** to open a second file alongside the current one.
- Press **Tab** or **F3 → X** again to switch between panes.
- Press **F3 → E** in the active pane to close it and return to single-pane mode.
- **F4 → W** copies the marked block from the inactive pane into the active pane.
- **F6 → T** compares both panes character-by-character from each pane's cursor.

---

## License

This project is provided as-is for educational and nostalgic purposes.  
No warranty is given. Use at your own risk.
