/*
 * MIT License
 *
 * Copyright (c) 2026 Licphel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.*;
import io.viki.elx.lib.DynamicLibrary;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.ValueText;
import io.viki.elx.memory.*;
import io.viki.elx.value.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Thread-confined register interpreter. Borrows heaps/registers/Memory; close the
 * VM before those resources. Script values stay in Slot storage. Function images
 * stay rooted until explicitly unloaded. Live functions/pins can retain unloaded code.
 * Host callbacks may reenter execute;
 * frames, return windows and budgets remain shared through that boundary.
 */
public final class VM implements AutoCloseable {
  /** Limit synchronous host/hook reentry before exhausting the Java call stack. */
  private static final int MAX_REENTRY = 128;
  private static final int MAX_RESULTS = io.viki.elx.lib.Call.MAX_RESULTS;
  private static final int POLL_MASK = 255;
  private static final int INVERT_RESULT = 1 << 8;
  private static final long DEFAULT_GC_INTERVAL = 1L << 20;
  private final ElxHeap heap;
  private final ExtHeap external;
  private final JavaRegs regs;
  private final Memory memory;
  private final Arena arena;
  private final Gc gc;
  private final Linker linker;
  private final long globals;
  private final Modules modules;
  private final Operations operations;
  private final Containers containers;
  private long collections, gcNanos;
  private long gcInterval = DEFAULT_GC_INTERVAL, nextGc;
  private Consumer<String> output = System.out::print;
  private int[] functions = new int[16], pcs = new int[16], capacities = new int[16], invocations = new int[16];
  private long[] bases = new long[16], destinations = new long[16], frameTokens = new long[16];
  private long[] hookArgs = new long[16], handles = new long[16];
  private int[] hookCapacities = new int[16], returnChecks = new int[16];
  private int depth, invocationDepth;
  private long[] results = new long[4];
  private int[] resultCounts = new int[4];
  private long lastResults;
  private long executed, budget = 100_000_000L, nextFrameToken = 1;
  private boolean closed, gcRequested, gcPressure;

  /**
   * Creates an interpreter over shared heap, external-object, and register
   * resources. The VM initializes the global table and standard {@code sys}
   * module immediately; all supplied resources remain owned by the caller.
   *
   * @param heap     internal ELX heap
   * @param external external Java heap sharing the internal memory
   * @param regs     reusable register-window stack
   */
  public VM(ElxHeap heap, ExtHeap external, JavaRegs regs) {
    this.heap = heap;
    this.external = external;
    external.attach(this);
    this.regs = regs;
    this.memory = heap.memory();
    this.arena = heap.arena();
    gc = new Gc(heap, external, regs);
    linker = new Linker(this, heap, external);
    globals = ElxTable.create(heap);
    operations = new Operations(this);
    containers = new Containers(this);
    modules = new Modules(this);
    long system = arena.allocate(Slot.SIZE);
    try {
      modules.require("sys", system);
      setGlobal("sys", system);
    } finally {
      arena.free(system);
    }
    nextGc = heap.allocatedBytes() + gcInterval;
  }

  /**
   * Creates a GC-traced native function value in this VM.
   *
   * @param function callback invoked with a borrowed {@link io.viki.elx.lib.Call}
   * @return VM-local function handle
   * @throws IllegalStateException if the VM is closed
   */
  public long function(io.viki.elx.lib.HostFunction function) {
    checkOpen();
    long header = heap.allocate(ElxHeap.Layout.CLOSURE, 3);
    Slot.write(memory, header, Type.INT, -1);
    Slot.write(memory, header + Slot.SIZE, Type.EXTERNAL, external.add(HostCalls.bind(this, function)));
    Slot.write(memory, header + 2L * Slot.SIZE, Type.VOID, 0);
    return header;
  }

  /**
   * Returns the VM's shared language-semantic operation facade.
   *
   * @return thread-confined operation facade
   */
  public ValueOperations operations() {
    return operations;
  }

  /**
   * Returns raw list and table operations that bypass metamethod dispatch.
   *
   * @return thread-confined container facade
   */
  public ContOp containers() {
    return containers;
  }

  /**
   * Borrows a zero-initialized, GC-rooted span of scratch slots.
   *
   * @param count requested slot count
   * @return scope that releases the slots when closed
   * @throws IllegalArgumentException if {@code count} is negative
   * @throws IllegalStateException    if the VM is closed
   */
  public Slots.Scope borrowSlots(int count) {
    if (count < 0) {
      throw new IllegalArgumentException("Negative scratch size: " + count);
    }
    long base = regs.enter(Math.max(1, count));
    for (int i = 0; i < count; i++) {
      Slot.write(memory, Slot.address(base, i), Type.VOID, 0);
    }
    return new Slots.Scope(this, base, count);
  }

  void releaseSlots() {
    regs.leave();
  }

  /**
   * Returns cumulative time spent in completed garbage collections.
   *
   * @return elapsed collection time in nanoseconds
   */
  public long gcNanos() {
    return gcNanos;
  }

  /**
   * Returns the number of completed garbage collections.
   *
   * @return completed collection count
   */
  public long collections() {
    return collections;
  }

  /**
   * Returns the shared raw memory backend.
   *
   * @return borrowed memory backend
   */
  public Memory memory() {
    return memory;
  }

  /**
   * Returns the internal ELX heap.
   *
   * @return borrowed internal heap
   */
  public ElxHeap heap() {
    return heap;
  }

  /**
   * Returns the external Java-object heap.
   *
   * @return borrowed external heap
   */
  public ExtHeap external() {
    return external;
  }

  /**
   * Returns the output sink used by standard print functions.
   *
   * @return current sink
   */
  public Consumer<String> output() {
    return output;
  }

  /**
   * Replaces the output sink used by standard print functions.
   *
   * @param output new sink receiving printed text
   * @throws NullPointerException if {@code output} is null
   */
  public void output(Consumer<String> output) {
    this.output = output;
  }

  /**
   * Returns the optimization form of the innermost executing image.
   *
   * @return {@link Optimization#O0} for a base-only image, otherwise
   * {@link Optimization#O1}; returns O1 when no frame is active
   */
  public Optimization optimization() {
    return Optimization.O1;
  }

  /**
   * Returns this VM's module cache and resolver registry.
   *
   * @return module manager owned by this VM
   */
  public Modules modules() {
    return modules;
  }

  /**
   * Returns the global environment table pointer.
   *
   * @return rooted global table pointer
   */
  public long globals() {
    return globals;
  }

  long constantAddress(int index) {
    return Slot.address(linker.constants(functions[depth - 1]), index);
  }

  long constantPointer(int index) {
    return Pointers.constant(heap, linker.constants(functions[depth - 1]), index);
  }

