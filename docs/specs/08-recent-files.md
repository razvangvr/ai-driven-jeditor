# 08 — Recent files

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| RECENT-1 | The application maintains a list of the most recent files (10). | Done |
| RECENT-2 | The list is accessed with Cmd+E. | Done — Cmd+E on macOS, Ctrl+E on Windows and Linux |

## Behaviour

- **Cmd+E / Ctrl+E** (*File → Recent Files…*) opens a list of the 10 most recently used files,
  newest first, each with its folder.
- Up/Down to choose, **Enter** or double-click to open, **Esc** to dismiss. Choosing a file opens
  it in a tab, or switches to its tab if it is already open.
- The entry after the current file is preselected, so the shortcut followed by Enter jumps back
  to the previous file.

## Design decisions

- **"Recent" means recently viewed**, not only recently opened: switching to a tab moves that
  file to the top of the list.
- The list is remembered between sessions (`~/.jeditor/recent-files.txt`) and spans folders: it
  is one list for the user, not one per project.
- Files that no longer exist are left out of the list.
- Closing a folder or quitting does not reorder the list.

## Not covered

- No type-to-filter in the list.
- No way to clear the list from the application.
