# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

NeoNorton is a retro, keyboard-driven text editor inspired by the classic Norton Editor of the 1980s, written in Java 17 against the [Lanterna](https://github.com/mabe02/lanterna) terminal-UI library. It's a nostalgic/learning project, not production software — favor simplicity and fidelity to the retro feel over modern editor conventions.

## Build, run, and package

```bat
mvn package
run.bat [filename]
```

- `mvn package` compiles and (via the shade plugin) produces `target\retro-text-editor-1.0-SNAPSHOT-shaded.jar`, an executable fat jar with `br.com.demersonpolli.neonorton.Main` as the entry point.
- `run.bat` launches that shaded jar with `javaw`, optionally passing a filename to open on startup.
- There is no test suite and no linter configured in this repo.
- Target OS is Windows: the app opens a `SwingTerminalFrame` (a Swing window emulating a terminal), so it will not run headless.

## Architecture

The whole app is a sequence of full-screen `AppScreen` implementations driven from `Main`, sharing one Lanterna `MultiWindowTextGUI`/`Screen`:

- `Main` creates the `SwingTerminalFrame` + `Screen` + `MultiWindowTextGUI`, shows `SplashScreen` to collect a filename, then shows `EditorScreen` for that file.
- `AppScreen` is a one-method interface (`show(MultiWindowTextGUI gui)`); `SplashScreen`, `HelpScreen`, `StatusScreen`, and `EditorScreen` all implement it. Each screen owns its own `screen.readInput()` loop and draws directly via `TextGraphics` (Lanterna's immediate-mode drawing, not its widget/component system) — there are no reusable UI widgets.
- `StatusBar` is the one shared UI helper: a row of label/column pairs rendered in reverse video. `EditorScreen` keeps six `StatusBar` instances (one normal + one overlay per F3–F7 mode) and swaps which one is rendered depending on the active mode.

### EditorScreen — the core of the app

`EditorScreen` (~1300 lines) is a single class holding the whole document model, cursor state, and all editing/command logic — there's no separate model/view/controller split. Key things to know before editing it:

- **Document model**: `List<StringBuilder> lines`. Cursor is `(cursorRow, cursorCol)`; `scrollRow` tracks the top visible line for vertical scrolling.
- **Main loop** (`show`): reads one `KeyStroke` at a time. Function keys F1–F7 branch into dedicated modal command bars (each replaces the status bar with an overlay, described in README.md's key reference); all other keys go through `handleKey`, which is a big switch on `KeyType` handling navigation, insertion, deletion, and Ctrl/Alt chord shortcuts (word delete, line delete, case toggle, undo).
- **Split-pane via field-swapping**: rather than maintaining two parallel editor objects, there is exactly one set of "live" fields (`lines`, `cursorRow/Col`, `scrollRow`, `insertMode`, `activeFileName`, block markers) plus a parallel `*2` set for the inactive pane. `swapActivePaneData()` swaps the live fields with the `*2` fields. The pattern used throughout `show()` is: if pane 2 is active, swap in its data, run the normal single-pane logic against the "live" fields, then swap back. This means most editing/command methods (`handleKey`, `handleBlockOperation`, etc.) are written as if there's only ever one pane, and split-pane support is layered on entirely at the call sites in `show()`. When adding a new command, keep following this swap-before/swap-after pattern rather than threading pane state through the method signatures.
- **Block operations** (F4) mark a region with `markerBeginRow/Col` and `markerEndRow/Col` (`-1` row means "no marker"). `normalizeMarkers()`/`hasFullMarkers()` are the guards other block methods rely on before acting. `extractBlockContent`/`insertBlockAt`/`deleteBlockContent` are the primitives that copy/move/delete build on; `blockCopyFromOtherPane` is the one operation that reaches into the `*2` fields directly (since it needs both panes' data at once) instead of using the swap idiom.
- **Undo** is a single-level snapshot (`undoLines`/`undoRow`/`undoCol`) captured by `saveUndo()` before destructive operations (Backspace, Delete, word/line kill commands) and restored by `undoLastDelete()` (Ctrl-U). There's no redo and no multi-level history.
- **F5 (format) and F7 (print) command bars are UI stubs**: `handleFormatOperation`/`handlePrintOperation` draw the overlay and accept keys, but most of their commands are unimplemented placeholders (see README.md's "(stubs)" notes). Don't assume pressing those keys does anything beyond what's actually coded.
- Screen redraws go through `redraw()` → `drawTextPane()` (draws the buffer, handling scroll and block-marker highlighting) → `updateStatusBar()`. Any change to cursor/scroll/mode state should be followed by a call into this redraw path (as the existing key handlers do) rather than drawn ad hoc.

Full key bindings and per-mode command tables (F3 file ops, F4 block ops, F5 format ops, F6 misc ops, F7 print ops, split-pane behavior) are documented in `README.md` — treat it as the source of truth for user-facing behavior when changing key handling.
