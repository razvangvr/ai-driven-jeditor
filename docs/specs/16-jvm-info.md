# 16 — JVM Info

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| JVM-1 | A new tab in the fashion of *Launch Classpath*, named *Jvm Info*. | Done — tab *JVM Info* |
| JVM-2 | It prints the system RAM size, in GB. | Done |
| JVM-3 | It prints how much of that memory was reserved for the current application (the main class being run), i.e. the initial heap size. | Done — maximum heap and initial heap |
| JVM-4 | It prints the heap size actually used. | Done — live, while the program runs |
| JVM-5 | It prints an OS summary: name and architecture. | Done |

## Behaviour

The *JVM Info* tab sits next to *Launch Classpath* and describes the program started by the most
recent Run. It appears the moment the program starts, is refreshed once a second while it runs,
and keeps the last figures after it ends.

| Section | Content |
| --- | --- |
| Program | Main class, process id, running or ended, Java runtime, garbage collector. |
| System memory (JVM-2) | RAM installed, in GB, and RAM free right now. |
| Memory reserved for this program (JVM-3) | **Maximum heap**: the most the program may ever use for its objects, as a size and as a share of the RAM. **Initial heap**: what the heap starts at. Each with a line saying where the number comes from. |
| Heap actually in use (JVM-4) | **Used**: occupied by the program's objects now, also as a share of the maximum. **Taken from the OS**: what the JVM has really claimed so far. **Peak used**: the highest value seen. |
| Operating system (JVM-5) | Name and version, processor architecture, number of processors. |

- The text can be selected and copied.
- A program that ends within a fraction of a second cannot be measured; the tab then says so in
  the "in use" section and still shows everything else.

## About "reserved"

A JVM does not set memory aside up front. It has a **maximum** it is allowed to reach and an
**initial** size it starts from, and takes more from the operating system only as the program
needs it. Since the question was how much is reserved for the program, the tab shows both limits
and, under "in use", how much has actually been taken.

Because the editor starts programs without `-Xmx` or `-Xms`, Java picks the limits itself from
the machine's RAM: by default a quarter of it as the maximum and 1/64 as the initial size.

## Design decisions

- **Name:** *JVM Info*.
- **The figures are the launched program's**, not the editor's own: the editor connects to the
  program's JVM while it runs and reads its heap, the way monitoring tools do. Nothing is added
  to the program or to its command line.
- **Once a second** is often enough to follow a program and cheap enough not to disturb it.
- **Units:** RAM in GB; heap sizes in MB below 1 GB and in GB above, whichever reads better.

## Not covered

- Only the heap is shown. Memory a JVM uses outside the heap (loaded classes, thread stacks,
  the JVM's own code) is not, so the process's total memory use is higher than "used".
- No chart or history; only the current figures and the peak.
- "Peak used" comes from once-a-second readings, so a spike between two readings is missed.
- The heap limits cannot be changed from the editor (no `-Xmx` setting; see spec 05).
- Reading the live figures needs the editor to run on a JDK, which it already requires.