  long localPointer(int register) {
    return Pointers.local(heap, frameTokens[depth - 1], register);
  }

  long capturePointer(int capture) {
    return Pointers.capture(heap, handles[depth - 1], capture);
  }

  long pointerData(long pointerSlot) {
    if (Slot.type(memory, pointerSlot) == Type.LIST) {
      return ElxList.data(memory, Slot.bits(memory, pointerSlot));
    }
    return Pointers.data(heap, Slot.bits(memory, pointerSlot), frameTokens, bases, depth);
  }

  void pointerWrite(long pointerSlot, long valueSlot) {
    Pointers.write(heap, Slot.bits(memory, pointerSlot), valueSlot, frameTokens, bases, depth);
  }

  /**
   * Sets the allocation threshold that requests the next automatic collection.
   *
   * @param bytes positive number of newly allocated bytes between collection requests
   * @throws IllegalArgumentException if {@code bytes} is not positive
   * @throws IllegalStateException    if the VM is closed
   */
  public void gcInterval(long bytes) {
    if (bytes <= 0) {
      throw new IllegalArgumentException("GC interval must be positive");
    }
    gcInterval = bytes;
    nextGc = heap.allocatedBytes() + bytes;
  }

  /**
   * Requests and performs a collection at the next safe point immediately.
   *
   * @param pressure whether weak pins may be reclaimed
   * @throws IllegalStateException if the VM is closed or a collection cannot run
   */
  public void collect(boolean pressure) {
    requestGc(pressure);
    safePoint();
  }

  /**
   * Releases the linker's root for a compiled image; live function values may
   * retain its code until they are collected.
   *
   * @param image image whose loader root is released
   * @throws IllegalStateException if the VM is closed
   */
  public void unload(Prototype image) {
    modules.invalidateNameCache();
    linker.unload(image);
  }

  /**
   * Releases the linker's root for a linked library.
   *
   * @param library library whose loader root is released
   * @throws IllegalStateException if the VM is closed
   */
  public void unload(Library library) {
    modules.invalidateNameCache();
    linker.unload(library);
  }

  /**
   * Copies a named global value into a caller-provided result slot.
   *
   * @param name   UTF-8 global name
   * @param result destination slot
   * @throws NullPointerException  if {@code name} is null
   * @throws IllegalStateException if the VM is closed
   */
  public void getGlobal(String name, long result) {
    long key = heap.importString(name), scratch = arena.allocate(Slot.SIZE);
    try {
      Slot.write(memory, scratch, Type.STRING, key);
      ElxTable.get(memory, globals, scratch, result);
    } finally {
      arena.free(scratch);
    }
  }

  /**
   * Stores a caller-provided slot value under a global name.
   *
   * @param name  UTF-8 global name
   * @param value source slot
   * @throws NullPointerException  if {@code name} is null
   * @throws IllegalStateException if the VM is closed
   */
  public void setGlobal(String name, long value) {
    long key = heap.importString(name), scratch = arena.allocate(Slot.SIZE);
    try {
      Slot.write(memory, scratch, Type.STRING, key);
      ElxTable.set(heap, globals, scratch, value);
    } finally {
      arena.free(scratch);
    }
  }

  /**
   * Returns the most recent result window.
   *
   * <p>The window is borrowed and remains valid until another execution at the
   * same invocation depth overwrites it or the VM closes.</p>
   *
   * @return result-slot base address, or zero before the first execution
   */
  public long results() {
    return lastResults;
  }

  /**
   * Compares two slot values using the same path as the script {@code <}
   * operator.
   *
   * <p>This evaluates {@code left < right} and, when that is false,
   * {@code right < left}. Both evaluations go through the VM's operator hook
   * machinery, so table {@code lt} metamethods, external-object comparison
   * hooks, numeric ordering, and the default fallback all behave exactly as
   * they do for a script comparison.</p>
   *
   * <p>Numeric comparisons avoid scratch storage. Other types borrow a rooted
   * scratch slot and may reenter script code. It is intended for host-side
   * ordering, such as sorting a list; tight loops that compare thousands of
   * tables should expect one hook invocation per comparison.</p>
   *
   * @param left  left slot address
   * @param right right slot address
   * @return negative when {@code left < right}, zero when neither operand is
   * less than the other, positive when {@code right < left}
   * @throws IllegalStateException if either slot is dead or the VM is closed
   */
  public int compare(long left, long right) {
    if (operations.test(left, right, Instr.Relation.LT.ordinal())) {
      return -1;
    }
    return operations.test(right, left, Instr.Relation.LT.ordinal()) ? 1 : 0;
  }

  /**
   * Tests two slot values for equality using the script {@code ==} path.
   *
   * <p>Evaluates the VM's equality operator, so table {@code eq} metamethods,
   * external-object equality hooks, and the default value comparison behave
   * exactly as in a script {@code left == right}. Numeric comparisons avoid scratch
   * storage. Other types borrow one rooted slot and may reenter script code when
   * an equality hook is installed.</p>
   *
   * @param left  left slot address
   * @param right right slot address
   * @return whether the values compare equal
   * @throws IllegalStateException if either slot is dead or the VM is closed
   */
  public boolean equal(long left, long right) {
    return operations.test(left, right, Instr.Relation.EQ.ordinal());
  }

  /**
   * Formats a slot value recursively, protecting against cycles in containers.
   * Table {@code __str} hooks are executed when present; otherwise lists and
   * tables are traversed in their iteration order.
   *
   * @param value source slot
   * @return human-readable ELX representation
   * @throws IllegalStateException if a formatting hook fails or the VM is closed
   */
  public String format(long value) {
    return format(value, new HashSet<>());
  }

