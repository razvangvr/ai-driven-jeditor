# 13 — Error handling and the error log

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| LOG-1 | The error handling across the project is reviewed. | Done — all 57 `catch` blocks; see *Review outcome* |
| LOG-2 | Where an error is handled quietly (caught and not shown), it is at least logged as an error or warning. | Done |
| LOG-3 | The log is a file in the `.jeditor` private folder, named like `errors-2026-11-15.log`. | Done — `~/.jeditor/errors-<date>.log` |
| LOG-4 | Unexpected exceptions are not lost unlogged (motivating case: a double-click on a file sometimes does not open/select it). | Done for the logging; the double-click cause itself is **not confirmed** — see *Not covered* |

## Behaviour

- **Where:** `.jeditor` in the user's home directory, one file per day:
  `errors-2026-11-15.log`. The folder is created when the first entry is written; a day without
  problems has no file.
- **What an entry contains:** date and time, WARNING or ERROR, the thread, the class that
  reported it, a sentence saying what failed and what the editor did instead, and the full
  stack trace.
- **What is logged:**
  - every problem the editor handles quietly (examples under *Review outcome*);
  - every failure shown in an error dialog that was caused by an exception (the dialog shows the
    message, the log keeps the stack trace);
  - **every exception no code caught**, on any thread. These are also announced in the status
    bar: "An unexpected error was logged (Help → Open Error Log)".
- ***Help → Open Error Log*** opens today's file in a tab, scrolled to the newest entry.
- Log files older than 14 days are deleted when the editor starts.

## Review outcome

| Kind of `catch` | Before | Now |
| --- | --- | --- |
| Quietly handled failures: unreadable folder or settings file, trash refused, compiler or formatter crash, theme not loaded, cleanup of temporary files, non-UTF-8 file | Silent | Logged as WARNING, with what the editor did instead |
| Failures shown to the user: cannot open / save / create / rename / move / delete | Dialog only | Dialog and log |
| Background work (error checking, go to declaration, reformat, build, run) | `RuntimeException` swallowed; any `Error` vanished completely, leaving e.g. "compiling…" for ever | Logged as ERROR; the operation ends cleanly |
| Opening a file | Only I/O errors handled; anything else ended silently and nothing opened | Any failure gives a dialog and a log entry |
| Exceptions with no handler at all | Printed to a console nobody sees | Logged as ERROR and announced in the status bar |
| User-input validation (invalid file or class name) | Dialog | Unchanged, not logged: not an error of the program |
| Expected, harmless cases (the running program closed its input; its output stream ended when it was stopped; a number failed to parse where that is one of the valid outcomes) | Silent | Unchanged, not logged |

## Design decisions

- **One log in the home directory**, not one per opened folder: errors also happen while no
  folder is open, and one place is easier to find and to send to someone.
- **No logging library.** Classes use the JDK's own `System.Logger`; a single class,
  `ErrorLog`, writes the file.
- **Repeats are collapsed:** the same problem recurring within one minute is written once.
  Some checks run after every edit, and one unreadable folder would otherwise fill the log.
- **Logging can never break the editor:** if the log file cannot be written, the entry is dropped.
- Only warnings and errors are logged; there is no informational or debug logging.

## Double-clicks in lists and trees

Opening on double-click (file viewer, Problems, recent files) and navigating on click (Structure)
now react to the mouse **press**. Before, they waited for a "click" event, which the toolkit only
produces if the pointer does not move between press and release.

## Not covered

- **The reported double-click failure was not reproduced.** Double-clicks with a slightly moving
  pointer opened the file both before and after this change in testing, so the change above
  removes a plausible cause but is not a confirmed fix. If it happens again, the log will now
  show whether an exception was behind it; an empty log would point at mouse handling instead.
- The log is not shown inside the editor as it grows; reopen it to see new entries.
- No setting for the log location, the level, or how long files are kept.
