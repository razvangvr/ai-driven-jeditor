# 15 — Launch Classpath

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| LAUNCH-1 | A new section at the bottom, next to the Console (working name `Launch_Classpath`), makes visible how a program is launched and how its classpath comes about. | Done — tab *Launch Classpath* |
| LAUNCH-2 | It shows the main class that was launched. | Done |
| LAUNCH-3 | It shows what is added to the classpath, and prints the classpath. | Done |

## Behaviour

The *Launch Classpath* tab, next to *Console*, describes the most recent Run. It is filled in
the moment the program starts and completed when it ends. Before the first Run it says what it
will show.

| Section | Content |
| --- | --- |
| Main class (LAUNCH-2) | The class handed to Java, and the source file it is declared in. |
| Java runtime | The runtime's name and version, and the `java` executable used. |
| Working directory | The program's current directory. |
| Classpath (LAUNCH-3) | Every entry in search order, each with the reason it is there: the project's own freshly compiled classes, a JAR from the `lib` folder, a Maven output folder, … |
| How this classpath was put together | In words: Java has no rule about a `lib` folder and loads only what `-cp` lists; the editor compiled the sources into a temporary folder, put it first, and added each JAR found in the library folder. With no libraries, it says where to put them. |
| Exact command the editor ran | The full command, one argument per line, pasteable into a terminal. |
| To run it yourself, without the editor | Two steps: Build Project, then a `java -cp "out:lib/*" Main` command for a terminal. |
| Result | Still running, or the exit code and how long it ran. |

- **Copy Command** copies the exact command; **Copy Classpath** copies the value passed to `-cp`.
  The text itself can also be selected and copied.
- The **Console** now prints the full command too, where it used to print a shortened one
  without the classpath.

## Design decisions

- **Name:** *Launch Classpath*.
- **It reports what actually ran.** The tab is filled from the very command line and classpath
  list that were used to start the process, not recomputed afterwards.
- **Text, not a tree:** the point is to read an explanation top to bottom and copy from it.
- **Two views of the classpath:** this tab is about the last Run; the *Classpath Inspector*
  (spec 14) is about the file being edited, before anything is run, and shows what is inside
  each JAR.
- The "run it yourself" command uses the Build output folder (`out`, or `target/classes`) in
  place of the temporary folder of the Run, and `folder/*` for the JARs of a library folder.

## Not covered

- Only the most recent Run is kept; there is no history of launches.
- A Run that fails to compile launches nothing, so the tab keeps showing the previous launch.
- Program arguments and JVM options cannot be set (spec 05), so none appear beyond the three
  encoding options the editor always adds.
- The Java runtime's own classes (the JDK modules) are not part of the classpath and are not listed.
