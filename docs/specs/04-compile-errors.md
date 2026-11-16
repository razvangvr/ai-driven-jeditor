# 04 — Compiler and compile errors

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| COMP-1 | The editor uses a Java compiler. | Done |
| COMP-2 | When a Java file is opened, it is loaded and checked for compile errors. | Done |
| COMP-3 | Compile errors are shown on screen with the well-known look and feel of classic IDEs. | Done |

## Behaviour

- **When (COMP-2):** on opening the file, and again 0.4 s after each edit. The text in the
  editor is what is checked, saved or not.
- **How errors are shown (COMP-3):**
  - a squiggly underline under the offending code, red for errors and yellow for warnings;
  - the compiler's message as a tooltip on that underline;
  - an icon in the gutter on that line;
  - a mark in the strip beside the scrollbar;
  - a row in the **Problems** table (message and line:column); double-click jumps there;
  - an error badge on the file's tab.
- Syntax errors, type errors and flow errors (e.g. missing `return`) are reported together.

## Design decisions

- Compiling runs in the background; nothing is written to disk.
- Warnings are shown too (raw types, unchecked calls, deprecation, …).
- **Finding other classes:** classes in the same source tree are found from the file's
  `package` declaration. In a Maven project, `src/main/java`, `src/test/java` and
  `target/classes` of the module and its sibling modules are used, plus jars in
  `target/dependency`. In any project, JARs in the `lib` (or `libs`) folder of the opened folder
  are used too; see [spec 14](14-classpath-inspector.md).

## Not covered

- Maven dependencies are not resolved automatically. Until `mvn dependency:copy-dependencies`
  has been run in the edited project, imports from third-party libraries show as errors.
- Errors are listed for the current file only; there is no project-wide problem list.
- Checking is always at Java 21 level, regardless of the runtime selected for running.
