# How JEditor works

## The short answers

| Question | Answer |
| --- | --- |
| Is `javac` reused? | **Yes.** All compiling is done by the real `javac`, called in-process through the standard `javax.tools` API. No compiler was written for this project. |
| What does the lexical analysis? | Two different lexers, for two different jobs, **neither written here**: `javac`'s own lexer/parser (for errors and structure) and RSyntaxTextArea's Java tokenizer (for colours and folding). |
| How is a file's structure loaded into memory? | `javac` parses the file into its syntax tree (AST); our `CodeStructureScanner` walks that tree and copies out classes, methods and fields with their positions. |
| Library or own code? | Parsing, type-checking, colouring and folding come from libraries. The glue, the project/classpath detection, the run feature and the whole UI are own code. |

## Libraries used

| Library | Used for |
| --- | --- |
| **JDK compiler** (`javax.tools`, `com.sun.source` — part of every JDK, not a download) | Lexing, parsing, type-checking, generating class files |
| **RSyntaxTextArea** | The text component: syntax colours, fold markers, squiggly underlines, line numbers |
| **FlatLaf** | Look and feel (window, buttons, trees) |
| **JUnit 5** | Tests |

## Modules

```
jeditor-compiler   no UI, no third-party dependencies
  dev.jeditor.compiler       analyse a source file: errors + structure
  dev.jeditor.compiler.run   Java runtimes, build to class files, launch
jeditor-ui         the Swing application
```

## What happens when you open or edit a Java file

```
EditorTab ──text──▶ JavaSourceAnalyzer ──▶ javac (in-process)
   ▲                     1. parse      → AST ──▶ CodeStructureScanner → structure, main classes
   │                     2. analyze    → type + flow checking          → diagnostics
   └── AnalysisResult ◀──┘
```

1. `EditorTab` waits 0.4 s after the last keystroke, then sends the **text in the editor** (not
   the file on disk) to a background thread.
2. `JavaSourceAnalyzer` hands that text to `javac` as an in-memory source file and runs two
   compiler phases:
   - **parse** — `javac` lexes and parses the text into an AST. `CodeStructureScanner` walks the
     AST once and builds a list of `CodeBlock`s (type / method / field, name, start and end
     offset and line). It also notes which classes have a `public static void main(String[])`.
   - **analyze** — `javac` resolves names, checks types and control flow. It stops there: no
     class files are written.
3. Everything `javac` reported becomes a `CompilationDiagnostic` (severity, message, line,
   column, start/end offset). The only own logic here is `normalizeRange`: `javac` sometimes
   reports an empty range, a whole method body, or end-of-file; this is narrowed to a short visible
   span so there is something sensible to underline.
4. Back on the UI thread the `AnalysisResult` is shown: `DiagnosticsParser` turns diagnostics
   into squiggles and tooltips, the gutter gets icons, and the **Problems** and **Structure**
   panels and the status-bar breadcrumb are refreshed.

**How other classes are found.** `ProjectLayout.detect` looks at the disk: the source root is the
file's folder minus its `package` path (the package name is read with a small regex,
`PackageNameExtractor` — the one piece of hand-written "parsing"); in a Maven project the
`src/main/java`, `src/test/java` and `target/classes` folders of the module and its sibling
modules are added, plus jars in `target/dependency` or `lib`.

## Colours and folding

These do **not** come from the compiler. RSyntaxTextArea has its own fast Java tokenizer that
re-colours only the lines being edited, and a brace-matching fold parser for the `[-]` markers.
That is why colours update instantly while errors appear a moment later: the first is a cheap
per-line lexer, the second a full compile.

A consequence: colouring is purely lexical (keywords, strings, comments, numbers, annotations).
It does not know whether an identifier is a field, a local variable or a type.

## Running a main class

```
Run ▶ ─▶ save ─▶ ProgramBuilder (javac, --release N) ─▶ temp dir of .class files
                                                              │
        Console ◀── stdout/stderr ── <chosen runtime>/bin/java -cp … Main
```

1. **Runtimes.** *Run → Java Runtimes…* adds a JRE or JDK folder. `JavaRuntime.detect` checks
   for `bin/java`, reads the version from the installation's `release` file, and notes whether
   `bin/javac` exists (JDK) or not (JRE). `RuntimeRegistry` stores the list and the selected one
   in `~/.jeditor/runtimes.properties`. On first start the Java running the editor is added.
2. **Build.** `ProgramBuilder` compiles the file, and the project sources it uses, into a fresh
   temporary directory. It always uses the editor's own `javac`, with `--release N` set to the
   chosen runtime's version. That is what makes a plain **JRE** usable: it needs no compiler of
   its own, and code using language features or APIs newer than that runtime fails the build
   instead of crashing at run time.
3. **Launch.** `JavaLauncher` starts `<runtime>/bin/java -cp <classes + project classpath> <MainClass>`
   as a separate process, in the opened folder. `RunController` streams its output into the
   **Console** tab; the *Input* field feeds its standard input; *Stop* kills it.

## Limits worth knowing

- The editor itself must run on a **JDK 21+** (it needs `javac` in-process). Runtimes you add
  for *running* can be JREs, from Java 8 upward.
- Live error checking always uses the editor's own `javac` at Java 21 level, whichever runtime
  is selected; version mismatches show up when you press Run.
- Maven dependencies are not resolved; see the README for the `target/dependency` workaround.
- Run takes no program arguments and runs the first main class found in the current file.
