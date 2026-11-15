# 08 — Recent files

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| RECENT-1 | The application maintains a list of the most recent files (10). | Done |
| RECENT-3 | The list survives editor restarts; it is saved in a file inside the opened directory. | Done — `<folder>/.jeditor/recent-files.txt` |
| RECENT-4 | Opening a file from the recent list also opens the folder/project that contains it. | Done |
| RECENT-2 | The list is accessed with Cmd+E. | Done — Cmd+E on macOS, Ctrl+E on Windows and Linux |

## Behaviour

- **Cmd+E / Ctrl+E** (*File → Recent Files…*) opens a list of the 10 most recently used files,
  newest first, each with its folder.
- Up/Down to choose, **Enter** or double-click to open, **Esc** to dismiss. Choosing a file opens
  it in a tab, or switches to its tab if it is already open.
- **The file's folder comes with it (RECENT-4).** Choosing a file that is not in the folder
  currently open closes that folder (asking about unsaved files; cancelling keeps everything as
  it was), opens the folder the file was last used in, opens the file, and selects it in the
  file viewer. A file of the folder already open is simply opened.
- The list shows the open folder's files first, then recent files from other folders, up to 10
  in total. With no folder open it shows the recent files of all folders.
- The entry after the current file is preselected, so the shortcut followed by Enter jumps back
  to the previous file.

## Design decisions

- **"Recent" means recently viewed**, not only recently opened: switching to a tab moves that
  file to the top of the list.
- **One list per folder (RECENT-3).** Each opened folder has its own list, saved in
  `.jeditor/recent-files.txt` inside that folder and loaded again when the folder is opened.
  Files inside the folder are saved as relative paths, so the list survives moving or renaming
  the folder. The `.jeditor` folder is hidden in the file viewer.
- **A second list across all folders** is kept in `~/.jeditor/recent-files.txt`. It records,
  for each file, the folder that was open when the file was used; this is what RECENT-4 relies on.
- **Which folder is "the project" of a file:** the folder it was last used in. If that is unknown
  or gone, a guess from disk: the nearest folder above it that the editor was used in, else the
  version-control root (`.git`), else the top of its Maven build, else the file's own folder.
- Files that no longer exist are left out of the list.
- Closing a folder or quitting does not reorder the list.

## Not covered

- No type-to-filter in the list.
- No way to clear the list from the application (delete the file to clear it).
- `.jeditor/` is not added to the project's `.gitignore` automatically.
