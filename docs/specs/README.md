# JEditor — requirements

The requirements for JEditor, as stated by the project owner, grouped by area.

| Spec | Area |
| --- | --- |
| [01](01-project-and-technology.md) | Project and technology |
| [02](02-layout-and-files.md) | Window layout, file viewer, folders |
| [03](03-code-display.md) | Syntax colours and code blocks |
| [04](04-compile-errors.md) | Compiler and compile errors |
| [05](05-running-programs.md) | Java runtimes and running main classes |
| [06](06-navigation.md) | Navigating to a type |
| [07](07-documentation.md) | Documentation |
| [08](08-recent-files.md) | Recent files |
| [09](09-files-and-editing-basics.md) | Creating files and basic editing |
| [10](10-build.md) | Compiling (building) to an output directory |
| [11](11-reformat-code.md) | Reformat code |

## How to read these

- **Requirement** rows are what was asked for. Each has an ID (e.g. `RUN-2`) and a status:
  **Done**, **Partial** (with the gap named), or **Open**.
- **Design decisions** are choices made while implementing, where the requirement left room.
  They were not asked for explicitly and can be changed.
- **Not covered** lists things a reader might assume are there and are not.

For how the implementation works, see [../HOW_IT_WORKS.md](../HOW_IT_WORKS.md).
