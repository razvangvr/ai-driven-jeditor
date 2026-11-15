# 10 — Compiling (building) to an output directory

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| BUILD-1 | Besides Run, there is a Compile (Build) option. | Done |
| BUILD-2 | It compiles the current file, or the files of the project, into a target/output directory. | Done |

## Behaviour

- **Build → Build Project** (Ctrl/Cmd+F9, or the **Build** toolbar button) compiles all Java
  sources of the opened folder.
- **Build → Compile Current File** (Ctrl/Cmd+Shift+F9) compiles the file in the current tab,
  together with the project classes it uses.
- **Where the class files go (BUILD-2):**

  | Project | Sources | Output |
  | --- | --- | --- |
  | Maven (`pom.xml` present) | `src/main/java` of the folder and of each direct child module | that module's `target/classes` |
  | Maven, compiling one test file | the file | `target/test-classes` |
  | Plain folder | every `.java` file in the folder | `<folder>/out` |

- Unsaved files are saved first. Progress and the result are shown in the **Console**: each
  error with its file and line, or "Build successful". The file viewer then shows the output
  folder.
- A build with errors writes no class files.

## Design decisions

- Classes are compiled for the Java version of the runtime selected in the toolbar, as for Run.
- In a plain folder, the folders `out`, `target`, `build`, `bin`, `node_modules` and hidden
  folders are not searched for sources.
- In a multi-module Maven project each module is compiled into its own output; a module can use
  another module's sources without that module's classes being copied into it.
- Building does not use Maven itself; it only follows Maven's directory conventions.

## Not covered

- **Run does not use the build output.** Run still compiles into its own temporary directory, so
  it never depends on a previous build.
- Maven **test sources are not part of Build Project**; they need the project's test libraries,
  which are not resolved (see spec 04).
- No *Clean*: classes of deleted sources stay in the output directory until removed by hand.
- No incremental build: every build recompiles all sources.
- Resources (non-Java files) are not copied to the output directory.
- Errors in the Console are not clickable.
