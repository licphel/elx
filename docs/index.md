---
title: ELX Language Manual
---

# ELX Language Manual

ELX is an embeddable language for configuration, console scripts, and small pieces of application logic. It provides separate lists and tables, functions and closures, explicit numeric widths, and managed references. A host application compiles scripts, supplies application functions, and runs the resulting code in its own context.

This manual describes the current compiler and runtime. The language sections explain what a script does. The [Java API reference](java-api.md) explains how an application compiles and executes scripts, reads returned values, and exposes host objects.

## Contents

- [Design decisions](#design-decisions)
- [Quick start](#quick-start)
- [1. Lexical conventions](#1-lexical-conventions)
- [2. Values and types](#2-values-and-types)
- [3. Variables, `env`, and scope](#3-variables-env-and-scope)
- [4. Expressions](#4-expressions)
- [5. Statements](#5-statements)
- [6. Functions and closures](#6-functions-and-closures)
- [7. Tables, lists, and methods](#7-tables-lists-and-methods)
- [8. Errors and protected calls](#8-errors-and-protected-calls)
- [9. Modules](#9-modules)
- [10. Standard library](#10-standard-library)
- [11. Memory, pointers, and GC](#11-memory-pointers-and-gc)
- [12. Host integration](#12-host-integration)
- [13. VM and bytecode](#13-vm-and-bytecode)
- [Java API reference](java-api.md)
- [Appendix A: grammar outline](#appendix-a-grammar-outline)
- [Appendix B: compatibility notes](#appendix-b-compatibility-notes)
- [Appendix C: implementation limits](#appendix-c-implementation-limits)

## Design decisions

### Why create ELX instead of using Lua?

The starting point was a preference for separate lists and tables. A list describes an ordered sequence, while a table describes associations between keys and values. Giving each its own type makes that intent visible in the program and lets the language define operations for each directly.
The second goal is the precision of static types. Explicit integer widths and signedness, together with separate `float` and `double` types, give arithmetic a predictable representation. The current implementation combines those concrete runtime types with some compiler checks. Type annotations already describe declarations and signatures, while broader enforcement remains an area for further work.
The last goal is out of complexity. Lua, especially the C implementation, is overcomplicated. Instead, ELX hides all complexities behind the APIs.

### Why use tables to represent objects?

ELX is intended for simple configuration and dispatch logic. Tables already hold state, functions, and methods, and metatables let a program customize operations. Keeping these building blocks gives scripts object-like behavior with a small syntax.

### Why have pointers?

A function sometimes needs to change a particular variable or container entry. A managed pointer expresses that relationship directly. A one-element list or a table could carry the same state, but would make the program describe an extra container instead of the location it wants to update.

These pointers play a role similar to C# `ref`. The runtime owns their storage, traces their references, and checks the lifetime of stack locations. Ordinary pointer use participates in automatic memory management. Explicit pins cover the additional case where host code retains a managed value between calls.

### Why is `print` both a function and a statement?

Convenient console scripting is one of ELX's goals. The statement `print 'answer', 42` makes a quick result easy to display. The function form `print('answer', 42)` also works in ordinary call syntax. Both send tab-separated values and a line ending to the VM's output sink.

### Why reserve moving-GC semantics?

The current collector keeps allocations at stable addresses. The host contract uses pins so a future collector can also support moving objects. Code that retains a managed value acquires a pin now, and that pin defines the value's lifetime across executions and collections.

### What is ELX's relationship with Java?

Java hosts the current implementation. The language itself is defined by its syntax, values, and execution rules, so another implementation can use a different host language. Details such as exception class names and diagnostic wording belong to that implementation.

### Why have macros?

ELX gives users room to choose how they write scripts. Token macros support generated configuration and repetitive code. A project can allow that flexibility or adopt a source policy that excludes macros when straightforward source is easier to maintain.

## Quick start

A script is a sequence of statements. Use `local` for a binding in the current scope, braces for a block, and `return` to pass values back to the caller.

```
local function twice(value) {
  return value * 2
}
return twice(21)
```

This script returns the integer `42`. A function can be declared inside another function and can refer to names in its surrounding scope. Newlines separate statements, and semicolons provide an alternative when several statements share a line.

The host compiles source into a `Prototype` and executes it through a `Context`. The returned `Results` object keeps the returned values available until it is closed. [Running a script](java-api.md#running-a-script-and-reading-its-results) shows the complete Java call.

## 1. Lexical conventions

Source is Unicode text. Identifiers are case-sensitive and may contain Unicode identifier characters, `_`, `$`, and `#`. Names beginning with `_` are reserved for host-internal source when normal compilation is used.

The reserved keywords are:

```
local native function return if else while for in break continue
true false null env print
```

Line comments begin with `//` and continue to the line ending. Block comments are enclosed by `/*` and `*/`. The lexer removes comments before parsing. A backslash immediately followed by a newline continues the current statement onto the next line. Otherwise, newlines and semicolons separate statements.

The preprocessor accepts a line-start `#define`. An object-like macro replaces a name with its replacement tokens. A function-like macro has an opening parenthesis immediately after its name in the definition. A final variadic parameter receives the remaining arguments as a list. `FILENAME` and `LINECOUNT` are built-in read-only macros. A bounded expansion depth limits recursive macro expansion.

String literals use single or double quotes. Supported escapes are `\n`, `\r`, `\t`, `\0`, escaped quotes and backslashes, and four-digit `\uFFFF` escapes. Use an escaped `\n` to include a line break in a string.

Decimal integers use the `int` type by default. Binary and hexadecimal literals use the `b'` and `h'` prefixes. A single apostrophe may separate digits. Suffixes select the exact type:

| Suffix   | Type     |
|----------|----------|
| *(none)* | `int`    |
| `b`      | `ubyte`  |
| `s`      | `short`  |
| `us`     | `ushort` |
| `u`      | `uint`   |
| `l`      | `long`   |
| `ul`     | `ulong`  |
| `f`      | `float`  |
| `d`      | `double` |

A decimal fraction or exponent without a suffix is a `double`. A leading sign is an operator, so `-1` is parsed as unary negation applied to the literal `1`.

## 2. Values and types

Values have a concrete runtime type. Numbers, booleans, strings, lists, tables, and functions can be stored in variables, passed to functions, and returned to a caller. `null` represents an absent value. A local declaration without an initializer and a lookup of a missing table key both produce `null`.

| Category         | Types                                 | Meaning                                                 |
|------------------|---------------------------------------|---------------------------------------------------------|
| Boolean          | `bool`                                | Logical value. Conditions must have this type.          |
| Signed integer   | `byte`, `short`, `int`, `long`        | 8, 16, 32, or 64 bits.                                  |
| Unsigned integer | `ubyte`, `ushort`, `uint`, `ulong`    | Explicit unsigned interpretation at the selected width. |
| Floating point   | `float`, `double`                     | IEEE-754 single or double precision.                    |
| Managed value    | `string`, `list`, `table`, `function` | Heap-managed language values.                           |
| Managed pointer  | `ptr`                                 | Checked handle created by address operations.           |
| Host value       | `external`                            | Object owned by the host-side external heap.            |

Binary numeric operations widen both operands to a common type. Integer width is the maximum operand width. The result is unsigned when an operand at that width is unsigned. If either operand is floating point, the result is `float` for a 32-bit operation or `double` for a 64-bit operation.

Conversions are explicit. Integer narrowing keeps the target's low bits. Floating-point values converted to integers truncate toward zero, wrap to the target width, and reject non-finite values. Conversion functions are named after the target type, for example `int(value)`, `long(value)`, `float(value)`, and `double(value)`. `str(value)` formats a value through the VM formatter, and `type(value)` returns its source type name.

Integer arithmetic keeps the result within the selected width by wrapping. Signed integer division truncates toward zero, and its remainder follows the dividend's sign. Integer division by zero raises an error. Floating-point operations follow IEEE-754 behavior. Shift counts are masked to the operand width, and `>>>` shifts in zero bits.

Strings are immutable Unicode text stored as UTF-8. `len(text)` counts Unicode code points, while `require('str').bytelen(text)` counts encoded bytes. These measures differ for non-ASCII text, and a visible character can contain several code points.

Assigning a list or table copies the reference to that container. Mutations through either variable affect the same container. Ordinary equality compares strings by content and containers structurally, with metatable hooks able to customize table comparisons. `referenceequal(a, b)` tests the underlying identity.

A type annotation documents the intended type of a local, parameter, or return value. The current parser accepts qualified names, generic arguments, and a trailing `?`. Runtime operations use the actual value tags, and annotations currently serve as metadata rather than a complete assignment-checking system.

## 3. Variables, `env`, and scope

`local` creates a binding in the current lexical scope. A braced block creates a nested scope. Function bodies create a new function scope and may capture bindings from their parents. A declaration without an initializer assigns `null`.

An unresolved ordinary name is a key in the VM's global environment table. The keyword `env` is the table itself:

```
env.answer = 41
env.answer += 1
return env.answer, answer
```

Both returned values are `42`. `env.answer` writes the same slot that an unresolved assignment `answer = 42` would write. `env` is a reserved keyword that always selects the VM environment. It can be passed to a function, indexed, mutated, and stored in another table. `require('sys').env` remains an alias for the same table for compatibility with host-facing code.

Global entries belong to a VM instance and persist across executions in that VM. The host controls any transfer of values between separate VMs.

A local name is found by searching the innermost visible scope and then its parents. Ordinary initializers are evaluated before the new bindings enter scope, so `local x = x` reads the previously visible `x`.

The compiler handles a directly assigned function specially. Both `local function f(...) { ... }` and `local f = function(...) { ... }` establish `f` while compiling the function body, allowing the function to call itself. A named function also has an internal self binding for recursion.

The current closure implementation copies captured values into the closure when it is created. Changes to a captured scalar persist in that closure's own storage. The outer scalar keeps its own storage. Captured tables and lists continue to refer to the same containers, so their contents provide shared mutable state.

For example, with `local x = 3`, a closure that increments its captured `x` can return `4` and then `5`, while the enclosing function still reads `3`. A captured list containing `x` instead lets both functions observe changes to the same list element.

A declaration with several names receives several values. `local a, b = pair()` requests two return values, while `local a = pair()` keeps the first. The compiler checks the value count for multiple declarations, so matching the destination count is part of the assignment contract.

## 4. Expressions

Expressions include literals, names, function expressions, list and table literals, calls, indexing, member access, unary operators, binary operators, and assignments used as statements.

The binary precedence is listed from low to high:

| Level | Operators            |
|-------|----------------------|
| 1     | `\|\|`               |
| 2     | `&&`                 |
| 3     | `\|`                 |
| 4     | `^`                  |
| 5     | `&`                  |
| 6     | `==`, `!=`           |
| 7     | `<`, `<=`, `>`, `>=` |
| 8     | `<<`, `>>`, `>>>`    |
| 9     | `+`, `-`             |
| 10    | `*`, `/`, `%`        |

Member access, indexing, calls, and postfix `++`/`--` bind more tightly than unary and binary operators. Unary operators are `+`, `-`, `!`, `~`, address-of `&`, dereference `*`, and prefix `++`/`--`. Binary operators at the same precedence associate from left to right. Parentheses group an expression explicitly.

Logical `&&` and `||` short-circuit and produce `bool`. Bitwise operators are eager and require integer values. String addition concatenates formatted values when at least one operand is a string. Tables can customize operations through metatable hooks.

A condition requires a `bool`. Comparisons produce booleans, and `bool(value)` requests an explicit conversion when needed. Short-circuit evaluation means that `false && expression` and `true || expression` skip the right operand.

Prefix increment and decrement return the updated value. Their postfix forms return the previous value and then store the update. An address expression selects a writable location, and dereferencing a pointer accesses that location.

A call may return up to four values. Scalar expression positions use one value, while multiple declarations and returns can carry several. The prefix `*` also expands a list in calls, assignments, and returns. The destination determines how the resulting values are consumed.

Arguments can be positional, named with `name=value`, or expanded from a list using `*values`. `**options` expands named arguments from a table. Positional arguments precede named arguments. Argument expressions run in source order before the callee binds them to parameters. Supplying the same parameter twice or naming an unknown parameter raises an error.

## 5. Statements

Statements execute in source order. A statement ends at a separator, a closing brace, or the end of the script. Braced blocks introduce a scope and group a sequence of statements.

### Assignments

An assignment writes to a local, global, container entry, or dereferenced pointer. Compound forms combine an operation with storing its result, including arithmetic, bitwise, shift, and logical assignments. Use `x += amount` to update `x` from its current value. Logical assignments apply the corresponding short-circuit rule.

### Branches and loops

`if condition { ... }` executes its body when the boolean condition is true. An optional `else if` chain tests subsequent conditions, and `else` handles the remaining case. `while condition { ... }` tests before each iteration, so the body can execute zero times.

The C-style loop is `for initializer, condition, step { ... }`. The initializer runs once, the condition is checked before each iteration, and the step runs after the body. A binding introduced by the initializer belongs to the loop scope.

`continue` skips to the next iteration. In a C-style loop it runs the step before testing the condition again. `break` exits the innermost loop. Both statements require an enclosing loop.

The iterator form is `for names in container { ... }`. It evaluates the container once and accepts a list or table. One binding receives the value. Two receive the key or index followed by the value. Three receive the ordinal, key or index, and value. List indices begin at zero. Table traversal follows the current hash-table storage order, which can change after insertion or resizing.

### Returns and calls

`return` ends the current function or script. A bare return produces zero values, and comma-separated expressions produce several values. Reaching the end of a function also returns zero values. A call used as a statement executes for its effects and discards its results.

### Native declarations

`native function host.log(message: string)` declares a signature at script scope. A return annotation can follow `->`. These declarations provide metadata for host tooling. The host supplies the actual implementation through a library.

## 6. Functions and closures

A function expression has the form `function(parameters) { body }`. Evaluating it produces a function value. Function bodies can contain further function expressions or declarations, which allows nested functions and recursion at each level.

### Declarations and methods

`local function f(...) { ... }` creates a local function binding. The directly assigned form `local f = function(...) { ... }` receives the same recursive-function treatment. An unqualified declaration `function f(...) { ... }` writes the function to the global environment.

`function table.method(...) { ... }` assigns a function to an existing table member. The colon form `function table:method(...) { ... }` adds `self` as the first parameter. Use `function` for these qualified declarations, since `local` introduces a name in lexical scope.

A call `receiver:method(arguments)` passes the receiver as the first argument. A dot call passes only the written arguments. The table must already exist when its function declaration executes.

### Parameters and defaults

Parameters become local bindings for the invocation. A required parameter must receive a value. Each optional parameter has a default expression evaluated on calls where that argument is absent. An explicitly supplied `null` counts as a supplied argument.

Default expressions can use earlier parameters. A default container expression creates a fresh container each time it is evaluated. A final parameter written `rest...` gathers additional positional arguments into a list, including an empty list when there are none.

A call to a fixed-arity function rejects excess arguments. Missing required parameters, duplicate assignments to a parameter, and unknown named arguments also raise errors. These checks apply to the declared signature before the body executes.

### Closures

A function can retain values from enclosing lexical scopes. Each evaluation of a function expression creates the captures for that function instance. The captured-value behavior is described in [Variables, env, and scope](#3-variables-env-and-scope). Recursive calls use the function's self binding and receive a separate set of parameters and local registers.

## 7. Tables, lists, and methods

### Construction and indexing

A list literal is a sequence in brackets, such as `[10, 20, 30]`. A table literal contains key/value entries in braces, such as `{name='elx', version=1}`. Table keys can be written as identifiers, quoted strings, or bracketed expressions followed by `=`.

Lists use zero-based integer indices. Reading or replacing an element requires an index in `0` through `len(list)-1`. Assigning at `len(list)` appends one element. Other indices raise an error. Elements can hold different types, including `null`.

Tables store key/value pairs. `table.field` is shorthand for `table['field']`. A missing key reads as `null`, and an entry whose value is `null` still occupies a key. `sys.remove(table, key)` removes the entry. `len(table)` counts occupied entries. NaN is rejected as a key.

### Container operators

Container operations produce a new container while retaining references to nested values. `list * count` repeats the list for a nonnegative integer count.

| Operator | Lists                                                             | Tables                                                         |
|----------|-------------------------------------------------------------------|----------------------------------------------------------------|
| `+`      | Concatenate both sequences.                                       | Merge entries, with right-side values replacing matching keys. |
| `-`      | Keep left elements absent from the right list.                    | Keep left entries whose keys are absent from the right table.  |
| `&`      | Keep left elements present in the right list.                     | Keep left entries whose keys occur in the right table.         |
| `\|`     | Keep the left sequence, then right elements absent from the left. | Keep left entries, then right entries with new keys.           |
| `^`      | Keep elements exclusive to either list.                           | Keep entries with keys exclusive to either table.              |

List membership uses value equality. These operations preserve qualifying occurrences rather than deduplicating an input list.

### Metatables

A metatable is a table containing functions that customize another table's behavior. `setmetatable(value, meta)` attaches it, and `getmetatable(value)` retrieves it. Multiple values can use the same metatable.

| Hook                                                       | Arguments and role                                                                              |
|------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| `+`, `-`, `*`, `/`, `%`, `&`, `\|`, `^`, `<<`, `>>`, `>>>` | Binary operands in source order. Unary `+` and `-` use the corresponding hook with one operand. |
| `~`, `!`, `++`, `--`                                       | One operand.                                                                                    |
| `==`, `!=`, `<`, `<=`, `>`, `>=`                           | Two operands, returning a boolean.                                                              |
| `bool`                                                     | The table, returning a boolean for explicit conversion.                                         |
| `getattr`                                                  | The table and key, returning the selected value.                                                |
| `setattr`                                                  | The table, key, and assigned value.                                                             |
| `call`                                                     | The table followed by call arguments.                                                           |
| `init`                                                     | Reserved initialization hook name in the current runtime metadata.                              |
| `str`                                                      | The table, returning one string for formatting.                                                 |

Operator dispatch tries the left table's hook and then the right table's hook. A missing `!=` hook can use the result of `==` with inversion. Unary operations receive one argument, and predicate hooks must return a boolean.

Index hooks handle indexed operations even for keys that already exist. Use `getrawattr(table, key)` and `setrawattr(table, key, value)` inside these hooks to access storage directly. This also gives a hook an explicit way to delegate a read or write to the underlying table.

Hook fields accept functions or `null`. The runtime caches hook slots when a table becomes a metatable and updates the cache as those fields change.

## 8. Errors and protected calls

Compilation reports malformed source through `SyntaxException`, including source location information. Runtime errors unwind the current call and propagate to the caller. Java failures can carry a suppressed `ScriptTrace` containing script file names, functions, and line numbers.

`throw(value)` formats its argument and raises a Java `IllegalStateException`. `assert(condition, message)` accepts a boolean and an optional message. It returns the condition on success and throws `AssertionError` on failure.

`pcall(function)` invokes a function with zero arguments. On success it returns `true` followed by up to three of the function's results, keeping the total within the four-value result window. On a caught failure it returns `false` and a table with `cause` and `stacktrace` string fields.

The current implementation catches `RuntimeException` in `pcall`. Java `Error` subclasses, including the `AssertionError` raised by `assert`, propagate to the host. Code using protected calls should distinguish these two failure paths.

## 9. Modules

`require(name)` loads a module and returns its exported value. The VM caches successful loads by the requested name, so repeated requests return the same exported value and preserve its mutable state.

Resolution starts with built-in modules. For other names, the loader asks host resolvers in registration order, then searches the configured filesystem directory, and finally bundled resources under `/elx/`. It appends `.elx` when that suffix is absent. A resolver supplies source text, a diagnostic source name, and optionally a compile-time library.

A script module runs in the requesting VM and uses its global environment. Its first return value becomes the export. Returning zero values exports `true`, while explicitly returning `null` exports `null`. A cycle in active loads raises an error, and a failed load can be retried.

`sys.unload(name)` removes a cached module and releases its loader root. The next request loads it again. Existing references retain their own lifetimes, so previously exported values can remain usable. Hosts can configure the directory and resolvers through `VM.modules()`, as described in the [Java API reference](java-api.md#module-loading).

## 10. Standard library

Built-in functions are available by name. Standard modules are obtained with `require(name)`. The following signatures describe their accepted arguments and results.

| Name                                      | Purpose                                                                                        |
|-------------------------------------------|------------------------------------------------------------------------------------------------|
| `type`, numeric conversions, `str`, `len` | Inspect, convert, format, and measure values.                                                  |
| `assert`, `throw`, `pcall`                | Raise and protect runtime failures.                                                            |
| `range`                                   | Create a signed integer list from stop or start/stop/step arguments. The step must be nonzero. |
| `getrawattr`, `setrawattr`                | Read or write a table field without metamethod dispatch.                                       |
| `getmetatable`, `setmetatable`            | Read or replace a table metatable.                                                             |
| `referenceequal`                          | Compare managed references by identity.                                                        |
| `print`, `require`                        | Use the VM output sink and module loader.                                                      |

The host standard modules are created per VM:

| Module | Exports                                                                                       |
|--------|-----------------------------------------------------------------------------------------------|
| `math` | `abs`, `sqrt`, `floor`, `ceil`, `sin`, `cos`, `log`, `exp`, `pow`, `min`, `max`, `pi`, `inf`. |
| `str`  | `length`, UTF-8 `bytelen`, `upper`, `lower`, `sub`, `contains`.                               |
| `io`   | `stdout`, `print`, `read_text`, `write_text`, `exists`.                                       |
| `sys`  | `env`, `unload`, `remove`, `identity`. `env` is the same table exposed by the `env` keyword.  |
| `gc`   | `collect`, `pin`, `unpin`.                                                                    |
| `util` | `base64_encode`, `base64_decode`, `time`, `date`.                                             |

### Numeric and conversion functions

`type(value)` returns the source type name. Numeric conversions use the target type's name, including `byte`, `ubyte`, `short`, `ushort`, `int`, `uint`, `long`, `ulong`, `float`, and `double`. `bool(value)` requests boolean conversion. `str(value)` uses VM formatting, including table string hooks.

`range(stop)`, `range(start, stop)`, and `range(start, stop, step)` return a list of `long` integers. The default start is zero and the default step is one. The stop is exclusive. A negative step walks downward, and an already exhausted range produces an empty list. Arguments must fit a signed `long`.

### Standard module behavior

All unary `math` functions return `double`, including `floor`, `ceil`, and `abs`. `pow(x, y)` also returns `double`. `min(values...)` and `max(values...)` require at least one numeric argument and return `double`.

`str.length(text)` counts code points and `str.bytelen(text)` counts UTF-8 bytes. `str.sub(text, start, end)` uses zero-based code-point offsets with an exclusive end. Omitting the end selects the rest of the string. Invalid offsets raise an error. `upper` and `lower` use locale-independent case conversion, and `contains(text, part)` tests for a literal substring.

`io.stdout(values...)` concatenates formatted values. `io.print(values...)` adds tab separators and a platform line ending, matching the global `print`. Text files use UTF-8. `read_text(path)` returns the full file, `write_text(path, text)` creates or replaces its contents, and `exists(path)` returns a boolean. Relative paths use the Java process working directory. File errors propagate as unchecked I/O exceptions.

`sys.env` is the VM's global table. `sys.remove(table, key)` returns whether an entry was removed. `sys.identity(value)` returns its raw payload as a `long`, which is useful for runtime diagnostics. Its handle values belong to the current runtime instance.

`gc.collect()` requests a pressure collection. `gc.pin(pointer)` strongly retains a live non-stack pointer and returns that pointer. `gc.unpin(pointer)` releases one such retention. Pair these calls when retaining script pointers explicitly.

`util.base64_encode(text)` encodes UTF-8 bytes and `base64_decode(text)` decodes them back into text. `util.time()` returns epoch milliseconds as a `long`. `util.date()` formats the current instant, and `util.date(milliseconds)` formats the supplied instant as an ISO-8601 string.

## 11. Memory, pointers, and GC

Strings, containers, functions, and pointer handles live in the managed heap. The collector follows typed references from active calls, globals, loaded code and modules, and explicit roots. A numeric value remains a number even when its bits resemble an address.

The address operator `&location` creates a managed pointer to a writable location. `*pointer` reads that location, and assigning to it writes through the pointer. Locations include locals, captures, container entries, and globals. A container pointer follows its owner and key or index, allowing the container to resize while the pointer continues to select the entry.

Pointers to stack locations carry frame-lifetime checks. Their handles can survive a function return, but accessing a location whose frame has ended raises an error. Keeping a pointer handle alive preserves the handle and its reachable owners. The pointed-to location still obeys its own lifetime.

Use `gc.pin` for explicit script-side retention of a live non-stack pointer. Java callers use `Results.get(index)` or `Context.pin(name)` to acquire a `Pin<T>`. Both make ownership explicit, and the host releases the pin with `close()` when it has finished using the managed value.

The current collector keeps addresses stable. The pin contract also reserves the ability to support moving collection later. [Value lifetimes](java-api.md#value-lifetimes) explains the relationship between a context, returned results, and independently acquired pins.

## 12. Host integration

The Java embedding API has three main parts. `Compiler` turns source into a reusable `Prototype`. `Context` owns an executing runtime and returns `Results` from an execution. `Library` supplies named functions and constants that scripts can use.

Application callbacks implement `HostFunction` and receive an `Args` view of their inputs and outputs. Java objects implement `HostAccess` to expose ELX metamethods through the default external heap. `ExtHeap` remains available for low-level bridge customization. These are complementary extension points: a library exposes functions by name, while a host object exposes behavior on its own instances.

The [Java API reference](java-api.md) covers compilation options, returned-value conversion, pin ownership, host callbacks, external-object adapters, module resolution, and execution controls.

## 13. VM and bytecode

The compiler emits an immutable register-based `Prototype`. It stores instructions, constants, strings, nested functions, call-site metadata, and source line information. The linker resolves library symbols and assigns VM-local function handles.

O1 images use integrated operations where possible. O0 lowers them through the internal runtime ABI for backend development. Applications compile and load images through the public compiler and runtime APIs, which manage the corresponding VM-local bindings.

The interpreter maintains register windows, call frames, closure handles, result windows, an instruction budget, and GC safepoints. At most four ordinary result slots are returned by a call. Dynamic list expansion uses a separate dereference path. A prototype may be linked into more than one VM, and each VM assigns independent local addresses.

## Appendix A: grammar outline

The following outline introduces the main syntactic forms. The preceding sections define their behavior and the compiler handles their detailed token grammar:

```
chunk       ::= { statement separator } [ statement ]
statement   ::= local-declaration | native-declaration
              | function-declaration | assignment | expression
              | if-statement | while-statement | for-statement
              | return-statement | break | continue | print-statement
block       ::= '{' chunk '}'
function    ::= 'function' '(' parameters ')' [ '->' type ] block
method      ::= 'function' name ('.' | ':') name '(' parameters ')' block
local       ::= 'local' names [ '=' expressions ]
call        ::= expression '(' arguments ')'
```

Type annotations may be qualified, generic, or nullable with a trailing `?`. The parser retains them as source metadata. Runtime operations check the actual values used by each operation.

## Appendix B: compatibility notes

ELX uses a table-oriented model, first-class functions, closures, metatables, and `require`-style modules. It is a separate language with a different lexer, type system, memory model, and block syntax.

| Common convention                  | ELX rule                                               |
|------------------------------------|--------------------------------------------------------|
| Nil-like value                     | `null`                                                 |
| Keyword-delimited blocks           | Braced `{ ... }` blocks                                |
| Not-equal operator                 | `!=`                                                   |
| String concatenation               | `+` through string or metatable semantics              |
| One numeric family                 | Explicit signed, unsigned, `float`, and `double` types |
| Unmanaged pointer values           | Managed `ptr` handles and host-owned `external` values |
| Qualified local method declaration | Use `function table:method` or `function table.method` |

## Appendix C: implementation limits

- A normal call and return window contains at most four values.
- A function prototype uses at most 256 registers.
- The VM is thread-confined. Use one context per executing thread unless the host provides its own synchronization boundary.
- The module cache is per VM and detects cycles.
- Native declarations describe script-level signatures. The host supplies their implementations through a library.
- O0 is an internal lowering form and may change as the VM backend evolves.