  private String format(long value, Set<Long> path) {
    Type type = Slot.type(memory, value);
    long bits = Slot.bits(memory, value);
    if (type == Type.EXTERNAL) {
      long result = arena.allocate(Slot.SIZE);
      try {
        safePoint();
        int returned = external.nmeta(bits, Metamethod.STR, 0, 0, result, 1);
        if (returned == 1 && Slot.type(memory, result) == Type.STRING) {
          return heap.exportString(Slot.bits(memory, result));
        }
      } finally {
        arena.free(result);
      }
    }
    if (type != Type.TABLE && type != Type.LIST) {
      return ValueText.string(memory, value);
    }
    if (type == Type.TABLE) {
      long hook = ElxTable.hook(memory, bits, Metamethod.STR.ordinal());
      if (hook != 0) {
        long arguments = arena.allocate(Slot.SIZE);
        try {
          Slot.copy(memory, arguments, value, 1);
          if (execute(Slot.bits(memory, hook), arguments, 1) != 1 || Slot.type(memory, results()) != Type.STRING) {
            throw new IllegalArgumentException("str metamethod must return one string");
          }
          return heap.exportString(Slot.bits(memory, results()));
        } finally {
          arena.free(arguments);
        }
      }
    }
    if (!path.add(bits)) {
      return type == Type.TABLE ? "{...}" : "[...]";
    }
    long entries = arena.allocate(4 * Slot.SIZE);
    try {
      StringBuilder text = new StringBuilder(type == Type.TABLE ? "{" : "[");
      long cursor = 0;
      boolean first = true;
      for (; ; ) {
        if (type == Type.TABLE) {
          ElxTable.next(memory, entries, bits, cursor);
          cursor = Slot.bits(memory, entries + 3L * Slot.SIZE);
          if (cursor == -1) {
            break;
          }
          if (!first) {
            text.append(", ");
          }
          text.append(format(entries + Slot.SIZE, path)).append("=").append(format(entries + 2L * Slot.SIZE, path));
        } else {
          ElxList.next(memory, entries, bits, cursor);
          cursor = Slot.bits(memory, entries + 2L * Slot.SIZE);
          if (cursor == -1) {
            break;
          }
          if (!first) {
            text.append(", ");
          }
          text.append(format(entries + Slot.SIZE, path));
        }
        first = false;
      }
      return text.append(type == Type.TABLE ? '}' : ']').toString();
    } finally {
      path.remove(bits);
      arena.free(entries);
    }
  }

  /**
   * Returns instructions executed by the outermost invocation since its start.
   *
   * @return interpreter instruction count
   */
  public long instructions() {
    return executed;
  }

  /**
   * Sets the instruction budget checked at interpreter safe points.
   *
   * @param instructions positive instruction budget
   * @throws IllegalArgumentException if the budget is not positive
   * @throws IllegalStateException    if the VM is closed
   */
  public void budget(long instructions) {
    if (instructions <= 0) {
      throw new IllegalArgumentException("Budget must be positive");
    }
    budget = instructions;
  }

  /**
   * Defers a GC request until the interpreter reaches a safe point.
   *
   * @param pressure whether weak pins should be reclaimed during the request
   */
  public void requestGc(boolean pressure) {
    gcRequested = true;
    gcPressure |= pressure;
  }

  /**
   * Installs a compiled image once, with automatic VM-local address relocation.
   *
   * @param image immutable compiled image
   * @return VM-local function handle
   * @throws IllegalStateException if the VM is closed
   */
  public long load(Prototype image) {
    checkOpen();
    return linker.load(image);
  }

  /**
   * Links a library and returns its VM-local export table.
   *
   * @param library library to freeze and link
   * @return VM-local export table
   * @throws IllegalStateException if the VM is closed or the library is already invalid
   */
  public DynamicLibrary link(Library library) {
    checkOpen();
    return linker.link(library);
  }

  /**
   * Executes a compiled image with no arguments.
   *
   * @param image compiled image
   * @return number of returned values, from zero through sixteen
   * @throws IllegalStateException if the VM is closed or execution fails
   */
  public int execute(Prototype image) {
    return execute(image, 0, 0);
  }

  /**
   * Executes a compiled image using a contiguous input-slot window.
   *
   * @param image compiled image
   * @param args  input-slot base address
   * @param count number of input slots
   * @return number of returned values, from zero through sixteen
   * @throws IllegalArgumentException if the image rejects the argument count
   * @throws IllegalStateException    if the VM is closed or execution fails
   */
  public int execute(Prototype image, long args, int count) {
    return execute(load(image), args, count);
  }

  /**
   * Executes an already loaded function handle, copying arguments directly
   * between slots. Calls may re-enter the VM through host callbacks; each
   * invocation receives an independent borrowed result window.
   *
   * @param function VM-local function handle
   * @param args     input-slot base address
   * @param count    number of input slots
   * @return number of returned values, from zero through sixteen
   * @throws IllegalArgumentException if the handle or argument count is invalid
   * @throws IllegalStateException    if the VM is closed, execution exceeds its
   *                                  budget, or a script operation fails
   */
  public int execute(long function, long args, int count) {
    checkOpen();
    if (invocationDepth >= MAX_REENTRY) {
      throw new IllegalStateException("ELX host/metamethod reentry exceeds " + MAX_REENTRY);
    }
    int stop = depth, invocation = invocationDepth++;
    if (invocation == results.length) {
      results = Arrays.copyOf(results, invocation * 2);
      resultCounts = Arrays.copyOf(resultCounts, invocation * 2);
    }
    if (results[invocation] == 0) {
      results[invocation] = arena.allocate(MAX_RESULTS * Slot.SIZE);
    }
    clearResults(results[invocation], 0, MAX_RESULTS);
    resultCounts[invocation] = 0;
    if (invocation == 0) {
      executed = 0;
    }
    try {
      int returned = push(function, args, count, results[invocation], MAX_RESULTS);
      if (returned >= 0) {
        resultCounts[invocation] = returned;
      } else {
        invocations[depth - 1] = invocation;
        runUntil(stop);
      }
      lastResults = results[invocation];
      return resultCounts[invocation];
    } catch (RuntimeException | Error failure) {
      if (depth > stop) {
        StackTraceElement[] trace = new StackTraceElement[depth - stop];
        for (int i = depth - 1; i >= stop; i--) {
          Prototype code = linker.image(functions[i]);
          int pc = Math.max(0, pcs[i] - 1);
          trace[depth - 1 - i] = new StackTraceElement("elx", code.functionName(), code.sourceName(), code.line(pc));
        }
        failure.addSuppressed(new ScriptTrace(trace));
      }
      while (depth > stop) {
        regs.leave();
        depth--;
        frameTokens[depth] = 0;
      }
      clearResults(results[invocation], 0, MAX_RESULTS);
      lastResults = results[invocation];
      throw failure;
    } finally {
      invocationDepth--;
    }
  }

  /** Returns a native result count, or -1 after pushing a script frame. */
  private int push(long function, long args, int count, long destination, int capacity) {
    int id = linker.id(function);
    if (id < 0) {
      return callNative(function, args, count, destination, capacity);
    }
    Prototype image = linker.image(id);
    if (image.captureCount() != 0 && function == linker.body(function)) {
      throw new IllegalArgumentException("Cannot call an unbound closure image");
    }
    if (image.signature() == null && count != image.parameterCount()) {
      throw new IllegalArgumentException("Expected " + image.parameterCount() + " arguments, got " + count);
    }
    if (depth == functions.length) {
      int size = depth * 2;
      functions = Arrays.copyOf(functions, size);
      pcs = Arrays.copyOf(pcs, size);
      capacities = Arrays.copyOf(capacities, size);
      invocations = Arrays.copyOf(invocations, size);
      bases = Arrays.copyOf(bases, size);
      destinations = Arrays.copyOf(destinations, size);
      hookArgs = Arrays.copyOf(hookArgs, size);
      handles = Arrays.copyOf(handles, size);
      frameTokens = Arrays.copyOf(frameTokens, size);
      hookCapacities = Arrays.copyOf(hookCapacities, size);
      returnChecks = Arrays.copyOf(returnChecks, size);
    }
    long base = regs.enter(image.registerCount());
    try {
      bindArguments(image, base, args, count);
    } catch (RuntimeException | Error failure) {
      regs.leave();
      throw failure;
    }
    functions[depth] = id;
    frameTokens[depth] = nextFrameToken++;
    handles[depth] = function;
    pcs[depth] = 0;
    bases[depth] = base;
    destinations[depth] = destination;
    capacities[depth] = capacity;
    invocations[depth] = -1;
    returnChecks[depth] = -1;
    depth++;
    return -1;
  }

