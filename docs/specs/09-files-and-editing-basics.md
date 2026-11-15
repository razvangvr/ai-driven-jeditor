# 09 — Creating files and basic editing

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| BASIC-1 | A "new file" button exists, so that a new Java class can be created (for example in a newly opened, empty folder). | Done |
| BASIC-3 | Any node selected in the file viewer can be deleted, including a folder that contains several levels of packages. | Done |
| BASIC-2 | No other basic, common-sense functionality of a basic editor is missing. | Partial — the review below added the essentials; what is still absent is listed under *Not covered* |

## Behaviour

### Creating (BASIC-1)

- **New Java Class** — toolbar button, *File → New Java Class…* (Ctrl/Cmd+N), or right-click in
  the file viewer. Asks for a name and a kind (class, interface, enum, record, annotation),
  creates the file with its `package` line and an empty declaration, and opens it.
  - A qualified name (`billing.Invoice`) also creates the package folders.
  - The file goes into the folder selected in the file viewer (or the folder of the selected
    file; the opened folder when nothing is selected).
- **New File** and **New Folder** — toolbar (New File), *File* menu, or right-click. A name with
  `/` creates the folders on the way.
- An existing file is never overwritten; invalid names are refused with a message.

### Added by the BASIC-2 review

| Function | How |
| --- | --- |
| Rename a file or folder | Right-click → *Rename…*, or F2 in the file viewer. Open tabs follow the rename. |
| Delete a file or folder (BASIC-3) | Select it, then right-click → *Delete*, press Delete or Backspace (also with Cmd/Ctrl), or use *File → Delete Selected*. A folder goes with all its subfolders and files, however deep. Asks first, saying how many files and subfolders are inside. Goes to the system trash where there is one, otherwise it is permanent and the question says so. |
| Find in the current file | *Edit → Find…* (Ctrl/Cmd+F): all matches highlighted, Enter / Shift+Enter for next / previous, wraps around, match-case and whole-word options, Esc closes. |
| Replace | *Edit → Replace…* (Ctrl/Cmd+R): Replace and Replace All. |
| Go to line | *Navigate → Go to Line…* (Ctrl/Cmd+G), `line` or `line:column`. |
| Edit menu | Undo, Redo, Cut, Copy, Paste, Select All — these already worked from the keyboard; the menu makes them discoverable. |
| Save button | Toolbar. |
| File viewer refresh | Keeps expanded folders expanded; happens automatically after create, rename and delete. |

## Design decisions

- **Package of a new class** is worked out, in order, from: existing sources in the folder or
  above it; the Maven layout (`src/main/java`, `src/test/java`); a `src` folder; the path below
  the opened folder. A folder whose name cannot be a package name gives no `package` line.
- Renaming a file does **not** rename the class inside it or update references to it (that
  would be a refactoring). The compiler then reports the mismatch.
- Deleting closes the affected tabs without asking to save them.
- The opened folder itself (the top node) can be deleted too; the editor then closes it.
- A click anywhere on a row of the file viewer selects it, not only a click on the name, so that
  a right-click beside a name acts on that row.

## Not covered

Basic-editor functions that are still missing:

- **Search across files** (find in project).
- **Save As**. (Moving a file to another folder: see [spec 12](12-drag-and-drop.md).)
- **Reloading a file changed on disk** by another program; the editor keeps its own version and
  overwrites on save.
- **Program arguments** for Run (also noted in spec 05).
- **Code completion**, organise imports, rename refactoring. (Formatting: see [spec 11](11-reformat-code.md).)
- **Settings**: font size, theme, tab width are fixed.
- Regular-expression search.
