# 06 — Navigating to a type

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| NAV-1 | Clicking on a Java type/class in the code navigates to it: the clicked type is opened in a new editor tab. | Done — with Ctrl+click |

## Behaviour

- **Ctrl+click** (Cmd+click on macOS) on a type name opens the file that declares it in a new
  tab, or switches to its tab if already open, with the caret on the type's name.
- While Ctrl is held, the name under the mouse is underlined as a link.
- **Ctrl+B** (*Navigate → Go to Declaration*) does the same for the name at the caret.
- It works wherever the type is used: imports, field and variable types, generics,
  `extends`/`implements`, `new`, qualified names, nested types.
- When the type has no source to open (JDK and library classes), the status bar says so.

## Design decisions

- **Ctrl+click rather than a plain click.** A plain click has to keep placing the caret,
  otherwise the code could not be edited; Ctrl+click is the convention in classic IDEs.
- The same gesture also navigates to **methods, fields, parameters and local variables**, since
  the compiler resolves them the same way. For an overloaded method, the overload actually
  called is the one opened.
- Resolution uses the text in the editor, saved or not.

## Not covered

- No source for JDK or library classes (the JDK's `src.zip` and source jars are not read).
- No "back" to return to where you came from, and no "find usages".
- If the target file is open with unsaved edits, the caret can land slightly off, because the
  position is computed from the saved file.
