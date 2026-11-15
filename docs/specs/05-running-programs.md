# 05 — Java runtimes and running main classes

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| RUN-1 | A JRE or a JDK can be added to the application. | Done |
| RUN-2 | The application can run Java main classes. | Done |

## Behaviour

- **Adding a runtime (RUN-1):** *Run → Java Runtimes…* → *Add…*, then pick the installation
  folder. Its version and kind (JRE or JDK) are detected. Runtimes can be removed there too.
- **Choosing:** the drop-down in the toolbar selects the runtime used for running.
- **Running (RUN-2):** the ▶ button, *Run → Run Main Class*, or Shift+F10 runs the `main`
  method of the file in the current tab:
  1. unsaved files are saved;
  2. the file and the project classes it uses are compiled;
  3. the program is started on the selected runtime, in the opened folder;
  4. its output appears in the **Console** tab, followed by its exit code.
- **While running:** the *Input* field sends a line to the program's standard input; ■ stops it.
- A compile error stops the run and is listed in the Console.

## Design decisions

- The list of runtimes and the selection are remembered between sessions
  (`~/.jeditor/runtimes.properties`). On first start, the Java running the editor is added.
- Compilation always uses the editor's own compiler, targeting the selected runtime's Java
  version. This is why a JRE, which has no compiler, is enough for running, and why code that
  needs a newer Java than the selected runtime fails at build time with a clear message.
- Runtimes from Java 8 upward are supported.
- One program runs at a time.

## Not covered

- Program arguments and JVM options cannot be set.
- If a file has several classes with a `main` method, the first one is run; there is no chooser.
- No debugger.
- Running on a runtime older than the editor's own has automated tests for the compile step
  only; it has not been tried against a real older installation.
