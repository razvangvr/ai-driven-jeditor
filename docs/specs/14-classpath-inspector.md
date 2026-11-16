# 14 — Classpath Inspector

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| CP-1 | A new section/tab after *Structure*, named *Classpath Inspector*, lets the user see and understand the classpath of the application (the main class being launched). | Done |
| CP-2 | It shows the application's classpath. | Done |
| CP-3 | It shows what the application's `libs` folder is, if any — i.e. where a new library JAR (say commons-lang) is added. | Done |
| CP-4 | It lists all JARs available on the classpath, and related details. | Done |
| CP-5 | Existing libraries may be used for the job. | Done — the JDK's own `java.util.jar` was enough; no library added |

## Behaviour

The tab sits next to *Structure*, under the file viewer, and describes the Java file in the
current tab (or, with no Java file open, the libraries of the opened folder).

- **What is run (CP-1):** the main class of the file, and the Java runtime it runs on.
- **Classpath (CP-2):** every entry, numbered in the order it is searched; when two entries
  contain the same class, the earlier one wins. The first entry is always the project's own
  classes, compiled on each Run. **Copy** puts the classpath on the clipboard in `java -cp` form.
- **Each JAR (CP-4)** opens to show:
  - the library it is and its version (e.g. `org.apache.commons:commons-lang3`, `3.14.0`);
  - how many classes it contains and its size;
  - where it is, and why it is on the classpath (e.g. "library folder lib");
  - the packages inside it.
- **Library folders (CP-3):** the folder whose JARs are picked up, with the number of JARs in it
  and a note that new JARs go there. If it does not exist yet, it says so.
- **Add JAR…** copies the JARs you choose into that folder (creating it if needed). They are on
  the classpath at once: open files are re-checked, so errors about the missing library
  disappear, and Run and Build use them.
- **Source folders:** where the sources of the project's other classes are found.
- **Warnings**, when something looks wrong:
  - the same library is present twice (only the first is used);
  - a JAR contains no classes (a sources/javadoc JAR, or not a valid JAR);
  - a Maven project whose `pom.xml` dependencies have not been copied, with the command to do it.
- **Refresh** re-reads everything from disk. The tab also updates by itself when you switch
  file, open or close a folder, finish a build, or add a JAR.

## The library folder

| Project | Library folder |
| --- | --- |
| Plain folder | `lib` in the opened folder (`libs` also works) |
| Maven module | `lib` (or `libs`) in the module, in addition to `target/classes` and `target/dependency` |

A JAR dropped into the folder by any means, not only *Add JAR…*, is picked up: press
*Refresh* in the tab, or F5.

## Design decisions

- **What "the classpath" is.** The one the editor itself uses to check, build and run the file.
  The inspector reads the same computation, so it cannot disagree with what Run does.
- **`lib` now works in plain folders for everything.** Before this, JARs in `lib` of a
  non-Maven folder were used by Build Project but not by error checking or Run. That was a gap;
  it is closed.
- **How a JAR is identified:** from the Maven metadata inside it if present, else its manifest,
  else its file name.
- **No new dependency (CP-5).** Reading JARs needs only the JDK.

## Not covered

- **Maven dependencies are still not resolved** from `pom.xml`; the inspector warns and gives the
  command, but does not download anything.
- JARs cannot be removed or reordered from the tab (delete them in the file viewer).
- The JDK's own classes (the runtime's modules) are not listed as entries.
- Classes inside a JAR are not listed individually, only its packages.
- No search for "which JAR contains class X".
- The module path (`module-info.java` projects) is not shown; everything is treated as classpath.
