# 01 — Project and technology

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| TECH-1 | The product is a Java editor together with a Java compiler component. | Done |
| TECH-2 | Everything is implemented in Java. | Done |
| TECH-3 | Java 21 is used. | Done |
| TECH-4 | The project uses the Maven structure. | Done |
| TECH-5 | The heavier logic (compiler side and similar) has decent/good tests. | Done |
| TECH-7 | A `.sh` file command starts the editor. | Done — `./jeditor.sh` |
| TECH-6 | The compiler may be built on a well-established, easy-to-use Java library rather than written from scratch. | Done |

## Design decisions

- **Two Maven modules.** `jeditor-compiler` (all compiler-side logic, no UI, no third-party
  dependencies) and `jeditor-ui` (the Swing application).
- **Compiler: the JDK's own `javac`**, through the standard `javax.tools` API (TECH-6). It is
  part of every JDK, so nothing extra is downloaded, and results are identical to a real build.
- **UI libraries**, both pure Java: RSyntaxTextArea (text component) and FlatLaf (look and feel).
- **Tests** are JUnit 5 and concentrate on `jeditor-compiler`. The UI module has tests only for
  its logic (diagnostic mapping, displayed texts), not for clicking through windows.
- **Packaging:** `mvn package` produces one runnable jar, `jeditor-ui/target/jeditor.jar`.
- **Launcher (TECH-7):** `jeditor.sh` builds the jar when it is missing or older than the
  sources, checks that Java 21+ is available, and passes its arguments (a folder or file to
  open) to the editor. `--rebuild` forces a build.

## Not covered

- No launcher for Windows (`.bat`); there, start the jar with `java -jar`.
- The editor must itself be started on a JDK 21 or newer; on a JRE there is no compiler, so no
  error checking and no Run.
