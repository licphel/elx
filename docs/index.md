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

Source is Unicode text. Identifiers are case-sensitive and may contain Unicode identifier characters, `_`, `$`, and `#`. Underscore-prefixed names are valid in ordinary source; hosts can still use them for internal symbols by convention.

The reserved keywords are:

```
local native function return if else while for in break continue
true false null void env print
```

Line comments begin with `//` and continue to the line ending. Block comments are enclosed by `/*` and `*/`. The lexer removes comments before parsing. A backslash immediately followed by a newline continues the current statement onto the next line. Otherwise, newlines and semicolons separate statements.

The preprocessor accepts line-start `#define`, `#undef`, `#include`, `#pragma once`, `#if`, `#ifdef`, `#ifndef`, `#elif`, `#else`, and `#endif` directives. An object-like macro replaces a name with its replacement tokens. A function-like macro has an opening parenthesis immediately after its name in the definition. A final variadic parameter receives the remaining arguments as a list. `defined(name)`, `FILENAME`, and `LINECOUNT` are available in preprocessor expressions. Includes use the compiler's `IncludeResolver`, retain source locations, reject recursive inclusion, and can be guarded with `#pragma once`. A bounded expansion depth and token budget limit recursive or explosive expansion.

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

Values have a concrete runtime type. Numbers, booleans, strings, lists, tables, functions, pointers, and external objects can be stored in variables, passed to functions, and returned to a caller. `null` is an explicit value. `void` represents a missing or unspecified value: an uninitialized declaration, an unresolved global, and a missing table key produce `void`.

| Category         | Types                                 | Meaning                                                 |
|------------------|---------------------------------------|---------------------------------------------------------|
| Boolean          | `bool`                                | Logical value. Conditions must have this type.          |
| Signed integer   | `byte`, `short`, `int`, `long`        | 8, 16, 32, or 64 bits.                                  |
| Unsigned integer | `ubyte`, `ushort`, `uint`, `ulong`    | Explicit unsigned interpretation at the selected width. |
| Floating point   | `float`, `double`                     | IEEE-754 single or double precision.                    |
| Absence          | `void`, `null`                        | `void` is missing/default; `null` is an explicit value.  |
| Managed value    | `string`, `list`, `table`, `function` | Heap-managed language values.                           |
| Managed pointer  | `ptr`                                 | Checked handle created by address operations.           |
| Host value       | `external`                            | Object owned by the host-side external heap.            |

Binary numeric operations widen both operands to a common type. Integer width is the maximum operand width. The result is unsigned when an operand at that width is unsigned. If either operand is floating point, the result is `float` for a 32-bit operation or `double` for a 64-bit operation.

Conversions are explicit. Arithmetic, conditions, and native typed reads require values of the appropriate type. Convert `void` explicitly before using a target type's default value. Integer narrowing keeps the target's low bits. Floating-point values converted to integers truncate toward zero, wrap to the target width, and reject non-finite values. Conversion functions are named after the target type, for example `int(value)`, `long(value)`, `float(value)`, and `double(value)`. Converting `void` to a number yields zero, and converting it to `bool` yields `false`. Typed conversion to `string` yields an empty string, while `str(void)` formats the value as the word `void`. Converting `void` to a list or table creates an empty container; conversion to reference types keeps it `void` rather than inventing a handle. `type(value)` returns the source type name.

Integer arithmetic keeps the result within the selected width by wrapping. Signed integer division truncates toward zero, and its remainder follows the dividend's sign. Integer division by zero raises an error. Floating-point operations follow IEEE-754 behavior. Shift counts are masked to the operand width, and `>>>` shifts in zero bits.

Strings are immutable Unicode text stored as UTF-8. `len(text)` counts Unicode code points, while `require('str').bytelen(text)` counts encoded bytes. These measures differ for non-ASCII text, and a visible character can contain several code points.

Assigning a list or table copies the reference to that container. Mutations through either variable affect the same container. Ordinary equality compares strings by content and containers structurally, with metatable hooks able to customize table comparisons. `referenceequal(a, b)` tests the underlying identity.

A type annotation documents the intended type of a local, parameter, or return value. The current parser accepts qualified names, generic arguments, and a trailing `?`. Runtime operations use the actual value tags, and annotations currently serve as metadata rather than a complete assignment-checking system.

