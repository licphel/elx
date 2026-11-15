# ELX Language Manual

Reference draft, version 0.3.

ELX is a small embeddable language with first-class functions, separate list and table values, explicit numeric widths, managed pointers, and a register-based virtual machine. The implementation currently lives in Java, but the language contract does not depend on Java. Another host can implement the same compiler and VM and may produce different host exception classes or diagnostic wording without changing the source language.

This page is the language manual. It describes the syntax accepted by the compiler, the values produced by the VM, and the Java boundary used by the current implementation. The correctness suite under `src/test` is the executable reference for edge cases.

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
- [Appendix A: grammar outline](#appendix-a-grammar-outline)
- [Appendix B: compatibility notes](#appendix-b-compatibility-notes)
- [Appendix C: implementation limits](#appendix-c-implementation-limits)

## Design decisions

### Why create ELX instead of using Lua?

The first reason is the data model. Lua uses one table type for both associative objects and array-like lists. That is convenient for a small interpreter, but it makes a list's intended shape implicit and leaves many errors to runtime convention. ELX starts by making `list` and `table` different values with different operations.

The second reason is numeric accuracy. Lua's traditional numeric model is intentionally dynamic. ELX keeps a dynamic runtime where that is useful, but gives values exact source types: signed and unsigned integers have explicit widths, and `float` and `double` are separate. Conversions are visible in source and the VM does not silently reinterpret an arbitrary value as a condition.

ELX borrows useful ideas such as closures, metatables, and `require`, but it is not a Lua compatibility layer.

### Why are there no objects?

An object system would add syntax, construction rules, inheritance or delegation rules, and another identity model. ELX is intended for small configuration scripts and for distributing simple logic. A table already provides named fields, mutable state, methods, and metatable hooks, so an object syntax would add weight without solving a problem that this language needs to solve.

Use a table for data and attach functions to it when a method-like API is useful:

```elx
function counter:next(step: int = 1) -> int {
  self.value += step
  return self.value
}

local counter = {value=0}
return counter:next(), counter:next(2)
```

### Why are there pointers?

The language could force every mutable location into a one-element list or a table field. That works, but it is an awkward way to express “this function may update that value.” A managed pointer is the direct representation of that relationship.

ELX pointers are closer to a safe `ref` value than to an unchecked C address. The VM owns the allocation, tracks pointer handles, checks frame lifetime, and exposes explicit `gc.pin`/`gc.unpin` operations for values retained across host calls. A pointer does not expose a JVM or native address, and ordinary pointer use does not create a manual memory-free obligation.

### Why is `print` both a function and a statement?

One purpose of ELX is convenient console scripting. Requiring parentheses for a one-off result makes tiny scripts unnecessarily noisy. Both forms therefore work:

```elx
print 'answer', 6 * 7
print('answer', 6 * 7)
```

They use the same VM output sink and produce the same tab-separated line. The statement form is syntax sugar for a call to the host-provided `print` function.

### Why reserve moving-GC semantics?

The current collector uses stable allocation addresses and does not move live objects. The API is designed as if a future collector may compact or move them. That keeps raw addresses out of the language and makes the ownership boundary explicit now. Host code that retains a managed value must pin it; code that only uses a borrowed slot during a call must not retain the slot or its pointer.

### What is ELX's relationship with Java?

Java is the current implementation language, not a language requirement. The compiler emits a language-level `Prototype`, and the VM consumes that image through a defined slot and heap model. A native, Rust, or other host implementation can provide the same behavior. Host exception classes, file APIs, and diagnostic text may differ; those are integration details rather than ELX semantics.

### Why have macros?

The design principle is to give users the freedom to choose. `#define` is a small token-level preprocessor, including function-like and variadic macros. It is useful for generated configuration and repetitive source, but it can reduce readability and tooling quality. Projects that value maintainability more than source-level generation can simply prohibit macros in their source policy or compiler wrapper.

## Quick start

The smallest script is an expression followed by a return:

```elx
local function twice(value) {
  return value * 2
}

return twice(21)
```

Blocks use braces. Newline and semicolon tokens terminate statements. A backslash immediately before a newline continues the token stream. There is no `then`/`end` block pair.

Function declarations and assignments create the same function value:

```elx
local function declared(value) {
  return value + 1
}

local assigned = function(value) {
  return value + 1
}

return declared(4) == assigned(4)
```

Compile source with `io.viki.elx.compile.Compiler` and execute the resulting `Prototype` through a `VM`. `Optimization.O1` keeps integrated operations where possible; `Optimization.O0` lowers them through the internal runtime ABI. Both forms consume the same source language.

## 1. Lexical conventions

Source is Unicode text. Identifiers are case-sensitive and may contain Unicode identifier characters, `_`, `$`, and `#`. Names beginning with `_` are reserved for host-internal source when normal compilation is used.

The reserved keywords are:

```text
local native function return if else while for in break continue
true false null env print
```

Line comments begin with `//`; block comments use `/* ... */`. Both are removed before parsing. A backslash followed by a newline is a continuation, not a separator. Newline and semicolon otherwise produce separator tokens.

The preprocessor accepts a line-start `#define`. An object-like macro replaces a name; a function-like macro requires the opening parenthesis immediately after its name. A final variadic parameter receives the remaining arguments as a list. `FILENAME` and `LINECOUNT` are built-in read-only macros. Macro expansion is bounded so recursive definitions cannot grow without limit.

String literals use single or double quotes. Supported escapes are `\n`, `\r`, `\t`, `\0`, escaped quotes and backslashes, and four-digit `\uFFFF` escapes. A newline cannot occur inside a string literal.

Decimal integers use the `int` type by default. Binary and hexadecimal literals use the `b'` and `h'` prefixes. A single apostrophe may separate digits. Suffixes select the exact type:

| Suffix | Type |
| --- | --- |
| *(none)* | `int` |
| `b` | `ubyte` |
| `s` | `short` |
| `us` | `ushort` |
| `u` | `uint` |
| `l` | `long` |
| `ul` | `ulong` |
| `f` | `float` |
| `d` | `double` |

A decimal fraction or exponent without a suffix is a `double`. A leading sign is an operator, so `-1` is parsed as unary negation applied to the literal `1`.

## 2. Values and types

Every VM slot contains a type tag and a 64-bit payload. `Type.INVALID` is an internal compiler marker and is not a source value. `null` is the only null value.

| Category | Types | Meaning |
| --- | --- | --- |
| Boolean | `bool` | Logical value. Conditions must have this type. |
| Signed integer | `byte`, `short`, `int`, `long` | 8, 16, 32, or 64 bits. |
| Unsigned integer | `ubyte`, `ushort`, `uint`, `ulong` | Explicit unsigned interpretation at the selected width. |
| Floating point | `float`, `double` | IEEE-754 single or double precision. |
| Managed value | `string`, `list`, `table`, `function` | Heap-managed language values. |
| Managed pointer | `ptr` | Checked handle created by address operations. |
| Host value | `external` | Object owned by the host-side external heap. |

Binary numeric operations widen both operands to a common type. Integer width is the maximum operand width; the result is unsigned when an operand at that width is unsigned. If either operand is floating point, the result is `float` for a 32-bit operation or `double` for a 64-bit operation.

Conversions are explicit. Integer narrowing keeps the target's low bits. Floating-point values converted to integers truncate toward zero, wrap to the target width, and reject non-finite values. Conversion functions are named after the target type, for example `int(value)`, `long(value)`, `float(value)`, and `double(value)`. `str(value)` formats a value through the VM formatter, and `type(value)` returns its source type name.

Reference identity can be tested with `referenceequal(a, b)`. Lists and tables are mutable containers. Strings and functions are managed values, but their contents and callable behavior are defined by their corresponding runtime operations.

## 3. Variables, `env`, and scope

`local` creates a binding in the current lexical scope. A braced block creates a nested scope. Function bodies create a new function scope and may capture bindings from their parents. A declaration without an initializer assigns `null`.

An unresolved ordinary name is a key in the VM's global environment table. The keyword `env` is the table itself:

```elx
env.answer = 41
env.answer += 1
return env.answer, answer
```

Both returned values are `42`. `env.answer` writes the same slot that an unresolved assignment `answer = 42` would write. `env` is a reserved keyword, so it cannot be declared as a local or shadowed by a global. It can be passed to a function, indexed, mutated, and stored in another table. `require('sys').env` remains an alias for the same table for compatibility with host-facing code.

Global entries belong to a VM instance. They persist across executions in that VM and are not shared with another VM unless the host explicitly copies values between them.

Closures capture the bindings they use:

```elx
local seed = 2
local function makeAdder(amount) {
  local value = seed
  return function(input) {
    return value + amount + input
  }
}

local add = makeAdder(3)
return add(4)
```

Named local functions are bound before their bodies are compiled, so recursion works naturally:

```elx
local function factorial(n) {
  if n < 2 { return 1 }
  return n * factorial(n - 1)
}
```

For multiple values, declare multiple names. A single local declaration is scalar and selects the first value:

```elx
local function pair(x) { return x, x * 2 }
local first, second = pair(4)
local values = [first, second]
return values
```

## 4. Expressions

Expressions include literals, names, function expressions, list and table literals, calls, indexing, member access, unary operators, binary operators, and assignments used as statements.

The binary precedence is listed from low to high:

| Level | Operators |
| --- | --- |
| 1 | `\|\|` |
| 2 | `&&` |
| 3 | `\|` |
| 4 | `^` |
| 5 | `&` |
| 6 | `==`, `!=` |
| 7 | `<`, `<=`, `>`, `>=` |
| 8 | `<<`, `>>`, `>>>` |
| 9 | `+`, `-` |
| 10 | `*`, `/`, `%` |

Postfix access, calls, colon calls, and postfix `++`/`--` bind after binary operators. Prefix `+`, `-`, `!`, `~`, address-of `&`, dereference `*`, and prefix `++`/`--` bind before them.

Logical `&&` and `||` short-circuit and produce `bool`. Bitwise operators are eager and require integer values. String addition concatenates formatted values when at least one operand is a string; tables can override operations with metatable hooks.

A normal call returns at most four values. A list can be expanded with `*list` in an argument list, assignment, or return. Named arguments use `name=value`; `**table` supplies named arguments from a table. Positional arguments must precede named arguments, and a parameter can be supplied only once.

```elx
local function f(a, b=2, rest...) {
  return a + b + len(rest)
}

return f(*[3, 8, 9], b=4)
```

## 5. Statements

Declarations and assignments are statements. A statement ends at a separator, a closing brace, or end of input. Blocks always use braces.

```elx
if value > 0 {
  print(value)
} else if value == 0 {
  print 'zero'
} else {
  print(-value)
}

while value > 0 {
  value--
}

for i=0, i<10, i++ {
  if i == 5 { continue }
  if i == 8 { break }
}
```

`for` has a C-style form with initializer, condition, and step, and an iterator form:

```elx
for index, value in source {
  print(index, value)
}
```

Iteration supports one to three loop bindings. `return` may return zero or more values. `break` and `continue` require an enclosing loop. Compound assignment includes arithmetic, bitwise, shifts, and logical forms such as `+=`, `&=`, and `&&=`.

Native declarations reserve a script-level signature without emitting a body. They are accepted only at the outermost script scope and are useful to editors or host tooling:

```elx
native function host.log(message: string)
native function host.read(name: string) -> string
```

## 6. Functions and closures

A function expression is `function(parameters) { body }`. Parameters may have type annotations and defaults, followed by one final variadic parameter written `values...`. The variadic parameter receives a list.

The following forms are assignment sugar:

```elx
local function add(a, b) { return a + b }
local addAgain = function(a, b) { return a + b }
```

A global declaration writes the named function into `env`. A qualified declaration writes a table member:

```elx
function mathBox.add(a, b) { return a + b }

function counter:next(step: int = 1) -> int {
  self.value += step
  return self.value
}
```

`function table:method` adds the receiver as the first argument and colon calls supply it automatically. `function table.method` does not add a receiver. `local function table:method` is rejected; `local` applies only to an unqualified local binding.

Each call receives a register window. Closures capture the bindings needed by their body. Default expressions are evaluated when the argument is absent, and an explicitly supplied `null` remains present rather than selecting the default.

## 7. Tables, lists, and methods

Tables are mutable key/value containers. Table literals contain identifier fields, bracketed keys, or quoted string keys, each followed by `=`:

```elx
local record = {
  name = 'elx',
  version = 1,
  ['enabled'] = true,
  'build' = 3
}

local values = [10, 20, 30]
record.version += 1
values[1] = record.version
```

The quoted key form is equivalent to an explicit string key. Lists are ordered and use list operations; tables do not silently become lists.

`table.field` is member access using a string key. `receiver:method(args)` reads `receiver["method"]` and passes the receiver as the first argument. A dot call leaves the argument list unchanged.

Metatables provide hooks for arithmetic, comparison, boolean conversion, calls, indexing, string conversion, increment/decrement, and length. Hook names are the operator spellings or the names `bool`, `getattr`, `setattr`, `call`, `init`, and `str`.

```elx
local meta = {
  ['+'] = function(left, right) { return left.value + right },
  str = function(value) { return 'value=' + str(value.value) }
}
local box = setmetatable({value=3}, meta)
return box + 4
```

`getrawattr` and `setrawattr` bypass hooks. `getmetatable` and `setmetatable` access the metatable itself. `referenceequal` compares the raw managed identity rather than invoking a value hook.

## 8. Errors and protected calls

Malformed source produces `SyntaxException` with a source span containing the file name, line, and column. Runtime failures carry a VM source trace when one is available. A failing operation stops the current call chain unless it is inside `pcall`.

```elx
local ok, error = pcall(function() {
  throw('failure')
})

assert(!ok)
print(error.cause)
```

`assert(condition, optionalMessage)` requires a boolean and raises an assertion error when it is false. `throw(value)` raises an `IllegalStateException` whose formatted value becomes the message. `pcall(function)` returns `true` followed by the function results on success, or `false` and an error table on failure. The error table contains `cause` and `stacktrace` string fields.

## 9. Modules

`require(name)` resolves a module through registered resolvers, the configured filesystem directory, or bundled resources. A successful module is compiled, linked, executed once, cached per VM, and returned on later requests.

```elx
local math = require('math')
local strings = require('str')
return math.pow(2, 8), strings.length('ELX')
```

Files are resolved relative to the VM module directory. `.elx` is appended when the name has no suffix. Bundled resources are searched after the filesystem. A module with no return value exports `true`; otherwise its first returned value becomes the export. Cyclic loading is rejected.

The host can register a `ModuleResolver` before loading a module:

```java
context.vm().modules().resolver(name -> {
  if (!name.equals("config:defaults")) return null;
  return new ModuleResolver.Source(name, "return {retries=3}");
});
```

`sys.unload(name)` and `VM.modules().unload(name)` release the module cache entry. Existing values and function handles can keep the underlying image alive until the VM no longer sees them.

## 10. Standard library

The immutable built-in library is shared by compiler instances.

| Name | Purpose |
| --- | --- |
| `type`, numeric conversions, `str`, `len` | Inspect, convert, format, and measure values. |
| `assert`, `throw`, `pcall` | Raise and protect runtime failures. |
| `range` | Create a signed integer list from stop or start/stop/step arguments. A zero step is an error. |
| `getrawattr`, `setrawattr` | Read or write a table field without metamethod dispatch. |
| `getmetatable`, `setmetatable` | Read or replace a table metatable. |
| `referenceequal` | Compare managed references by identity. |
| `print`, `require` | Use the VM output sink and module loader. |

The host standard modules are created per VM:

| Module | Exports |
| --- | --- |
| `math` | `abs`, `sqrt`, `floor`, `ceil`, `sin`, `cos`, `log`, `exp`, `pow`, `min`, `max`, `pi`, `inf`. |
| `str` | `length`, UTF-8 `bytelen`, `upper`, `lower`, `sub`, `contains`. |
| `io` | `stdout`, `print`, `read_text`, `write_text`, `exists`. |
| `sys` | `env`, `unload`, `remove`, `identity`. `env` is the same table exposed by the `env` keyword. |
| `gc` | `collect`, `pin`, `unpin`. |
| `util` | `base64_encode`, `base64_decode`, `time`, `date`. |

## 11. Memory, pointers, and GC

Strings, lists, tables, functions, and pointer handles live in the managed heap. The collector traces typed slots; an ordinary integer is never treated as a heap root. Internal allocations currently use stable addresses, but source code never relies on that fact.

The address operator creates a managed pointer to an assignable location. Dereferencing reads or writes through that handle:

```elx
local value = 3
local pointer = &value
*pointer += 4
return value
```

Pointers can address locals, captured bindings, list elements, table fields, globals, and function values. Pointers to locals and captured bindings are checked against frame lifetime. A pointer to a returned stack slot cannot be safely dereferenced after its frame ends.

`gc.pin(pointer)` retains a live non-stack pointer. `gc.unpin(pointer)` releases that retention. Pin before Java code stores a managed handle for later use. Do not retain a borrowed `Args` window or a temporary slot address.

There is no numeric `ptr(...)` conversion. Pointer creation is explicit and remains subject to managed ownership checks.

## 12. Host integration

### The normal `Context` boundary

`Context` owns one thread-confined VM, its managed heap, register storage, and external-object heap. It is the easiest way to embed ELX:

```java
import io.viki.elx.Context;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;

try (Context context = Context.open()) {
  Compiler compiler = new Compiler();
  Prototype image = compiler.compile("return 6 * 7");
  int count = context.vm().execute(image);
  long results = context.vm().results();
  System.out.println(count + " result(s): " + context.vm().format(results));
}
```

`VM.results()` returns a borrowed slot window. It remains valid until another execution at the same invocation depth overwrites it or the VM closes. Copy or pin anything that must survive that boundary.

### Registering a host function

`SimpleLibrary` stores source-visible symbols. A `HostFunction` receives an `Args` view that is borrowed for one call:

```java
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.SimpleLibrary;

SimpleLibrary library = new SimpleLibrary()
    .function("add", args -> {
      args.arity(2);
      args.returnLong(Math.addExact(args.i(0), args.i(1)));
    })
    .function("announce", args -> {
      args.returnString("received: " + args.join(", "));
    })
    .freeze();

Prototype image = new Compiler().compile(
    "return add(2, 3), announce('elx')", library);
int count = context.vm().execute(image);
```

`Args` provides `i`, `d`, `b`, `s`, `t`, `l`, `pointer`, and `o` accessors. Use `typeof(index)` when dispatch depends on a type. Use `returnInt`, `returnLong`, `returnDouble`, `returnBoolean`, `returnString`, `returnObject`, or `returnValue` to append results. A callback may return at most four values. It may re-enter the same VM, but it must not retain the `Args` object or its slot addresses.

If a callback retains ELX pointers in its own state, implement `HostFunction.trace(HeapVisitor)` so the GC can visit those roots. The callback and the VM remain thread-confined.

### Exposing Java objects with `ExtHeap`

`external` values are implemented by a subclass of `ExtHeap`. The subclass decides how a member name is read, written, or invoked; ELX does not require reflection.

The following minimal adapter exposes `Map<String, Object>` fields:

```java
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.ExtHeap;

import java.util.Map;

final class MapHeap extends ExtHeap {
  MapHeap(ElxHeap internal) {
    super(internal);
  }

  @Override
  protected void readField(Object target, long memberName, long result) {
    Map<?, ?> map = (Map<?, ?>) target;
    importJava(result, map.get(memberName(memberName)));
  }

  @SuppressWarnings("unchecked")
  @Override
  protected void writeField(Object target, long memberName, long value) {
    Map<String, Object> map = (Map<String, Object>) target;
    map.put(memberName(memberName), exportValue(value));
  }

  @Override
  protected int invoke(Object target, long memberName, long args,
                       int argCount, long results, int resultCapacity) {
    throw new UnsupportedOperationException("Map values are not callable");
  }
}
```

Install the adapter when opening the context:

```java
Map<String, Object> config = new HashMap<>();
config.put("name", "demo");

try (Context context = Context.open(MapHeap::new)) {
  long slot = context.vm().heap().arena().allocate(Slot.SIZE);
  try {
    context.vm().external().importJava(slot, config);
    context.vm().setGlobal("config", slot);
    Prototype image = new Compiler().compile(
        "config.name = config.name + '-elx'; return config.name");
    context.vm().execute(image);
  } finally {
    context.vm().heap().arena().free(slot);
  }
}
```

`ExtHeap.importJava` converts Java primitives and strings directly and wraps other objects in external handles. `exportValue` performs the reverse conversion. Override `traceObject` when a host object stores ELX handles that the collector cannot discover by itself, and override `disposeObject` when dropping a handle must close an external resource.

### Pinning values for Java

`Context.pin(name)` roots a named global until the returned `Pin` is closed:

```java
try (var text = context.pin("result")) {
  System.out.println(text.get());
}
```

Primitive values are copied. Managed strings are exported as Java strings. Other managed references are represented by a rooted handle whose lifetime is controlled by the `Pin`. External values use the configured `ExtHeap` pinning rules.

## 13. VM and bytecode

The compiler emits an immutable register-based `Prototype`. It stores instructions, constants, strings, nested functions, call-site metadata, and source line information. The linker resolves library symbols and assigns VM-local function handles.

O1 images use integrated operations where possible. O0 lowers them through the internal runtime ABI. O0 is an implementation form, not a source-level assembly language; host code should use `Compiler`, `Prototype`, `Library`, and `VM` rather than depending on internal ABI names.

The interpreter maintains register windows, call frames, closure handles, result windows, an instruction budget, and GC safepoints. At most four ordinary result slots are returned by a call. Dynamic list expansion uses a separate dereference path. A prototype may be linked into more than one VM, and each VM assigns independent local addresses.

## Appendix A: grammar outline

This is a readable outline, not a parser-generator grammar:

```text
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

Type annotations may be qualified, generic, or nullable with a trailing `?`. They are parsed and retained as source metadata; the current VM does not insert runtime checks from them.

## Appendix B: compatibility notes

ELX uses a table-oriented model, first-class functions, closures, metatables, and `require`-style modules. It is a separate language with a different lexer, type system, memory model, and block syntax.

| Common convention | ELX rule |
| --- | --- |
| Nil-like value | `null` |
| Keyword-delimited blocks | Braced `{ ... }` blocks |
| Not-equal operator | `!=` |
| String concatenation | `+` through string or metatable semantics |
| One numeric family | Explicit signed, unsigned, `float`, and `double` types |
| Unmanaged pointer values | Managed `ptr` handles and host-owned `external` values |
| Qualified local method declaration | Rejected; use `function table:method` or `function table.method` |

## Appendix C: implementation limits

- A normal call and return window contains at most four values.
- A function prototype uses at most 256 registers.
- The VM is thread-confined. Use one context per executing thread unless the host provides its own synchronization boundary.
- The module cache is per VM and detects cycles.
- Native declarations are script-level metadata and do not create an implementation automatically.
- O0 is an internal lowering form and may change as the VM backend evolves.
