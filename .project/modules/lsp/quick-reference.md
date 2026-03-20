# LSP (Language Server Protocol) Tool — Quick Reference

**What:** Built-in Claude Code tool for code intelligence. Queries running LSP servers to navigate code without reading files — definitions, references, call hierarchies, hover docs.
**Added:** Claude Code v2.1.71
**No dependencies** — LSP servers must already be configured for the file type in the environment.

---

## Operations

All operations require three position parameters:
- `filePath` — absolute path to the file
- `line` — line number (1-based, as shown in editors)
- `character` — character offset (1-based, as shown in editors)

### goToDefinition
Find where a symbol is declared.

```
goToDefinition
  filePath: /absolute/path/to/File.kt
  line: 42
  character: 15
```

**Returns:** File path + position of the definition. Cross-file — works across module boundaries.

---

### findReferences
Find all usages of a symbol across the workspace.

```
findReferences
  filePath: /absolute/path/to/File.kt
  line: 42
  character: 15
```

**Returns:** List of file paths + positions where the symbol is referenced.

---

### hover
Get documentation and type information at a position.

```
hover
  filePath: /absolute/path/to/File.kt
  line: 42
  character: 15
```

**Returns:** KDoc/Javadoc, type signature, or other hover info the language server provides.

---

### documentSymbol
Get all symbols (classes, functions, properties, etc.) declared in a single file.

```
documentSymbol
  filePath: /absolute/path/to/File.kt
  line: 1
  character: 1
```

**Note:** `line`/`character` are required by the protocol but unused for this operation — pass `1`/`1`.

**Returns:** Flat or nested list of all symbols with their kinds (class, function, variable, etc.) and positions.

---

### workspaceSymbol
Search for symbols by name across the entire workspace.

```
workspaceSymbol
  filePath: /absolute/path/to/any/File.kt
  line: 1
  character: 1
```

**Note:** Pass any valid file; `line`/`character` are unused. The query is the symbol name.

**Returns:** Matching symbols across all files with their kinds and locations.

---

### goToImplementation
Find concrete implementations of an interface or abstract method.

```
goToImplementation
  filePath: /absolute/path/to/Interface.kt
  line: 10
  character: 8
```

**Returns:** File paths + positions of all implementing classes/methods.

---

### prepareCallHierarchy
Get the call hierarchy item at a position (prerequisite for incomingCalls / outgoingCalls).

```
prepareCallHierarchy
  filePath: /absolute/path/to/File.kt
  line: 55
  character: 12
```

**Returns:** The call hierarchy item (function/method) at that position, used as input for the next two operations.

---

### incomingCalls
Find all functions that call the function at a position.

```
incomingCalls
  filePath: /absolute/path/to/File.kt
  line: 55
  character: 12
```

**Returns:** List of callers with their file positions and the specific ranges of the call sites.

---

### outgoingCalls
Find all functions called by the function at a position.

```
outgoingCalls
  filePath: /absolute/path/to/File.kt
  line: 55
  character: 12
```

**Returns:** List of callees with their file positions and the specific ranges of the call sites.

---

## Practical Patterns

### Trace a ViewModel call chain
1. `documentSymbol` on the ViewModel file to find all method positions
2. `incomingCalls` on a method to see which composables call it
3. `outgoingCalls` on the same method to see which repository/service methods it calls

### Find all usages before refactoring
1. `goToDefinition` to confirm you're pointing at the right symbol
2. `findReferences` to get the full impact surface before changing a signature

### Explore an interface's implementation
1. `goToImplementation` on the interface method to find all concrete classes
2. `hover` on each implementation to read its inline docs without opening the file

### Understand unfamiliar code
1. `hover` for quick type/doc info
2. `goToDefinition` to jump to source
3. `documentSymbol` to get a map of the whole file's structure

---

## Gotchas

- **Server must be running.** If no LSP server is configured for the file type, the tool returns an error. Kotlin/Java requires a JVM-based server (e.g., Kotlin Language Server or Android Studio's embedded server).
- **1-based coordinates.** Both `line` and `character` are 1-based — match what your editor shows, not 0-based array indices.
- **`documentSymbol` / `workspaceSymbol` ignore position.** Pass `line: 1, character: 1` — the values are required by protocol but not used.
- **Cross-module resolution depends on classpath.** `goToDefinition` and `findReferences` across module boundaries only work if the LSP server has the full classpath (i.e., the project is fully indexed).