## 3. Variables, `env`, and scope

`local` creates a binding in the current lexical scope. A braced block creates a nested scope. Function bodies create a new function scope and may capture bindings from their parents. A declaration without an initializer has type `void`.

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

A closure captures a copy of each referenced outer binding when it is created. Captured bindings are read-only: assignment, compound assignment, increment, decrement, and taking their writable address are compile-time errors. The enclosing function can still modify its own binding. To obtain mutable storage for each invocation, copy the capture into a local variable inside the closure, such as `local remaining = rep`.

Captured tables and lists still refer to the same containers. Their entries remain mutable, so closures can share state through a container. A pointer created in the owning scope can also be captured and dereferenced to access its original target.

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

A call may return up to sixteen values. Scalar expression positions use one value, while multiple declarations and returns can carry several. The prefix `*` also expands a list in calls, assignments, and returns. The destination determines how the resulting values are consumed.

Arguments can be positional, named with `name=value`, or expanded from a list using `*values`. `**options` expands named arguments from a table. Positional arguments precede named arguments. Argument expressions run in source order before the callee binds them to parameters. Supplying the same parameter twice or naming an unknown parameter raises an error.

## 5. Statements

Statements execute in source order. A statement ends at a separator, a closing brace, or the end of the script. Braced blocks introduce a scope and group a sequence of statements.

### Assignments

An assignment writes to a local, global, container entry, or dereferenced pointer. Compound forms combine an operation with storing its result, including arithmetic, bitwise, shift, and logical assignments. Use `x += amount` to update `x` from its current value. Logical assignments apply the corresponding short-circuit rule.

### Branches and loops

`if condition { ... }` executes its body when the boolean condition is true. An optional `else if` chain tests subsequent conditions, and `else` handles the remaining case. `while condition { ... }` tests before each iteration, so the body can execute zero times.

The C-style loop is `for initializer, condition, step { ... }`. The initializer runs once, the condition is checked before each iteration, and the step runs after the body. A binding introduced by the initializer belongs to the loop scope.

`continue` skips to the next iteration. In a C-style loop it runs the step before testing the condition again. `break` exits the innermost loop. Both statements require an enclosing loop.

The iterator form is `for names in iterable { ... }`. It evaluates `iterable` once, then calls the resulting zero-argument function for each step. Each call can yield several values; the loop assigns them to the listed names. When the first yielded value is `void`, iteration ends. Explicit `null` is an ordinary yielded value. A function that appends no results also ends iteration.

`itr(list)` returns a function that yields one list element per call. `itr(table)` yields a key and its value. Container traversal follows the current table storage order, which can change after insertion or resizing. There is no implicit index or ordinal. To use a list index, keep a separate counter in the loop. The lazy `range(stop)`, `range(start, stop)`, and `range(start, stop, step)` functions can be used directly as iterables.

### Returns and calls

`return` ends the current function or script. A bare return produces zero values, and comma-separated expressions produce several values. Reaching the end of a function also returns zero values. A call used as a statement executes for its effects and discards its results.

### Native declarations

At script scope, `native function host.log(message: string)` resolves `host.log` in the host-supplied compile-time library. A return annotation can follow `->`. `native pi: double;` binds a scalar or string definition. Missing definitions, function/value mismatches, and duplicate declarations are compilation errors, even when the declaration is unused.

Native references become symbol constants in the compiled image. The VM links them to its local handles; calling a native function performs no global or namespace-table lookup. Qualified names such as `host.log` are compile-time namespaces. Ordinary lexical bindings still take precedence. Parameter and return annotations describe the signature for tooling; the native implementation checks its actual call arguments.

## 6. Functions and closures

A function expression has the form `function(parameters) { body }`. Evaluating it produces a function value. Function bodies can contain further function expressions or declarations, which allows nested functions and recursion at each level.

### Declarations and methods

`local function f(...) { ... }` creates a local function binding. The directly assigned form `local f = function(...) { ... }` receives the same recursive-function treatment. An unqualified declaration `function f(...) { ... }` writes the function to the global environment.

`function table.method(...) { ... }` assigns a function to an existing table member. The colon form `function table:method(...) { ... }` adds `self` as the first parameter. Use `function` for these qualified declarations, since `local` introduces a name in lexical scope.

