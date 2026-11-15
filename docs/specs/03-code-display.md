# 03 — Syntax colours and code blocks

## Requirements

| ID | Requirement | Status |
| --- | --- | --- |
| CODE-1 | Basic/decent code support on screen. | Done |
| CODE-2 | Code blocks are shown, e.g. where a method starts and ends. | Done |
| CODE-3 | A basic Java colour scheme for keywords, variable names, etc. | Partial — variable names are not coloured differently from other identifiers |

## Behaviour

- **Code blocks (CODE-2):**
  - `[-]`/`[+]` markers in the gutter at the first line of every class, method and multi-line
    comment; clicking one folds the block.
  - The matching bracket is highlighted when the caret is next to a bracket.
  - The **Structure** panel lists the file's types and members; a click jumps to one, and its
    tooltip gives the lines it spans.
  - The status bar shows the block the caret is in and its line range.
- **Colours (CODE-3):** keywords, strings, comments, numbers and annotations each have their own
  colour, in a light theme modelled on IntelliJ's.

## Design decisions

- Also on by default: line numbers, highlighting of other occurrences of the word under the
  caret, auto-indent, automatic closing of `{`.
- Other file types (XML, JSON, properties, Markdown, …) are coloured by file extension.

## Not covered

- **CODE-3 gap:** colouring is lexical only. It cannot tell a field from a local variable, a
  parameter or a type name, so those all look the same.
- Code completion, formatting, refactoring.
