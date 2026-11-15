# 12 — Drag and drop in the file viewer

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| DND-1 | In the file viewer, a file can be dragged and dropped onto a folder. | Done |

## Behaviour

- Dragging a file onto a folder **moves** it into that folder, after a confirmation
  ("Move 'X' to 'folder'?").
- Dropping onto a file means "into that file's folder"; dropping onto empty space means the
  opened folder itself.
- If the file is open in a tab, the tab follows it to the new location. An unsaved tab asks to
  be saved first; cancelling there cancels the move.
- The file viewer then shows the file in its new place, selected.
- A move that would overwrite an existing file of the same name is refused.

## Design decisions

- **Folders can be dragged too**, with everything in them; open tabs of files inside follow.
  A folder cannot be dropped into itself or one of its own subfolders.
- **A confirmation is asked** because a drag is easy to do by accident and there is no undo for
  file moves.
- **Files dragged in from outside the editor** (the desktop, a file manager) are **copied** into
  the folder they are dropped on; the original stays where it was.
- The opened folder itself (the root of the tree) cannot be dragged.

## Not covered

- Moving a `.java` file does not update its `package` line or the references to it in other
  files; the compiler then reports the mismatch where it matters.
- No copy by dragging within the tree (e.g. with a modifier key), and no cut/copy/paste of files.
- One item per drag; there is no multi-selection in the file viewer.
- Folders do not expand by themselves while something is dragged over them; expand the target
  first if it is nested.
- Dragging a file out of the editor to another application.
