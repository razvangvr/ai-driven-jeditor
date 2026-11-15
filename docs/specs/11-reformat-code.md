# 11 — Reformat code

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| FMT-1 | Option+Command+L reformats the code. | Done — Option+Cmd+L on macOS, Ctrl+Alt+L on Windows and Linux |
| FMT-2 | The Java standard code formatting is used. | Done — the Sun/Oracle Java code conventions |

## Behaviour

- **Option+Cmd+L / Ctrl+Alt+L** (*Edit → Reformat Code*) reformats the current Java file. With a
  selection, only the selection is reformatted.
- **The style (FMT-2)** is the standard *Code Conventions for the Java Programming Language*:
  - 4 spaces per indentation level, no tabs;
  - opening brace at the end of the line, closing brace on its own line, `else`/`catch`/`finally`
    on the line of the closing brace;
  - one statement per line; one blank line between members; imports each on their own line;
  - spaces around operators, after commas and after keywords such as `if` and `for`;
  - `case` labels aligned with their `switch`;
  - lines wrapped at 80 columns, continuation lines indented 8 spaces;
  - Javadoc comments tidied.
- The whole reformat is **one undo step**. The caret stays in front of the character it was at.
- The status bar reports the result: reformatted, already formatted, or cannot reformat.
- Only whitespace and comment layout change; the code itself is never altered.

## Design decisions

- **Formatter: Eclipse JDT**, with its built-in "Java Conventions" profile, which implements
  the conventions above. The one change to that profile is spaces-only indentation (the profile
  mixes tabs and spaces).
- Formatting needs code that parses, not code that compiles: type errors do not matter. With
  syntax errors, what can still be recognised is formatted and the broken part is left as
  written; code too broken to parse at all is left alone and the status bar says so.
- The file is not saved by reformatting.
- The formatter lives in its own Maven module, `jeditor-format`, so the compiler module stays
  free of third-party dependencies. It adds about 14 MB to the application jar.

## Not covered

- The style is fixed: no settings for line length, indentation or brace placement.
- Imports are not reordered and unused imports are not removed.
- No reformat-on-save, and no reformatting of several files at once.
- Only Java files; XML, JSON and others are not reformatted.
- On some Linux desktops Ctrl+Alt+L is taken by the system (lock screen); use the menu there.
