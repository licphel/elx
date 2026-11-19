---
title: ELX Java API
---

# ELX Java API

The Java API lets an application compile scripts, run them in an isolated runtime, and supply application-specific behavior. Start with `Compiler` and `Context`. Use the lower-level VM and slot interfaces when implementing adapters or working with managed containers.

[Language manual](index.md)

## Contents

- [Running a script and reading its results](#running-a-script-and-reading-its-results)
- [Runtime ownership](#runtime-ownership)
- [Compilation](#compilation)
- [Results](#results)
- [Value lifetimes](#value-lifetimes)
- [Java value conversion and null](#java-value-conversion-and-null)
- [Globals and arguments](#globals-and-arguments)
- [Host functions and libraries](#host-functions-and-libraries)
- [External object adapters](#external-object-adapters)
- [Module loading](#module-loading)
- [Execution controls and diagnostics](#execution-controls-and-diagnostics)
- [API map](#api-map)

## Running a script and reading its results

Compile the source into a `Prototype`, execute it with a `Context`, and read each returned value through a `Pin<T>`. The context, returned results, and acquired pins each have their own ownership. Try-with-resources closes them in the reverse order of their declaration.

```
import io.viki.elx.Context;
import io.viki.elx.Results;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.memory.Pin;

Prototype image = new Compiler().compile("return 42, 'hello'");
try (Context context = Context.open();
     Results results = context.execute(image);
     Pin<Long> number = results.get(0);
     Pin<String> text = results.get(1)) {
  System.out.println(number.get());
  System.out.println(text.get());
}
```

This prints `42` and `hello`. ELX integer values export as Java `Long`, including a script literal whose ELX type is `int`. `results.type(index)` reports the original ELX type when that distinction matters.

`Pin.get()` exposes the Java representation of the captured value. Explicit ELX `null` becomes Java `null`; ELX `void` becomes `VoidValue.INSTANCE`. Code that needs a primitive Java value checks or otherwise establishes the result's type before unboxing it.

## Runtime ownership

`Context.open()` constructs one VM with managed memory, register storage, and an external-object heap. Each context has its own globals, module cache, and linked function handles. A context is confined to its executing thread. Host callbacks may re-enter that same context synchronously.

`Context.open()` installs the default external heap. Java objects that implement `HostAccess` are dispatched automatically, so the ordinary path needs no heap subclass. `Context.open(factory)` remains available for low-level `ExtHeap` extensions that need to control handle storage or override the raw bridge.

`Context.close()` closes the VM, register storage, external heap, managed heap, and memory. Heap-backed pins become invalid when their owning context closes. Release results and pins as soon as their values are no longer needed, and close the context after the application's last runtime operation.

Applications using the low-level `VM(ElxHeap, ExtHeap, JavaRegs)` constructor own those supporting resources themselves. `Context` provides the coordinated ownership for the common case.

## Compilation

`Compiler.compile(source)` returns a script `Prototype` using the default O1 optimizer. Compilation creates code and metadata, and execution instantiates the script's values in a particular VM. Reuse a prototype for repeated execution instead of recompiling the same source for each call.

| Entry point                                    | Use                                                                           |
|------------------------------------------------|-------------------------------------------------------------------------------|
| `compile(source)`                              | Compile source with built-ins and an empty application library.               |
| `compile(source, library)`                     | Also resolve names from an application library.                               |
| `compile(sourceName, source, library)`         | Retain a meaningful file or resource name for diagnostics.                    |
| `compile(sourceName, source, library, level)`  | Request an optimization level; currently only O1 is implemented.              |
| `compileFunction(source, library)`             | Compile a single function expression for direct invocation.                   |
| `compileRaw(sourceName, source, library)`      | Inspect the compiler's direct instruction output.                             |
| `compileInternal(sourceName, source, library)` | Compile optimized host-generated glue that may use underscore-prefixed names. |

A prototype carries instructions, constants, nested function prototypes, signature information, and source locations. It can be loaded into multiple VMs. Each VM gives loaded code its own handles and addresses.

O1 is the supported execution form. Requesting O0 throws `UnsupportedOperationException`; the level remains reserved for future work. `compileRaw` returns direct O1 instruction selection without the optimization pass, which is useful for tooling and differential checks.

The compiler resolves local and captured names first, then application-library symbols and built-ins, and finally unresolved names through the global environment. Consequently, adding an entry to `env` changes global lookups, while a name already bound to a library symbol keeps its compiled binding.

The VM and host-library paths share one semantic facade, `ValueOperations`. Its arithmetic methods accept the bytecode `Op` directly, and comparisons accept `Instr.Relation`; there is no second unary/binary operation enum to translate at the host boundary. Numeric fast paths and the general fallback use the same promotion, NaN, `void`, string, container, and metamethod rules. Named and spread calls carry a `CallSite.resultCapacity` from zero through sixteen, so the compiler and VM can avoid materializing unused return slots.

## Results

`Context.execute(image)` returns a caller-owned `Results` object. Its indexed values are a snapshot of that invocation's return slots, retained independently of the VM's temporary return window. Later execution and collection leave those slots available until the results object is closed.

| Method        | Behavior                                                                                            |
|---------------|-----------------------------------------------------------------------------------------------------|
| `size()`      | Return the captured number of values, from zero through sixteen. Available after close as metadata. |
| `type(index)` | Read the captured ELX type at a zero-based index.                                                   |
| `get(index)`  | Acquire a new, independently owned `Pin<T>` for that value.                                         |
| `close()`     | Release the snapshot's roots. Repeated calls are harmless.                                          |

A bare `return` has size zero. `return null` has size one and type `Type.NULL`; a returned `void` has type `Type.VOID`. Reading an index outside `0` through `size()-1` raises `IndexOutOfBoundsException`. Indexed access after closing results or their context raises `IllegalStateException`.

`get(index)` creates a fresh pin on each call. A caller that keeps two pins owns and closes both. Closing `Results` leaves already acquired pins alive, so an application can retain selected values and promptly release the rest of a result set.

The snapshot copies values and references, rather than recursively copying containers. Mutating a returned table or list through another reference changes the same container. Capturing results takes O(n) time and storage for n return values. Indexed access and pin acquisition take constant time. Exporting a string through `Pin.get()` decodes its contents and takes time proportional to the string length.

The generic `T` describes the Java representation expected by the caller. It provides compile-time convenience through an unchecked boundary. It performs no numeric conversion and does not validate that a chosen Java class matches the script result. Inspect `type(index)` when the result type depends on script input.

The `Context.execute(image, args, count)` and `execute(function, args, count)` overloads accept a contiguous window of input slots. The latter calls an already loaded VM-local function handle. Both return the same owned results representation.

## Value lifetimes

A result snapshot retains all of its returned managed values. Each pin acquired from it retains its selected value separately. A managed value remains reachable while at least one applicable owner retains it, subject to the owning context staying open.

| Owner or view                | Lifetime                                                                            |
|------------------------------|-------------------------------------------------------------------------------------|
| `Results`                    | Snapshot slots and their reachable values remain rooted until `close()`.            |
| Managed or external `Pin<T>` | Its allocation remains strongly pinned until that pin closes or its context closes. |
| `Pin.Primitive<T>`           | An independently copied Java value. Closing it has no effect on its contents.       |
| `VM.results()`               | Borrowed slot window, overwritten by the next invocation at the same depth.         |
| Callback `Call` and `Span`   | Borrowed inputs, rooted scratch slots, and results for that callback invocation.    |

`Pin.get()` exports the current representation. `isAlive()` checks the pin's liveness, `isPrimitive()` identifies independently copied values, and `close()` releases the hold. `unpin()` is an alias for `close()`.

A closed heap-backed pin throws on access. A primitive pin remains readable because its value is already independent of the runtime. These behaviors also apply to primitive pins containing `null`.

A pin to a string roots the managed string and exports a Java `String` on access. The exported Java string has its ordinary Java lifetime. Pins for lists, tables, functions, and managed pointers expose VM-local `Long` handles. Use the corresponding value or VM operations while holding the pin to work with those handles.

Pinning preserves an allocation's lifetime. A pointer to a stack variable still carries the lifetime of that stack frame, so retaining its handle does not extend the variable's lifetime after the frame returns.

The low-level heap supports `PinMode.STRONG`, `WEAK`, and `NEVER`. Strong pins retain allocations through collection. Weak pins retain them through normal collection but allow reclamation under pressure. Observation pins use `NEVER` and track liveness without retaining an allocation. `Results.get` and `Context.pin` use strong pins for host ownership.

## Java value conversion and null

`ExtHeap.importJava(destination, value)` writes a Java value into an ELX slot. `exportValue(address)` reads a typed slot and converts it back to Java.

| Java input                         | ELX type                     | Java output through slot export or a result pin |
|------------------------------------|------------------------------|-------------------------------------------------|
| `VoidValue.INSTANCE`               | `VOID`                       | `VoidValue.INSTANCE`                            |
| `null`                             | `NULL`                       | `null`                                          |
| `Boolean`                          | `BOOL`                       | `Boolean`                                       |
| `Byte`, `Short`, `Integer`, `Long` | Matching signed integer type | `Long`                                          |
| `Float`                            | `FLOAT`                      | `Float`                                         |
| `Double`                           | `DOUBLE`                     | `Double`                                        |
| `String`                           | `STRING`                     | `String`                                        |
| Other non-null object              | `EXTERNAL`                   | The original object through the external heap.  |

Unsigned ELX integers also export as `Long`. Values fitting the positive Java range keep that numeric value. An `ulong` above `Long.MAX_VALUE` retains its 64 payload bits, so applications needing the unsigned magnitude must interpret those bits as unsigned.

Java `null` is a normal conversion input. Import writes `Type.NULL` with a zero payload and allocates no external object. Export recognizes `Type.NULL` and returns Java `null` directly. `VoidValue.INSTANCE` is the Java representation of ELX `Type.VOID`, and importing that singleton writes the void tag. The raw `ExtHeap.add(Object)` operation registers a non-null object by identity. Nullable application values enter through `importJava`, which selects the correct type before object registration.

A callback can use `Call.ro(null)` to return an explicit ELX null and `Call.o(index)` to read one as Java null. Use `Call.rv()` to return ELX void; `Call.o(index)` and `Pin.get()` export it as `VoidValue.INSTANCE`. An external-field adapter can pass a nullable field value to `Span.setObject` and receive null through `Span.o` when a script assigns null to that field. Explicit `null` remains distinct from `void`, which represents an absent or unspecified value.

`Results.get(index)` and `Context.pin(name)` return a pin object even for explicit null. That pin is a `Pin.Primitive` containing Java null. A missing global has type `Type.VOID`, distinct from an explicitly stored `null`.

`exportValue` supports primitives, strings, managed pointer payloads, and external handles. Lists, tables, and functions are accessed through their managed APIs. `Results.get` and `Context.pin` root those managed values and expose their handles, making ownership explicit while using those APIs.

## Globals and arguments

The script's `env` keyword and `VM.globals()` refer to the same global table. `VM.setGlobal(name, slot)` copies a value into that table, and `getGlobal(name, destination)` copies it into a supplied slot. `Context.pin(name)` directly acquires a pin for a global when Java needs to inspect or retain it. Script table mutation and membership use `table.remove(t, key)` and `table.has(t, key)`.

For writing globals or passing low-level arguments, allocate temporary slot storage from `vm.heap().arena()`. Import Java values with `vm.external().importJava(slot, value)`, then pass the slots to the chosen VM or context operation. Release the temporary storage with `arena.free(address)` in a `finally` block.

A slot occupies `Slot.SIZE` bytes. Use `Slot.address(base, index)` to select a zero-based slot, `Slot.type(memory, slot)` to inspect its type, and `Slot.bits(memory, slot)` to read its raw payload. Floating-point payloads contain encoded bits. Conversion helpers decode them into Java numeric values.

Arena storage owns bytes, while typed runtime roots keep managed references reachable. Keep source references rooted if a low-level integration retains temporary slots across execution or collection. `Results` and pins supply that ownership for returned values.

A function can be compiled with `compileFunction`, loaded with `vm.load(image)`, and invoked with the argument-window overload. Handles belong to the VM that loaded them. The same prototype can be loaded elsewhere to obtain a handle for that other VM.

## Host functions and libraries

A `Library` supplies compile-time named definitions. `SimpleLibrary` is the mutable builder. It accepts compiled functions, `HostFunction` callbacks, typed constants, and strings. Finish construction with `freeze()`. Linking also freezes a library, and frozen definitions can be reused by independent runtimes.

```
SimpleLibrary library = new SimpleLibrary()
    .function("add", call -> {
      call.arity(2);
      call.rl(Math.addExact(call.i(0), call.i(1)));
    })
    .freeze();
Prototype image = new Compiler().compile("return add(2, 3)", library);
```

`HostFunction` is a functional interface with `void invoke(Call call)`. The call view borrows the arguments and a separate result window. Typed reads (`i`, `d`, and `b`) require the corresponding value type and reject `void`. Use `operations().convert(...)` to explicitly obtain a target type's default value from `void`. Results are appended with `r`, `rs`, or `ro`; the callback's Java return value is not used. The short scalar methods are `rn`, `rbyte`, `rshort`, `ri`, `rl`, `rf`, `rd`, and `rb`. `rarg(index)` copies one input, and `rvalue(span, index)` copies from another live span. A callback may append up to sixteen results.

A `HostFunction` can also serve as a script decorator. `@wrap(a, b)` invokes the linked function with the original function in slot zero, followed by the explicit arguments. Return that slot with `rarg(0)` for an identity decorator, or return another function value to wrap behavior. Captured values retained by the wrapper follow the usual tracing and pin rules. Argument binding follows ordinary calls, including the requirement for a script signature when using named arguments.

`Call` extends `Span`. Use `size()` and `arity()` for input counts; `type(index)` and `bits(index)` inspect tagged values. `i`, `d`, and `b` read checked numeric or boolean values, `s` exports a string, and `o` converts a slot to a Java value, including explicit null. `set`, `setString`, and `setObject` write values into the borrowed window. `scratch(size)` supplies a zero-initialized, GC-rooted temporary span that remains valid through the callback, including nested script calls. `returnedCount()` reports how many values have been appended.

`Span.operations()` exposes language arithmetic, comparison, conversion, indexing, calls, formatting, and semantic hashing. Arithmetic methods accept the bytecode `Op` directly, such as `binary(Op.ADD, ...)` and `unary(Op.NEG, ...)`. Comparisons use `Instr.Relation`. These paths share the VM's promotion and metamethod rules. The semantic `hash` operation requires an integer result. `Span.containers()` exposes raw list/table storage operations without invoking metamethods. Its `TableCursor` copies keys to a destination span, keeps the table rooted, and detects structural changes. These APIs let native libraries implement algorithms without exposing slot addresses or boxing each value. All callback views are borrowed, thread-confined, and must not be retained.

An exception thrown by a callback propagates through the script call and gains script trace information when available. If callback state retains ELX handles, implement `HostFunction.trace(HeapVisitor)` and visit those references so the collector can keep them alive.

A callback can synchronously call `context.execute(...)` again. Each invocation depth has its own borrowed VM result window, and the context adapter snapshots the returned slots before exposing them as `Results`. Shared callback objects still need application-managed synchronization if multiple runtimes use them from different threads.

## External object adapters

Implement `HostAccess` on a Java object and pass it through `Span.setObject` or the external heap's `importJava`. The default `ExtHeap` sends every operation through `elx_handle(Metamethod, Call)`. The arguments use a canonical indexed layout instead of a separate member-name channel: GET receives `self, key`; SET receives `self, key, value`; CALL receives `self` followed by explicit arguments. Unary operations receive their operand, binary operations receive left and right in source order, and STR, BOOL, and GC receive `self`.

`Metamethod` covers arithmetic, comparisons, `get`, `set`, `call`, `init`, `str`, `inc`, `dec`, `gc`, and `hash`. ELX table metatables use the lowercase names `add`, `sub`, `mul`, `div`, `mod`, `and`, `or`, `xor`, `shl`, `shr`, `ushr`, `pos`, `neg`, `inv`, `not`, `bool`, `eq`, `ne`, `lt`, `le`, `gt`, `ge`, `get`, `set`, `call`, `init`, `str`, `inc`, `dec`, `gc`, and `hash`. The default handler is empty, so an adapter implements only the behavior it owns. A result-producing handler appends a value through `Call`. An empty scalar-result hook means that the operation is unavailable; appending an explicit `null` counts as a handled result. SET, INIT, and GC hooks may complete without results. `hash` must append exactly one integer value.

This adapter exposes one readable and writable property:

```
final class Settings implements HostAccess {
  private long value;

  @Override public void elx_handle(Metamethod method, Call args) {
    if (method == Metamethod.GET && args.type(1) == Type.STRING && args.s(1).equals("value")) args.rl(value);
    if (method == Metamethod.SET && args.type(1) == Type.STRING && args.s(1).equals("value")) value = args.i(2);
  }
}
```

`HostAccess` objects work with `Context.open()` and do not depend on the external heap class. Implement `elx_trace(HeapVisitor)` when the object retains ELX handles. Handle `Metamethod.GC` in `elx_handle` when the external value is reclaimed and the adapter must release an owned resource. `ExtHeap` subclassing remains the lower-level escape hatch for custom handle storage or raw bridge behavior.

External objects use Java reference identity. Importing the same live object reuses its handle. A nullable Java field value maps to explicit ELX `null`; it remains distinct from an absent value represented by `void`.

## Module loading

`context.vm().modules()` exposes the per-VM `Modules` loader. `directory(path)` selects the filesystem base for script modules. `addHandler(resolver)` appends a host resolver to the ordered resolver list.

A `ModuleResolver` returns `ModuleResolver.Source` for a handled name and null to let the loader try its next source. The source contains a diagnostic name, script text, and a library. The two-argument source constructor uses an empty application library. This resolver-level null means “continue searching” and is separate from an ELX module that successfully exports null.

A source library also supplies native declarations. Register a callback with `library.function("host.log", callback)` and compile a script containing `native function host.log(message: string);`. The compiler checks that the named definition exists and is callable. Constants registered through `constant` or `text` use value declarations such as `native threshold: int;`. A qualified native name is bound directly and does not require a runtime `host` table. Compiled functions and constants continue through the existing per-VM linker, so symbols contain no addresses from another VM.

Bundled standard modules use this same compile-time binding path. Their public ELX tables combine native bindings with script implementations. The standard-module loader owns its supplied native library and drops that library's loader root on unload; libraries supplied by application resolvers remain under the application's ownership.

Built-in modules are resolved first. Other requests try registered resolvers, then files under the configured directory, then bundled `/elx/` resources. A successful result is cached by the requested name. Script modules execute in the requesting VM and share its globals.

`unload(name)` removes the cache entry and releases the module's loader root. A later request loads it again. Previously exported values retain their own references. A cycle in active loads raises an error, and unloading a currently loading module also raises an error.

## Execution controls and diagnostics

`VM.output(consumer)` redirects formatted output from `print` and the standard I/O output functions. The consumer receives text including any separators and line endings added by the calling function.

`VM.budget(instructions)` sets a positive instruction budget checked at interpreter safe points. `instructions()` reports executed instructions for the outermost invocation. Host callbacks execute ordinary Java code, so their own execution cost is governed by the host application.

`gcInterval(bytes)` configures the allocation threshold for requesting automatic collection. `requestGc(pressure)` defers a request to a safe point, while `collect(pressure)` requests and performs collection through that mechanism. `collections()` and `gcNanos()` report collection statistics.

Compilation failures use `SyntaxException` with source location information. Runtime failures propagate their Java exception and can include a suppressed `ScriptTrace` with script frames. Give the compiler a meaningful source name to make these frames useful in logs.

`assert(condition[, message])` returns a true boolean unchanged and raises `AssertionError` for false. A `void` argument needs an explicit `bool(void)` conversion. `throw(value)` formats its value and raises `RuntimeException`. Script `pcall(targetfn, handlerfn)` catches those runtime exceptions and assertion failures. It passes an error table to `handlerfn`, then returns `false` followed by the handler's values. A successful target returns `true` followed by its values. Errors raised by the handler propagate to the host, as do other Java `Error` subclasses.

`vm.unload(image)` and `vm.unload(library)` release linker roots when an application has finished using loaded code. Live function values can retain their code independently until those values become unreachable.

## API map

| Package or type                                                            | Responsibility                                                             |
|----------------------------------------------------------------------------|----------------------------------------------------------------------------|
| `io.viki.elx.Context`, `Results`                                           | Runtime ownership and retained execution results.                          |
| `io.viki.elx.compile.Compiler`                                             | Source compilation and source-level diagnostics.                           |
| `io.viki.elx.asm.Prototype`, `Parameters`                                  | Compiled code and function signature metadata.                             |
| `io.viki.elx.lib.Library`, `SimpleLibrary`, `HostFunction`, `Call`, `Span` | Host definitions, borrowed input values, and callback results.             |
| `io.viki.elx.vm.VM`, `Modules`                                             | Execution, linking, globals, modules, budgets, and collection controls.    |
| `io.viki.elx.memory.Pin`, `PinMode`                                        | Host retention and liveness.                                               |
| `io.viki.elx.memory.HostAccess`, `ExtHeap`                                 | Java object metamethods, identity conversion, and low-level bridge access. |
| `io.viki.elx.memory.Slot`, `Memory`, `Arena`, `ElxHeap`                    | Typed storage, allocation, and managed roots.                              |
| `io.viki.elx.value.ElxList`, `ElxTable`                                    | Managed container operations through typed slots.                          |

[Back to the language manual](index.md)