  private int callNative(long function, long args, int count, long destination, int capacity) {
    long roots = regs.enter(count + 1);
    try {
      Slot.write(memory, roots, Type.FUNCTION, function);
      Slot.copy(memory, roots + Slot.SIZE, args, count);
      int returned = external.ncall(linker.nativeTarget(function), 0, roots + Slot.SIZE, count, destination, capacity);
      if (returned < 0) {
        throw new IllegalStateException("Native library call failed: " + returned);
      }
      clearResults(destination, returned, capacity);
      return returned;
    } finally {
      regs.leave();
    }
  }

  private void finish(int first, int count) {
    finishValues(Slot.address(bases[depth - 1], first), count);
  }

  private void finishValues(long source, int count) {
    int frame = depth - 1, written = Math.min(count, capacities[frame]);
    if (returnChecks[frame] >= 0 && (count == 0 || Slot.type(memory, source).ordinal() != (returnChecks[frame] & 255))) {
      throw new IllegalArgumentException("Metamethod must return " + Type.fromTag((byte) returnChecks[frame]));
    }
    if (written > 0) {
      Slot.copy(memory, destinations[frame], source, written);
    }
    clearResults(destinations[frame], written, capacities[frame]);
    if (written > 0 && returnChecks[frame] >= 0 && (returnChecks[frame] & INVERT_RESULT) != 0) {
      Slot.write(memory, destinations[frame], Type.BOOL, Slot.bits(memory, destinations[frame]) == 0 ? 1 : 0);
    }
    if (invocations[frame] >= 0) {
      resultCounts[invocations[frame]] = written;
    }
    regs.leave();
    depth--;
    frameTokens[frame] = 0;
  }

  private void clearResults(long base, int from, int count) {
    for (int i = from; i < count; i++) {
      Slot.write(memory, Slot.address(base, i), Type.VOID, 0);
    }
  }

  private void safePoint() {
    if (executed > budget) {
      throw new IllegalStateException("ELX instruction budget exceeded");
    }
    if (!gcRequested && heap.allocatedBytes() < nextGc) {
      return;
    }
    boolean pressure = gcPressure;
    gcRequested = false;
    gcPressure = false;
    long started = System.nanoTime();
    gc.collect(pressure, visitor -> {
      for (int i = 0; i < depth; i++) {
        visitor.visit(Type.FUNCTION, handles[i]);
      }
      linker.visitRoots(visitor);
      visitor.visit(Type.TABLE, globals);
      modules.visitRoots(visitor);
      for (int invocation = 0; invocation < results.length; invocation++) {
        long window = results[invocation];
        // Inactive scratch return windows must not retain previous nested calls.
        if (window != 0 && (invocation < invocationDepth || window == lastResults)) {
          for (int i = 0; i < MAX_RESULTS; i++) {
            long cell = Slot.address(window, i);
            if (Slot.isReference(memory, cell)) {
              visitor.visit(Slot.type(memory, cell), Slot.bits(memory, cell));
            }
          }
        }
      }
    });
    gcNanos += System.nanoTime() - started;
    collections++;
    linker.prune();
    nextGc = heap.allocatedBytes() + gcInterval;
  }