A call `receiver:method(arguments)` passes the receiver as the first argument. A dot call passes only the written arguments. The table must already exist when its function declaration executes.

### Decorators

A decorator wraps a function when its definition executes. `@wrap(2, mode='fast')` before a function declaration calls `wrap(originalFunction, 2, mode='fast')` and stores its first result in the declared binding. `@wrap` and `@wrap()` both pass only the original function. The wrapper's first actual argument is always the function being decorated.

Decorators use ordinary call binding. They support optional and variadic parameters, named arguments, `*values` list expansion, and `**options` named expansion. The implicit function argument comes before the explicit arguments. Positional arguments precede named arguments, and normal duplicate/missing-parameter checks apply. Wrapper names can select members, such as `@decorators.trace` or `@decorators['trace']`.

Decorator expressions and their arguments are evaluated once, from top to bottom. Wrapping then proceeds from bottom to top: `@outer @inner function f() { ... }` assigns `outer(inner(originalFunction))` to `f`. Calls to `f` execute the resulting wrapper. The original function keeps its usual self binding for recursion.

Decorators work on local and global declarations, table functions, methods, and function expressions:

```
function decora(fn, times) {
  return function(args...) {
    for i=0, i<times, i++ { fn(*args) }
  }
}

@decora(3)
@decora(2)
function printme(me) {
  print(me)
}

printme("I've been printed 6 times")
```

Here `printme` becomes `decora(decora(originalPrintme, 2), 3)`. Calling it forwards the supplied message through both wrappers and prints it six times. The returned wrapper controls argument forwarding and return values, just like an ordinary function.

The expression form is `local f = @wrap(arguments) function(parameters) { body }`. A decorated method still receives `self` when called with the colon syntax. Decorators use the regular function-call bytecode, so an identity decorator adds no per-call dispatch to the original function.

### Parameters and defaults

Parameters become local bindings for the invocation. Each optional parameter has a default expression evaluated on calls where that argument is absent. An explicitly supplied `null` counts as a supplied argument, while omitted or unspecified values use `void`.

Default expressions can use earlier parameters. A default container expression creates a fresh container each time it is evaluated. A final parameter written `rest...` gathers additional positional arguments into a list, including an empty list when there are none.

A call to a fixed-arity function rejects excess arguments. Missing required parameters, duplicate assignments to a parameter, and unknown named arguments also raise errors. These checks apply to the declared signature before the body executes.

### Closures

