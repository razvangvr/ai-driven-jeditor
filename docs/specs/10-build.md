# 10 — Compiling (building) to an output directory

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| BUILD-1 | Besides Run, there is a Compile (Build) option. | Done |
| BUILD-2 | It compiles the current file, or the files of the project, into a target/output directory. | Done |
| BUILD-3 | A Clean button deletes the build output, like `mvn clean`. | Done |

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

- **Clean** (toolbar button, or *Build → Clean*) deletes the build output, like `mvn clean`:

  | Project | Deleted |
  | --- | --- |
  | Maven | `target/` of the folder and of each direct child module (folders with a `pom.xml`) |
  | Plain folder | `out/` |

  Nothing else is touched: sources, `lib/`, and folders that only happen to be called `target`
  in a plain folder stay. The Console lists what was deleted and how much. There is no
  confirmation, as with `mvn clean`, because only rebuildable output is removed.
- Unsaved files are saved first. Progress and the result are shown in the **Console**: each
  error with its file and line, or "Build successful". The file viewer then shows the output
  folder.
- A build with errors writes no class files.

## Design decisions

- **Clean never follows links.** An output folder that is itself a symbolic link is skipped, and
  links inside an output folder are removed as links, so nothing outside the project can be
  deleted through one.

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
- Classes of deleted sources stay in the output directory until the next Clean.
- No incremental build: every build recompiles all sources.
- Resources (non-Java files) are not copied to the output directory.
- Errors in the Console are not clickable.