  private void runUntil(int stop) {
    int cachedFrame = -1, id = -1;
    long instructionBase = 0, base = 0;
    while (depth > stop) {
      int frame = depth - 1, pc = pcs[frame];
      if (frame != cachedFrame) {
        cachedFrame = frame;
        id = functions[frame];
        instructionBase = linker.code(id);
        base = bases[frame];
      }
      int word = memory.load32(instructionBase + (long) pc * Instr.WORD_BYTES);
      Op op = Instr.op(word);
      pcs[frame] = pc + op.format().words();
      if ((++executed & POLL_MASK) == 0) {
        safePoint();
      }
      int registerMask = op.registerMask();
      // Match the A/B/C bits used by prototype validation; immediates are not addresses.
      long a = (registerMask & 1) != 0 ? Slot.address(base, Instr.a(word)) : 0;
      long b = (registerMask & 2) != 0 ? Slot.address(base, Instr.b(word)) : 0;
      long c = (registerMask & 4) != 0 ? Slot.address(base, Instr.c(word)) : 0;
      int extension = op.format().words() > 1
          ? memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES) : 0;
      switch (op) {
        case MOVE -> Slot.copy(memory, a, b, 1);
        case LOADK -> Slot.copy(memory, a, Slot.address(linker.constants(id), Instr.index(word)), 1);
        case LOADNULL -> Slot.write(memory, a, Type.NULL, 0);
        case LOADVOID -> Slot.write(memory, a, Type.VOID, 0);
        case LOADBOOL -> Slot.write(memory, a, Type.BOOL, Instr.b(word));
        case GETENV -> Slot.write(memory, a, Type.TABLE, globals);
        case GETGLOBAL -> readTableIndex(a, globals, Slot.address(linker.constants(id), Instr.index(word)));
        case SETGLOBAL -> ElxTable.set(heap, globals, Slot.address(linker.constants(id), extension), a);
        case GETUPVAL -> Slot.copy(memory, a, Slot.address(handles[frame], Instr.b(word) + 1), 1);
        case SETUPVAL -> Slot.copy(memory, Slot.address(handles[frame], Instr.b(word) + 1), a, 1);
        case SELF -> Slot.write(memory, a, Type.FUNCTION, handles[frame]);
        case CLOSURE -> createClosure(a, b, c, extension);
        case NEWLIST, NEWTABLE -> {
          safePoint();
          Slot.write(memory, a, op == Op.NEWLIST ? Type.LIST : Type.TABLE,
              heap.allocate(op == Op.NEWLIST ? ElxHeap.Layout.LIST : ElxHeap.Layout.TABLE, Instr.b(word)));
        }
        case GETTABLE -> readIndex(a, b, c);
        case SETTABLE -> writeIndex(a, b, c);
        case GETFIELD -> readIndex(a, b, Slot.address(linker.constants(id), extension));
        case SETFIELD -> writeIndex(a, Slot.address(linker.constants(id), extension), b);
        case GETI, SETI -> {
          try (Slots.Scope key = borrowSlots(1)) {
            key.setInt(0, Instr.c(word));
            if (op == Op.GETI) {
              readIndex(a, b, Slots.address(key, 0));
            } else {
              writeIndex(a, Slots.address(key, 0), b);
            }
            completeRuntimeHook(depth);
          }
        }
        case RAWGET -> RawValues.getIndex(memory, b, c, a);
        case RAWSET -> RawValues.setIndex(heap, a, b, c);
        case APPEND -> ElxList.append(heap, Slot.bits(memory, a), b);
        case ADD, SUB, MUL, DIV, MOD, BAND, BOR, BXOR, SHL, SHR, USHR -> operations.execute(op, a, b, c, 0);
        case NEG, POS, BNOT, NOT, INC, DEC -> operations.execute(op, a, b, b, 0);
        case COMPARE -> operations.execute(op, a, b, c, extension);
        case ADDK, SUBK -> operations.execute(op == Op.ADDK ? Op.ADD : Op.SUB, a, b,
            Slot.address(linker.constants(id), extension), 0);
        case ADDI -> {
          Type kind = Slot.type(memory, b);
          if (!kind.integer()) {
            throw new IllegalArgumentException("ADDI requires integer, got " + kind);
          }
          Slot.write(memory, a, kind, Type.normalize(Slot.bits(memory, b) + extension, kind));
        }
        case RAWEQUAL -> Slot.write(memory, a, Type.BOOL, RawValues.equal(memory, b, c) ? 1 : 0);
        case JMP -> pcs[frame] = pc + Instr.jumpOffset(word) / Instr.WORD_BYTES;
        case BR_TRUE, BR_FALSE -> {
          if (condition(a) == (op == Op.BR_TRUE)) {
            pcs[frame] = pc + Instr.branchOffset(word) / Instr.WORD_BYTES;
          }
        }
        case BR_VOID -> {
          if (Slot.type(memory, a) == Type.VOID) {
            pcs[frame] = pc + Instr.branchOffset(word) / Instr.WORD_BYTES;
          }
        }
        case BR_PRESENT -> {
          if (Slot.type(memory, a) != Type.INVALID) {
            pcs[frame] = pc + Instr.branchOffset(word) / Instr.WORD_BYTES;
          }
        }
        case BR_COMPARE -> {
          if (operations.test(b, c, extension & 255, a) == ((extension & Instr.BRANCH_FALSE) == 0)) {
            pcs[frame] = pc + memory.load32(instructionBase + (long) (pc + 2) * Instr.WORD_BYTES) / Instr.WORD_BYTES;
          }
        }
        case CALL -> {
          safePoint();
          callValue(a, b, c, Instr.argumentCount(extension), Instr.resultCount(extension));
        }
        case CALL_PACKED -> packedCall(a, b, c, extension);
        case CALL_NAMED -> namedCall(a, b, c, linker.image(id).callSite(extension));
        case RETURN -> finish(Instr.a(word), Instr.b(word));
        case RETURN0 -> finish(0, 0);
        case RETURN1 -> finish(Instr.a(word), 1);
        case RETURN_UNPACK -> {
          int count = Pointers.dereferenceCount(memory, a);
          if (count > MAX_RESULTS) {
            throw new IllegalArgumentException("At most sixteen return values are supported");
          }
          finishValues(pointerData(a), count);
        }
        case UNPACK -> {
          int count = Pointers.dereferenceCount(memory, b);
          if (extension > count) {
            throw new IllegalArgumentException("Unpack needs " + extension + " values, sequence has " + count);
          }
          Slot.copy(memory, a, pointerData(b), extension);
        }
        case APPEND_UNPACK -> {
          int count = Pointers.dereferenceCount(memory, b);
          for (int i = 0; i < count; i++) {
            ElxList.append(heap, Slot.bits(memory, a), Slot.address(pointerData(b), i));
          }
        }
        case LOCALREF -> Slot.write(memory, a, Type.PTR, Pointers.local(heap, frameTokens[frame], Instr.b(word)));
        case INDEXREF -> {
          safePoint();
          long ptr = Slot.type(memory, b) == Type.EXTERNAL
              ? external.naddress(Slot.bits(memory, b), Slot.bits(memory, c)) : Pointers.indexed(heap, b, c);
          Slot.write(memory, a, Type.PTR, ptr);
        }
        case GLOBALREF -> Slot.write(memory, a, Type.PTR, Pointers.global(heap, globals, b));
        case UPVALREF -> Slot.write(memory, a, Type.PTR, Pointers.capture(heap, handles[frame], Instr.b(word)));
        case CONSTREF ->
            Slot.write(memory, a, Type.PTR, Pointers.constant(heap, linker.constants(id), Instr.index(word)));
        case GETREF -> Slot.copy(memory, a, pointerData(b), 1);
        case SETREF -> pointerWrite(a, b);
        case TYPE -> Slot.write(memory, a, Type.UBYTE, Byte.toUnsignedInt(Slot.type(memory, b).tag()));
        case CONVERT -> convertValue(a, b, Instr.c(word));
        case TOSTRING -> Slot.write(memory, a, Type.STRING, heap.strings().importJava(format(b)));
        case LEN -> Slot.write(memory, a, Type.INT, Slot.type(memory, b) == Type.STRING
            ? heap.strings().codePointLength(Slot.bits(memory, b)) : RawValues.length(memory, b));
        case HASH -> Slot.write(memory, a, Type.LONG, operations.hashValue(b));
        case SETMETA -> {
          if (Slot.type(memory, b) != Type.TABLE || (Slot.type(memory, c) != Type.TABLE && Slot.type(memory, c) != Type.NULL)) {
            throw new IllegalArgumentException("setmetatable requires table and table/null");
          }
          long table = Slot.bits(memory, b);
          ElxTable.setMetatable(heap, table, Slot.type(memory, c) == Type.NULL ? 0 : Slot.bits(memory, c));
          Slot.write(memory, a, Type.TABLE, table);
        }
        case GETMETA -> {
          if (Slot.type(memory, b) != Type.TABLE) {
            throw new IllegalArgumentException("getmetatable requires table");
          }
          long meta = ElxTable.metatable(memory, Slot.bits(memory, b));
          Slot.write(memory, a, meta == 0 ? Type.NULL : Type.TABLE, meta);
        }
        case SAFEPOINT -> safePoint();
      }
    }
  }

  private void packedCall(long result, long function, long sequence, int control) {
    safePoint();
    boolean list = (control & 256) != 0;
    int count = list ? Pointers.argumentCount(memory, sequence) : Pointers.dereferenceCount(memory, sequence);
    if (count > 255) {
      throw new IllegalArgumentException("Call argument count exceeds 255");
    }
    callValue(result, function, list ? Pointers.argumentData(heap, sequence, frameTokens, bases, depth) : pointerData(sequence),
        count, control & 255);
  }

  void runtimeConvert(long result, long source, int kind) {
    int stop = depth;
    convertValue(result, source, kind);
    completeRuntimeHook(stop);
  }

  private void createClosure(long a, long b, long c, int count) {
    safePoint();
    long function = Slot.bits(memory, b);
    int child = linker.id(function);
    if (child < 0 || linker.image(child).captureCount() != count) {
      throw new IllegalArgumentException("Closure capture count mismatch");
    }
    long closure = heap.allocate(ElxHeap.Layout.CLOSURE, count + 1);
    Slot.write(memory, closure, Type.FUNCTION, function);
    Slot.copy(memory, closure + Slot.SIZE, c, count);
    Slot.write(memory, a, Type.FUNCTION, closure);
  }

  private void convertValue(long out, long source, int type) {
    Type target = Type.fromTag((byte) type), actual = Slot.type(memory, source);
    if (actual == Type.VOID) {
      if (target.numeric()) {
        Slot.write(memory, out, target, 0);
      } else if (target == Type.STRING) {
        Slot.write(memory, out, target, heap.strings().importJava(""));
      } else if (target == Type.LIST) {
        Slot.write(memory, out, target, ElxList.create(heap));
      } else if (target == Type.TABLE) {
        Slot.write(memory, out, target, ElxTable.create(heap));
      } else if (target == Type.NULL) {
        Slot.write(memory, out, target, 0);
      } else if (target == Type.FUNCTION) {
        Slot.write(memory, out, target, function(call -> {
        }));
      } else {
        Slot.write(memory, out, Type.VOID, 0);
      }
      return;
    }
    if (target == Type.BOOL && (actual == Type.TABLE || actual == Type.EXTERNAL)) {
      try (Slots.Scope arguments = borrowSlots(1)) {
        long args = Slots.address(arguments, 0);
        Slot.copy(memory, args, source, 1);
        int returned;
        if (actual == Type.EXTERNAL) {
          safePoint();
          returned = external.nmeta(Slot.bits(memory, args), Metamethod.BOOL, args, 1, out, 1);
        } else {
          long hook = ElxTable.hook(memory, Slot.bits(memory, args), Metamethod.BOOL.ordinal());
          if (hook == 0) {
            throw new IllegalArgumentException("Table has no bool conversion");
          }
          returned = executeHook(Slot.bits(memory, hook), args, 1, out, 1);
        }
        if (returned != 1 || Slot.type(memory, out) != Type.BOOL) {
          throw new IllegalArgumentException("bool metamethod must return one BOOL");
        }
      }
    } else {
      Slot.write(memory, out, target, Type.cast(Slot.bits(memory, source), actual, target));
    }
  }

  private void bindArguments(Prototype image, long base, long args, int count) {
    Parameters parameters = image.signature();
    if (parameters == null) {
      if (count > 0) {
        Slot.copy(memory, base, args, count);
      }
      return;
    }
    int fixed = parameters.fixed();
    if (!parameters.variadic() && count == fixed) {
      if (count > 0) {
        Slot.copy(memory, base, args, count);
      }
      return;
    }
    if (!parameters.variadic() && count > fixed) {
      throw new IllegalArgumentException("Expected at most " + fixed + " arguments, got " + count);
    }
    int copied = Math.min(fixed, count);
    if (copied > 0) {
      Slot.copy(memory, base, args, copied);
    }
    for (int i = 0; i < fixed; i++) {
      if (i >= count) {
        Slot.write(memory, Slot.address(base, i), Type.INVALID, 0);
      }
      if (Slot.type(memory, Slot.address(base, i)) == Type.INVALID && !parameters.optional().get(i)) {
        throw new IllegalArgumentException("Missing argument '" + parameters.names().get(i) + "'");
      }
    }
    if (parameters.variadic()) {
      int rest = Math.max(0, count - fixed);
      long list = ElxList.create(heap);
      Slot.write(memory, Slot.address(base, fixed), Type.LIST, list);
      for (int i = 0; i < rest; i++) {
        ElxList.append(heap, list, Slot.address(args, fixed + i));
      }
    }
  }

  private void namedCall(long destination, long functionSlot, long arguments, CallSite site) {
    safePoint();
    long function = Slot.bits(memory, functionSlot);
    Type callableType = Slot.type(memory, functionSlot);
    boolean selfCall = false;
    if (callableType == Type.TABLE) {
      long hook = ElxTable.hook(memory, function, Metamethod.CALL.ordinal());
      if (hook == 0) {
        throw new IllegalArgumentException("Table has no call hook");
      }
      function = Slot.bits(memory, hook);
      callableType = Type.FUNCTION;
      selfCall = true;
    }
    if (callableType != Type.FUNCTION || linker.id(function) < 0) {
      throw new IllegalArgumentException("Named calls require a script function with a signature");
    }
    Parameters parameters = linker.image(linker.id(function)).signature();
    if (parameters == null) {
      throw new IllegalArgumentException("Function has no named parameter signature");
    }
    int total = 0;
    for (int i = 0; i < site.names().size(); i++) {
      long value = Slot.address(arguments, i);
      total += switch (site.kinds().get(i)) {
        case POSITIONAL, NAMED -> 1;
        case LIST_SPREAD -> {
          if (Slot.type(memory, value) != Type.LIST) {
            throw new IllegalArgumentException("A positional spread requires a list");
          }
          yield ElxList.size(memory, Slot.bits(memory, value));
        }
        case TABLE_SPREAD -> {
          if (Slot.type(memory, value) != Type.TABLE) {
            throw new IllegalArgumentException("A named spread requires a table");
          }
          yield ElxTable.size(memory, Slot.bits(memory, value));
        }
      };
    }
    int fixed = parameters.fixed(), position = selfCall ? 1 : 0, extra = 0;
    if (selfCall && fixed == 0) {
      throw new IllegalArgumentException("call metamethod must declare a self parameter");
    }
    long roots = regs.enter(fixed + total + 5);
    Slot.write(memory, roots, Type.FUNCTION, function);
    long bound = roots + Slot.SIZE;
    for (int i = 0; i < fixed + total; i++) {
      Slot.write(memory, Slot.address(bound, i), Type.INVALID, 0);
    }
    if (selfCall) {
      Slot.copy(memory, bound, functionSlot, 1);
    }
    long scratch = Slot.address(bound, fixed + total);
    try {
      boolean named = false;
      for (int i = 0; i < site.names().size(); i++) {
        long value = Slot.address(arguments, i);
        switch (site.kinds().get(i)) {
          case POSITIONAL -> {
            if (named) {
              throw new IllegalArgumentException("Positional argument follows a named argument");
            }
            int index = position < fixed ? position : fixed + extra++;
            Slot.copy(memory, Slot.address(bound, index), value, 1);
            position++;
          }
          case LIST_SPREAD -> {
            if (named) {
              throw new IllegalArgumentException("Positional spread follows a named argument");
            }
            long list = Slot.bits(memory, value), data = ElxList.data(memory, list);
            int count = ElxList.size(memory, list);
            for (int j = 0; j < count; j++) {
              int index = position < fixed ? position : fixed + extra++;
              Slot.copy(memory, Slot.address(bound, index), Slot.address(data, j), 1);
              position++;
            }
          }
          case NAMED -> {
            named = true;
            bindNamed(parameters, bound, site.names().get(i), value, fixed);
          }
          case TABLE_SPREAD -> {
            named = true;
            long table = Slot.bits(memory, value), cursor = 0;
            for (; ; ) {
              ElxTable.next(memory, scratch, table, cursor);
              long next = Slot.bits(memory, scratch + 3L * Slot.SIZE);
              if (next == -1) {
                break;
              }
              long key = scratch + Slot.SIZE;
              if (Slot.type(memory, key) != Type.STRING) {
                throw new IllegalArgumentException("Named spread keys must be strings");
              }
              bindNamed(parameters, bound, heap.exportString(Slot.bits(memory, key)), scratch + 2L * Slot.SIZE, fixed);
              cursor = next;
            }
          }
        }
      }
      for (int i = 0; i < fixed; i++) {
        if (Slot.type(memory, Slot.address(bound, i)) == Type.INVALID && !parameters.optional().get(i)) {
          throw new IllegalArgumentException("Missing argument '" + parameters.names().get(i) + "'");
        }
      }
      executeHook(function, bound, fixed + extra, destination, site.resultCapacity());
    } finally {
      regs.leave();
    }
  }

  private void bindNamed(Parameters parameters, long bound, String name, long value, int fixed) {
    int index = parameters.names().indexOf(name);
    if (index < 0 || index >= fixed) {
      throw new IllegalArgumentException("Unknown named argument '" + name + "'");
    }
    long destination = Slot.address(bound, index);
    if (Slot.type(memory, destination) != Type.INVALID) {
      throw new IllegalArgumentException("Duplicate argument '" + name + "'");
    }
    Slot.copy(memory, destination, value, 1);
  }

  private long hookArguments(int count) {
    if (count > 255) {
      throw new IllegalArgumentException("Metamethod argument count exceeds 255");
    }
    int at = depth - 1;
    if (hookCapacities[at] < count) {
      long replacement = arena.allocate(Math.max(3, count) * Slot.SIZE);
      if (hookArgs[at] != 0) {
        arena.free(hookArgs[at]);
      }
      hookArgs[at] = replacement;
      hookCapacities[at] = Math.max(3, count);
    }
    return hookArgs[at];
  }

  private void invokeHook(long cell, long destination, int count, int capacity, int expected) {
    long function = Slot.bits(memory, cell), args = hookArgs[depth - 1];
    safePoint();
    int returned = push(function, args, count, destination, capacity);
    if (returned < 0) {
      returnChecks[depth - 1] = expected;
    } else if (expected >= 0) {
      if (returned == 0 || Slot.type(memory, destination).ordinal() != (expected & 255)) {
        throw new IllegalArgumentException("Metamethod must return " + Type.fromTag((byte) expected));
      }
      if ((expected & INVERT_RESULT) != 0) {
        Slot.write(memory, destination, Type.BOOL, Slot.bits(memory, destination) == 0 ? 1 : 0);
      }
    }
  }

  // O0 runtime entry points complete script hooks synchronously inside the existing VM.
  // This preserves native-call root windows and avoids boxing script values at the ABI.
  private void completeRuntimeHook(int stop) {
    try {
      if (depth > stop) {
        runUntil(stop);
      }
    } catch (RuntimeException | Error failure) {
      if (depth > stop) {
        StackTraceElement[] trace = new StackTraceElement[depth - stop];
        for (int i = depth - 1; i >= stop; i--) {
          Prototype image = linker.image(functions[i]);
          trace[depth - 1 - i] = new StackTraceElement("elx", image.functionName(), image.sourceName(), image.line(Math.max(0, pcs[i] - 1)));
        }
        failure.addSuppressed(new ScriptTrace(trace));
      }
      while (depth > stop) {
        regs.leave();
        depth--;
        frameTokens[depth] = 0;
      }
      throw failure;
    }
  }

  void runtimeReadIndex(long result, long target, long key) {
    int stop = depth;
    readIndex(result, target, key);
    completeRuntimeHook(stop);
  }

  void runtimeWriteIndex(long target, long key, long value) {
    int stop = depth;
    writeIndex(target, key, value);
    completeRuntimeHook(stop);
  }

  void runtimeClosure(long result, long function, long captures, int count) {
    createClosure(result, function, captures, count);
  }

  void runtimeNamed(long result, long function, long args, int site, long context) {
    namedCall(result, function, args, linker.image(linker.id(context)).callSite(site));
  }

  boolean operatorHook(Op op, int relation, long result, long left, long right) {
    int method = Metamethod.operator(op, relation);
    if (method < 0) {
      return false;
    }
    boolean unary = op == Op.NEG || op == Op.POS || op == Op.BNOT || op == Op.NOT || op == Op.INC || op == Op.DEC || op == Op.HASH;
    int count = unary ? 1 : 2;
    try (Slots.Scope arguments = borrowSlots(count)) {
      long args = Slots.address(arguments, 0);
      Slot.copy(memory, args, left, 1);
      if (!unary) {
        Slot.copy(memory, args + Slot.SIZE, right, 1);
      }
      for (int side = 0; side < (unary ? 1 : 2); side++) {
        long target = Slot.address(args, side);
        Type type = Slot.type(memory, target);
        int returned = 0;
        boolean invert = false;
        if (type == Type.EXTERNAL) {
          safePoint();
          returned = external.nmeta(Slot.bits(memory, target), Metamethod.at(method), args, count, result, 1);
          if (returned == 0 && method == Metamethod.NE.ordinal()) {
            returned = external.nmeta(Slot.bits(memory, target), Metamethod.EQ, args, count, result, 1);
            invert = returned != 0;
          }
        } else if (type == Type.TABLE) {
          long hook = ElxTable.hook(memory, Slot.bits(memory, target), method);
          if (hook == 0 && method == Metamethod.NE.ordinal()) {
            hook = ElxTable.hook(memory, Slot.bits(memory, target), Metamethod.EQ.ordinal());
            invert = hook != 0;
          }
          if (hook != 0) {
            returned = executeHook(Slot.bits(memory, hook), args, count, result, 1);
          }
        }
        if (returned == 0) {
          continue;
        }
        if (returned != 1) {
          throw new IllegalArgumentException("Metamethod " + Metamethod.at(method) + " must return one value");
        }
        if (Metamethod.at(method).booleanResult() && Slot.type(memory, result) != Type.BOOL) {
          throw new IllegalArgumentException("Metamethod " + Metamethod.at(method) + " must return BOOL");
        }
        if (invert) {
          Slot.write(memory, result, Type.BOOL, Slot.bits(memory, result) == 0 ? 1 : 0);
        }
        return true;
      }
    }
    return false;
  }

  private int executeHook(long function, long arguments, int count, long destination, int capacity) {
    int returned = execute(function, arguments, count), copied = Math.min(returned, capacity);
    if (copied > 0) {
      Slot.copy(memory, destination, results(), copied);
    }
    clearResults(destination, copied, capacity);
    return returned;
  }

  int callSynchronously(long callable, long inputs, int count, long outputs, int capacity) {
    if (capacity > MAX_RESULTS) {
      throw new IllegalArgumentException("At most sixteen result slots are supported");
    }
    Type type = Slot.type(memory, callable);
    if (type == Type.FUNCTION) {
      return Math.min(executeHook(Slot.bits(memory, callable), inputs, count, outputs, capacity), capacity);
    }
    if (type != Type.TABLE && type != Type.EXTERNAL) {
      throw new IllegalArgumentException("Not callable: " + type);
    }
    try (Slots.Scope args = borrowSlots(count + 1)) {
      long data = Slots.address(args, 0);
      Slot.copy(memory, data, callable, 1);
      if (count > 0) {
        Slot.copy(memory, data + Slot.SIZE, inputs, count);
      }
      if (type == Type.EXTERNAL) {
        int returned = external.nmeta(Slot.bits(memory, data), Metamethod.CALL, data, count + 1, outputs, capacity);
        clearResults(outputs, Math.min(returned, capacity), capacity);
        return Math.min(returned, capacity);
      }
      long hook = ElxTable.hook(memory, Slot.bits(memory, data), Metamethod.CALL.ordinal());
      if (hook == 0) {
        throw new IllegalArgumentException("Table is not callable: missing call metamethod");
      }
      return Math.min(executeHook(Slot.bits(memory, hook), data, count + 1, outputs, capacity), capacity);
    }
  }

  private void readIndex(long result, long target, long key) {
    Type type = Slot.type(memory, target);
    if (type == Type.EXTERNAL) {
      safePoint();
      external.nget(Slot.bits(memory, target), Slot.bits(memory, key), result);
      return;
    }
    if (type == Type.TABLE) {
      readTableIndex(result, Slot.bits(memory, target), key);
      return;
    }
    RawValues.getIndex(memory, target, key, result);
  }

  private void readTableIndex(long result, long table, long key) {
    long hook = ElxTable.hook(memory, table, Metamethod.GET.ordinal());
    if (hook == 0) {
      ElxTable.get(memory, table, key, result);
      return;
    }
    try (Slots.Scope args = borrowSlots(2)) {
      long data = Slots.address(args, 0);
      Slot.write(memory, data, Type.TABLE, table);
      Slot.copy(memory, data + Slot.SIZE, key, 1);
      if (executeHook(Slot.bits(memory, hook), data, 2, result, 1) != 1) {
        throw new IllegalArgumentException("GET metamethod must return one value");
      }
    }
  }

  private void writeIndex(long target, long key, long value) {
    Type type = Slot.type(memory, target);
    if (type == Type.EXTERNAL) {
      safePoint();
      external.nset(Slot.bits(memory, target), Slot.bits(memory, key), value);
      return;
    }
    long hook = type == Type.TABLE ? ElxTable.hook(memory, Slot.bits(memory, target), Metamethod.SET.ordinal()) : 0;
    if (hook == 0) {
      RawValues.setIndex(heap, target, key, value);
      return;
    }
    try (Slots.Scope args = borrowSlots(3)) {
      long data = Slots.address(args, 0);
      Slot.copy(memory, data, target, 1);
      Slot.copy(memory, data + Slot.SIZE, key, 1);
      Slot.copy(memory, data + 2L * Slot.SIZE, value, 1);
      executeHook(Slot.bits(memory, hook), data, 3, 0, 0);
    }
  }

  private void callValue(long destination, long function, long args, int count, int capacity) {
    if (Slot.type(memory, function) == Type.TABLE) {
      long hook = ElxTable.hook(memory, Slot.bits(memory, function), Metamethod.CALL.ordinal());
      if (hook == 0) {
        throw new IllegalArgumentException("Table is not callable: missing call metamethod");
      }
      if (count == 255) {
        throw new IllegalArgumentException("Callable table supports at most 254 explicit arguments");
      }
      long staged = hookArguments(count + 1);
      Slot.copy(memory, staged, function, 1);
      Slot.copy(memory, staged + Slot.SIZE, args, count);
      invokeHook(hook, destination, count + 1, capacity, -1);
    } else if (Slot.type(memory, function) == Type.EXTERNAL) {
      safePoint();
      int returned = callSynchronously(function, args, count, destination, capacity);
      if (returned < 0 || returned > capacity) {
        throw new IllegalStateException("External call returned an invalid result count: " + returned);
      }
      clearResults(destination, returned, capacity);
    } else {
      if (Slot.type(memory, function) != Type.FUNCTION) {
        throw new IllegalArgumentException("Not a function: " + Slot.type(memory, function));
      }
      push(Slot.bits(memory, function), args, count, destination, capacity);
    }
  }

  private boolean condition(long address) {
    if (Slot.type(memory, address) != Type.BOOL) {
      throw new IllegalArgumentException("Branch requires BOOL, got " + Slot.type(memory, address));
    }
    return Slot.bits(memory, address) != 0;
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("VM is closed");
    }
  }

  /**
   * Closes modules, linked images, and VM-owned roots; borrowed resources remain
   * caller-owned. Repeated close calls are harmless.
   *
   * @throws IllegalStateException if execution is still active
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    if (depth != 0 || invocationDepth != 0) {
      throw new IllegalStateException("Cannot close an executing VM");
    }
    for (long window : results) {
      if (window != 0) {
        arena.free(window);
      }
    }
    for (long args : hookArgs) {
      if (args != 0) {
        arena.free(args);
      }
    }
    modules.close();
    linker.close();
    lastResults = 0;
    closed = true;
  }
}