A function can retain values from enclosing lexical scopes. Each evaluation of a function expression creates the captures for that function instance. The captured-value behavior is described in [Variables, env, and scope](#3-variables-env-and-scope). Recursive calls use the function's self binding and receive a separate set of parameters and local registers.

## 7. Tables, lists, and methods

### Construction and indexing

A list literal is a sequence in brackets, such as `[10, 20, 30]`. A table literal contains key/value entries in braces, such as `{name='elx', version=1}`. Table keys can be written as identifiers, quoted strings, or bracketed expressions followed by `=`.

Lists use zero-based integer indices. Reading or replacing an element requires an index in `0` through `len(list)-1`. Assigning at `len(list)` appends one element. Other indices raise an error. Elements can hold different types, including `null` and `void`.

Tables store key/value pairs. `table.field` is shorthand for `table['field']`. A missing key reads as `void`; `table.has(table, key)` checks key presence directly, so an entry whose value is `void` or `null` still exists. `table.remove(table, key)` removes the entry and returns whether it was present. `len(table)` counts occupied entries. NaN is rejected as a key.

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

Metamethods are table fields named with lowercase words. For example, arithmetic uses `add` and `sub`; symbolic operator spellings such as `+` are not hook keys. The complete set is `add`, `sub`, `mul`, `div`, `mod`, `and`, `or`, `xor`, `shl`, `shr`, `ushr`, `pos`, `neg`, `inv`, `not`, `bool`, `eq`, `ne`, `lt`, `le`, `gt`, `ge`, `get`, `set`, `call`, `init`, `str`, `inc`, `dec`, `gc`, and `hash`.

Arithmetic and comparison hooks receive operands in source order. Unary hooks receive one operand; unary plus uses `pos` and unary minus uses `neg`. Comparison and boolean-conversion hooks return a boolean. `get` receives the table and key, while `set` receives the table, key, and assigned value. `call` receives the table followed by explicit arguments. `str` formats a value, `gc` handles collection of an external object, and `hash` implements the explicit semantic hash operation.

Operator dispatch tries the left table's hook and then the right table's hook. A missing `ne` hook can use the result of `eq` with inversion. Unary operations receive one argument, and predicate hooks must return a boolean. Explicit `hash(value)` honors the `hash` hook. Hashing keys for a table's own storage remains raw so a key's hash and equality do not change when script hooks run.

Index hooks handle indexed operations even for keys that already exist. Use `getrawattr(table, key)` and `setrawattr(table, key, value)` inside these hooks to access storage directly. This also gives a hook an explicit way to delegate a read or write to the underlying table.

Hook fields accept functions or `null`. The runtime caches hook slots when a table becomes a metatable and updates the cache as those fields change.

## 8. Errors and protected calls

Compilation reports malformed source through `SyntaxException`, including source location information. Runtime errors unwind the current call and propagate to the caller. Java failures can carry a suppressed `ScriptTrace` containing script file names, functions, and line numbers.

`throw(value)` formats its argument and raises a Java `RuntimeException`. `assert(condition[, message])` accepts a boolean condition. Passing `void` requires an explicit `bool(void)` conversion. It returns a true condition unchanged and raises `AssertionError` on false, using the optional ELX-formatted message or `assertion failed` by default.

`pcall(targetfn, handlerfn)` requires two callable values and invokes `targetfn` with no arguments. On success it returns `true` followed by up to fifteen of the target's results. If the target throws a `RuntimeException` or `AssertionError`, `pcall` builds an error table with `cause` and `stacktrace` strings, passes that table as the sole argument to `handlerfn`, and returns `false` followed by up to fifteen handler results. An error raised by the handler propagates to the caller. Other Java `Error` subclasses are not caught.

The two-value prefix (`true` or `false`) and up to fifteen forwarded values fit the sixteen-value result limit.

## 9. Modules

`require(name)` loads a module and returns its exported value. The VM caches successful loads by the requested name, so repeated requests return the same exported value and preserve its mutable state.

Resolution starts with built-in modules. For other names, the loader asks host resolvers in registration order, then searches the configured filesystem directory, and finally bundled resources under `/elx/`. It appends `.elx` when that suffix is absent. A resolver supplies source text, a diagnostic source name, and optionally a compile-time library.

A script module runs in the requesting VM and uses its global environment. Its first return value becomes the export. Returning zero values exports `true`, while explicitly returning `null` exports `null`. A cycle in active loads raises an error, and a failed load can be retried.

`sys.unload(name)` removes a cached module and releases its loader root. The next request loads it again. Existing references retain their own lifetimes, so previously exported values can remain usable. Hosts can configure the directory and resolvers through `VM.modules()`, as described in the [Java API reference](java-api.md#module-loading).

## 10. Standard library

Built-in functions are available by name. Standard modules are obtained with `require(name)`. The following signatures describe their accepted arguments and results.

| Name                                      | Purpose                                                                                        |
|-------------------------------------------|------------------------------------------------------------------------------------------------|
| `type`, numeric conversions, `str`, `len`, `hash`, `itr`, `range` | Inspect, convert, format, measure, hash, and iterate values. |
| `assert`, `throw`, `pcall`                | Raise and handle runtime failures.                                                             |
| `getrawattr`, `setrawattr`                | Read or write a table field without metamethod dispatch.                                       |
| `getmetatable`, `setmetatable`            | Read or replace a table metatable.                                                             |
| `referenceequal`                          | Compare managed references by identity.                                                        |
| `print`, `require`                        | Use the VM output sink and module loader.                                                      |

Standard modules are ELX scripts under `/std/`. Each declares its host primitives with `native` and assembles its public table from these bindings and script functions. The loader supplies the module's native definitions during compilation. Only the public script module is loaded and cached; native implementations are linked symbols with no separate runtime module table. Unloading a standard module releases both its image and its native-library loader roots. Values still referenced by scripts or pins remain alive.

| Module | Exports |
|--------|---------|
| `list` | `copy`, `deepCopy`, `sort`, `reverse`, `insert`, `removeAt`, `remove`, `pop`, `push`, `clear`, `slice`, `concat`, `append`, `prepend`, `indexOf`, `lastIndexOf`, `contains`, `count`, `unique`, `flatten`, `zip`, `chunk`, `swap`, `fill`, `equals`, `isEmpty`, `size`, `empty`, `repeat`, `range`, `sum`, `min`, `max`. |
| `table` | `create`, `get`, `set`, `remove`, `has`, `keys`, `values`, `entries`, `merge`, `mergeInto`, `clear`, `clone`, `inv`, `size`, `isEmpty`. |
| `math` | `abs`, `sqrt`, `floor`, `ceil`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `log`, `exp`, `to_degrees`, `to_radians`, `pow`, `fmod`, `modf`, `min`, `max`, `pi`, `inf`. |
| `str` | `charAt`, `codeAt`, `fromCode`, `toUpper`, `toLower`, `sub`, `trim`, `trimStart`, `trimEnd`, `contains`, `containsIgnoreCase`, `startsWith`, `endsWith`, `index`, `lastIndex`, `isSameIgnoreCase`, `replace`, `split`, `join`, `repeat`, `padStart`, `padEnd`, `format`. |
| `io` | `print`, `readText`, `writeText`, `appendText`, `exists`, `delete`, `listDir`. |
| `sys` | `env`, `unload`, `identity`. `env` is the same table exposed by the `env` keyword. |
| `gc` | `collect`, `pin`, `unpin`. |
| `util` | `encodeBase64`, `decodeBase64`, `time`, `date`, `genUuid`. |
| `random` | `seed`, `rand`, `randl`, `randf`, `randd`, `randb`. |
| `re` | `matches`, `find`, `findAll`, `replace`, `split`, `escape`; flags `caseInsensitive`, `multiline`, `dotAll`, `unicodeCase`. |
| `path` | `normalize`, `absolute`, `isAbsolute`, `cwd`, `join`, `relative`, `parent`, `name`, `stem`, `extension`, `separator`. |
| `json` | `parse`, `stringify`. |
| `time` | `millis`, `nanos`, `date`, `parse`, `sleep`. |
| `fmt` | `format`, `printf`, `interpolate`. |

### Numeric and conversion functions

`type(value)` returns the source type name. Numeric conversions use the target type's name, including `byte`, `ubyte`, `short`, `ushort`, `int`, `uint`, `long`, `ulong`, `float`, and `double`. `bool(value)` requests boolean conversion. `str(value)` uses VM formatting, including table string hooks. `hash(value)` requests the value's semantic hash operation. `itr(list)` and `itr(table)` adapt containers to function iterables.

`range(stop)`, `range(start, stop)`, and `range(start, stop, step)` return a lazy function iterable that yields `long` integers. The default start is zero and the default step is one. The stop is exclusive. A negative step walks downward, and an already exhausted range ends immediately. Arguments must fit a signed `long`.

### Standard module behavior

All unary `math` functions return `double`, including `floor`, `ceil`, and `abs`. `pow(x, y)` also returns `double`. `min(values...)` and `max(values...)` require at least one numeric argument and return `double`.

`str.charAt` and `str.codeAt` use zero-based Unicode code-point indices. `str.sub(text, start, end)` uses an exclusive end and defaults to the rest of the string. `toUpper` and `toLower` use locale-independent case conversion. `split` preserves trailing empty fields and requires a non-empty separator. `join(separator, values...)` joins formatted string arguments with the separator. `format` replaces each `{}` marker with the next value's ELX formatted representation.

`io.print(values...)` separates values with `, ` and appends a newline, matching the global `print`. Text files use UTF-8. `readText(path)` returns the full file, `writeText(path, text)` creates or replaces its contents, `appendText(path, text)` appends to a file, and `exists(path)` returns a boolean. `listDir(path)` returns entry names. Relative paths use the Java process working directory. File errors propagate as unchecked I/O exceptions.

`sys.env` is the VM's global table. `sys.identity(value)` returns its raw payload as a `long`, which is useful for runtime diagnostics. Its handle values belong to the current runtime instance. Table mutation and membership operations are provided by the `table` module.

`gc.collect()` requests a pressure collection. `gc.pin(pointer)` strongly retains a live non-stack pointer and returns that pointer. `gc.unpin(pointer)` releases one such retention. Pair these calls when retaining script pointers explicitly.

`util.encodeBase64(text)` encodes UTF-8 bytes and `decodeBase64(text)` decodes them back into text. `util.time()` returns epoch milliseconds as a `long`. `util.date()` formats the current instant, and `util.date(milliseconds)` formats the supplied instant as an ISO-8601 string. `genUuid()` returns a random UUID string.

`list` contains ordered, zero-based sequence operations. `sort` mutates its input and uses ELX `<` semantics, so mixed numeric types are promoted as they are in expressions and table `lt` hooks participate. `min` and `max` are written in ELX on top of those comparisons. `sum` starts at `0.0` and uses ordinary arithmetic promotion. `append(target, source)` and `prepend(target, source)` copy the source elements into the target. `slice(list, from, end)` uses an exclusive end. `range` also uses an exclusive stop.

`table` exposes raw key/value access. `get(table, key, fallback)` distinguishes an absent key from a key whose value is `null` or `void`; `has` reports presence. `merge(left, right)` creates a new table with right-hand entries taking precedence. `mergeInto(target, source)` mutates the target. `entries` returns a list of `{key, value}` tables. Table keys use the same raw equality and hashing rules as table indexing, without invoking value comparison or hash hooks. `list.range` remains an eager list constructor; the global `range` is lazy.

### Random numbers

Random generation belongs to `random`. Each VM owns its generator state. `seed(long)` resets its sequence, allowing scripts to reproduce a run. `rand(min, max)` returns an `int` and `randl(min, max)` returns a `long`; both bounds are inclusive, including the largest representable integer. `randf()` and `randd()` sample from [0, 1). Their two-argument forms sample [min, max), with finite bounds. Equal bounds return that value. `randb()` returns a boolean. Reversed bounds and a single floating-point bound raise an error.

### Paths and regular expressions

Path operations use the host platform's path syntax. `join(parts...)` resolves each part against the accumulated path and normalizes the result; an absolute part replaces the preceding path. `normalize(path)` removes redundant components, and `absolute(path)` also resolves against the working directory. `relative(base, target)` computes a relative path between compatible roots. `parent(path)` returns `void` when no parent exists. `name` returns the final component, `stem` removes its last extension, and `extension` includes the leading dot. A leading-dot filename such as `.profile` has an empty extension. These operations manipulate paths without accessing files.

Regular expressions use Java pattern syntax. All functions accept text first and pattern second. `matches(text, pattern, flags=0)` tests the whole string. `find(text, pattern, start=0, flags=0)` searches from a zero-based code-point offset and returns `void` when no match exists. A match is a table with code-point `start`, exclusive `end`, and a `groups` list: index zero holds the complete match, followed by captures. An unmatched optional capture is `null`.

`findAll(text, pattern, flags=0)` returns a list of match tables, including zero-width matches. `replace(text, pattern, replacement, flags=0)` replaces all matches and interprets Java replacement references such as `$1`. `split(text, pattern, flags=0)` preserves trailing empty fields. `escape(text)` produces a literal pattern. Combine flag constants with bitwise `|`.

### JSON

`json.parse(text)` parses one complete JSON value. Arrays become lists, objects become tables with string keys, and JSON null becomes explicit ELX `null`. Duplicate object keys keep the last value. Integers use `int`, `long`, or `ulong` according to their range; fractions and exponent forms use finite `double` values. Integer overflow and floating-point overflow raise an error. Unicode escapes support surrogate pairs. Errors include the input character offset.

`json.stringify(value)` returns compact JSON. It accepts booleans, numeric values, strings, null, lists, and tables with string keys. Object member order follows table traversal order. Shared acyclic containers are supported. Cycles, non-finite numbers, `void`, functions, pointers, and host objects raise an error. Both operations allow at most 128 nested container levels.

### Time and formatting

`time.millis()` returns epoch milliseconds. `time.nanos()` returns a monotonic nanosecond counter suitable for elapsed-time differences. Both return `long`. `date(milliseconds)` formats an ISO-8601 UTC instant, and its zero-argument form uses the current time. `parse(text)` converts an ISO instant to epoch milliseconds. `sleep(milliseconds)` blocks the VM thread; interruption preserves the thread's interrupted status and raises an error. The existing `util.time` and `util.date` names are ELX aliases for `time.millis` and `time.date`.

`fmt.format(template, values...)` uses locale-independent Java printf syntax, such as `%04d`, `%.2f`, and `%s`. Integral values remain integral, including unsigned 64-bit values. Other ELX containers use their normal string representation for `%s`. `fmt.printf` writes the formatted string to the VM output sink without appending a newline. `fmt.interpolate` aliases `str.format`, which uses sequential `{}` placeholders.

## 11. Memory, pointers, and GC

Strings, containers, functions, and pointer handles live in the managed heap. The collector follows typed references from active calls, globals, loaded code and modules, and explicit roots. A numeric value remains a number even when its bits resemble an address.

The address operator `&location` creates a managed pointer to a writable location. `*pointer` reads that location, and assigning to it writes through the pointer. Writable locations include locals, container entries, and globals. Captured bindings are read-only and cannot be addressed directly. A container pointer follows its owner and key or index, allowing the container to resize while the pointer continues to select the entry.

Pointers to stack locations carry frame-lifetime checks. Their handles can survive a function return, but accessing a location whose frame has ended raises an error. Keeping a pointer handle alive preserves the handle and its reachable owners. The pointed-to location still obeys its own lifetime.

Use `gc.pin` for explicit script-side retention of a live non-stack pointer. Java callers use `Results.get(index)` or `Context.pin(name)` to acquire a `Pin<T>`. Both make ownership explicit, and the host releases the pin with `close()` when it has finished using the managed value.

The current collector keeps addresses stable. The pin contract also reserves the ability to support moving collection later. [Value lifetimes](java-api.md#value-lifetimes) explains the relationship between a context, returned results, and independently acquired pins.

## 12. Host integration

The Java embedding API has three main parts. `Compiler` turns source into a reusable `Prototype`. `Context` owns an executing runtime and returns `Results` from an execution. `Library` supplies named functions and constants that scripts can use.

Application callbacks implement `HostFunction` and receive a borrowed `Call` view over indexed values. It provides the `Span` read/write operations, a separate append-only result window, and rooted scratch spans for temporary values. Java objects implement `HostAccess` and handle each `Metamethod` through one `elx_handle(Metamethod, Call)` method. `ExtHeap` remains available for low-level bridge customization. A library exposes functions by name, while a host object exposes behavior on its own instances.

The [Java API reference](java-api.md) covers compilation options, returned-value conversion, pin ownership, host callbacks, external-object adapters, module resolution, and execution controls.

## 13. VM and bytecode

The compiler emits an immutable register-based `Prototype`. It stores instructions, constants, strings, nested functions, call-site metadata, and source line information. The linker resolves library symbols and assigns VM-local function handles.

O1 is the implemented bytecode path. O0 is reserved as an optimization-level name but is currently unsupported; requesting it raises `UnsupportedOperationException`. Applications compile and load O1 images through the public compiler and runtime APIs, which manage VM-local bindings.

The interpreter maintains tagged register windows, call frames, closure handles, result windows, an instruction budget, and GC safepoints. The optimizer performs bounded constant, copy, liveness, loop-invariant, and instruction-fusion rewrites while preserving address-taken locals and hook-visible operations. A call reserves only the result slots its consumers need, up to sixteen values. A function-valued loop iterable is called once per iteration; its first `void` result ends the loop, while `null` remains a yieldable value. A prototype may be linked into more than one VM, and each VM assigns independent local addresses.

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
native-declaration ::= 'native' 'function' qualified-name '(' parameters ')' [ '->' type ]
                     | 'native' qualified-name [ ':' type ]
decorator   ::= '@' qualified-name [ '(' arguments ')' ]
decorated   ::= decorator { decorator } [ 'local' ] function-declaration
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

- A normal call and return window contains at most sixteen values.
- A function prototype uses at most 256 registers.
- The VM is thread-confined. Use one context per executing thread unless the host provides its own synchronization boundary.
- The module cache is per VM and detects cycles.
- Native declarations resolve host-library symbols during compilation. Their type annotations remain signature metadata.
- O0 is reserved but not implemented. O1 is the supported compiler output.
