# 02 — Window layout, file viewer, folders

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| UI-1 | A straightforward Java editor with the well-known layout. | Done |
| UI-2 | A file viewer on the left side. | Done |
| UI-3 | The main area is where a file's content is viewed and edited. | Done |
| UI-4 | The current folder/project can be closed, through a button or menu option. | Done — *File → Close Folder* |

## Behaviour

- **File viewer:** tree of the opened folder, folders first; double-click or Enter opens a file.
- **Editor area:** one tab per open file; a dot on the tab marks unsaved changes; *File → Save*
  (Ctrl+S), *Save All*, *Close Tab* (Ctrl+W).
- **Opening:** *File → Open Folder…*, *File → Open File…*, or a path on the command line.
- **Closing a folder (UI-4):** closes all its tabs (asking about each unsaved file; cancelling
  keeps the folder open), stops a running program, and empties the file viewer.

## Design decisions

- Extra panels beyond what was asked: **Structure** (under the file viewer), **Problems** and
  **Console** (under the editor), a toolbar and a status bar.
- Files and folders whose name starts with a dot are hidden in the file viewer.
- Files over 5 MB are refused.

## Not covered

- Creating, renaming and deleting files, and find/replace, are covered in [spec 09](09-files-and-editing-basics.md).
- The file viewer does not notice changes made by other programs until refreshed (*File → Refresh File Tree*, F5).
