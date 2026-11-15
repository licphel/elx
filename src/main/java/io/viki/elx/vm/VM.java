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
import io.viki.elx.memory.*;
import io.viki.elx.value.*;
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
  private static final int MAX_RESULTS = 4;
  private static final int POLL_MASK = 255;
  private static final int INVERT_RESULT = 1 << 8;
  private static final ElxHeap.Layout[] LAYOUTS = ElxHeap.Layout.values();
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
   * @param heap internal ELX heap
   * @param external external Java heap sharing the internal memory
   * @param regs reusable register-window stack
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

  private static boolean compare(int comparison, Instr.Relation relation) {
    return switch (relation) {
      case EQ -> comparison == 0;
      case NE -> comparison != 0;
      case LT -> comparison < 0;
      case LE -> comparison <= 0;
      case GT -> comparison > 0;
      case GE -> comparison >= 0;
    };
  }

  private static boolean compareFloat(double x, double y, Instr.Relation relation) {
    return switch (relation) {
      case EQ -> x == y;
      case NE -> x != y;
      case LT -> x < y;
      case LE -> x <= y;
      case GT -> x > y;
      case GE -> x >= y;
    };
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
   *         {@link Optimization#O1}; returns O1 when no frame is active
   */
  public Optimization optimization() {
    return depth > 0 && linker.image(functions[depth - 1]).baseOnly() ? Optimization.O0 : Optimization.O1;
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
   * @throws IllegalStateException if the VM is closed
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
   * @param name UTF-8 global name
   * @param result destination slot
   * @throws NullPointerException if {@code name} is null
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
   * @param name UTF-8 global name
   * @param value source slot
   * @throws NullPointerException if {@code name} is null
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
   * @throws IllegalStateException if the VM is closed
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
   * @return number of returned values, from zero through four
   * @throws IllegalStateException if the VM is closed or execution fails
   */
  public int execute(Prototype image) {
    return execute(image, 0, 0);
  }

  /**
   * Executes a compiled image using a contiguous input-slot window.
   *
   * @param image compiled image
   * @param args input-slot base address
   * @param count number of input slots
   * @return number of returned values, from zero through four
   * @throws IllegalArgumentException if the image rejects the argument count
   * @throws IllegalStateException if the VM is closed or execution fails
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
   * @param args input-slot base address
   * @param count number of input slots
   * @return number of returned values, from zero through four
   * @throws IllegalArgumentException if the handle or argument count is invalid
   * @throws IllegalStateException if the VM is closed, execution exceeds its
   *                               budget, or a script operation fails
   */
  public int execute(long function, long args, int count) {
    checkOpen();
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
      Slot.write(memory, Slot.address(base, i), Type.NULL, 0);
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
      for (long window : results) {
        if (window != 0) {
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
    while (depth > stop) {
      int frame = depth - 1, id = functions[frame], pc = pcs[frame];
      long instructionBase = linker.code(id);
      int word = memory.load32(instructionBase + (long) pc * Instr.WORD_BYTES);
      Op op = Instr.op(word);
      pcs[frame] = pc + op.format().words();
      if ((++executed & POLL_MASK) == 0) {
        safePoint();
      }
      long base = bases[frame];
      int registerMask = op.registerMask();
      // Match the A/B/C bits used by prototype validation; immediates are not addresses.
      long a = (registerMask & 1) != 0 ? Slot.address(base, Instr.a(word)) : 0;
      long b = (registerMask & 2) != 0 ? Slot.address(base, Instr.b(word)) : 0;
      long c = (registerMask & 4) != 0 ? Slot.address(base, Instr.c(word)) : 0;
      switch (op) {
        case FRAME_ADDR -> Slot.write(memory, a, Type.PTR, base);
        case FRAME_CONTEXT, CX_SELF -> Slot.write(memory, a, Type.FUNCTION, handles[frame]);
        case ECALL -> environmentCall(a, b, c, memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
        case BR_ZERO -> {
          if (Slot.bits(memory, a) == 0) {
            pcs[frame] = pc + Instr.branchOffset(word) / Instr.WORD_BYTES;
          }
        }
        case BR_NONZERO -> {
          if (Slot.bits(memory, a) != 0) {
            pcs[frame] = pc + Instr.branchOffset(word) / Instr.WORD_BYTES;
          }
        }
        case CX_BR_PRESENT -> {
          if (Slot.type(memory, a) != Type.INVALID) {
            pcs[frame] = pc + Instr.branchOffset(word) / Instr.WORD_BYTES;
          }
        }
        case CX_CALL_NAMED -> namedCall(a, b, c, linker.image(id).callSite(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES)));
        case CX_RETURN_INDIRECT -> {
          int count = Math.toIntExact(Slot.bits(memory, b));
          if (count < 0 || count > MAX_RESULTS) {
            throw new IllegalArgumentException("At most four return values are supported");
          }
          finishValues(Slot.bits(memory, a), count);
        }
        case CX_CALL_INDIRECT -> indirectCall(a, b, c, base, memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
        case CX_LOCAL_PTR -> Slot.write(memory, a, Type.PTR, Pointers.local(heap, frameTokens[frame], Instr.b(word)));
        case CX_INDEX_PTR -> {
          safePoint();
          Slot.write(memory, a, Type.PTR, Pointers.indexed(heap, b, c));
        }
        case CX_GLOBAL_PTR -> {
          safePoint();
          Slot.write(memory, a, Type.PTR, Pointers.global(heap, globals, b));
        }
        case CX_WRITE_PTR -> pointerWrite(a, b);
        case CX_CAPTURE_PTR -> Slot.write(memory, a, Type.PTR, Pointers.capture(heap, handles[frame], memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES)));
        case CX_CONST_PTR -> Slot.write(memory, a, Type.PTR, Pointers.constant(heap, linker.constants(id), Instr.index(word)));
        case CX_ENV -> Slot.write(memory, a, Type.TABLE, globals);
        case CX_GET_GLOBAL_CONST ->
            ElxTable.get(memory, globals, Slot.address(linker.constants(id), Instr.index(word)), a);
        case CX_GET_GLOBAL -> ElxTable.get(memory, globals, b, a);
        case CX_SET_GLOBAL -> ElxTable.set(heap, globals, a, b);
        case NOP -> {
        }
        case MOVE -> Slot.copy(memory, a, b, 1);
        case LC -> Slot.copy(memory, a, Slot.address(linker.constants(id), Instr.index(word)), 1);
        case CX_TYPE -> Slot.write(memory, a, Type.UBYTE, Byte.toUnsignedInt(Slot.type(memory, b).tag()));
        case CX_BITS -> Slot.write(memory, a, Type.ULONG, Slot.bits(memory, b));
        case CX_LEN -> Slot.write(memory, a, Type.INT, ValueOperations.length(memory, b));
        case CX_PACK -> Slot.write(memory, a, Type.fromTag((byte) Slot.bits(memory, b)), Slot.bits(memory, c));
        case CX_CONVERT -> convertValue(a, b, memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
        case CX_TO_STRING -> Slot.write(memory, a, Type.STRING, heap.importString(format(b)));
        case CX_GUARD -> {
          Type expected = Type.fromTag((byte) memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          if (Slot.type(memory, a) != expected) {
            throw new IllegalArgumentException("Expected " + expected + ", got " + Slot.type(memory, a));
          }
        }
        case IADD -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
          Slot.write(memory, a, kind, Type.normalize(x + y, kind));
        }
        case ISUB -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
          Slot.write(memory, a, kind, Type.normalize(x - y, kind));
        }
        case IMUL -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
          Slot.write(memory, a, kind, Type.normalize(x * y, kind));
        }
        case IDIV -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Type.normalize(Slot.bits(memory, b), kind), y = Type.normalize(Slot.bits(memory, c), kind);
          Slot.write(memory, a, kind, Type.normalize(x / y, kind));
        }
        case UDIV -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long mask = -1L >>> (64 - kind.width());
          long x = Slot.bits(memory, b) & mask, y = Slot.bits(memory, c) & mask;
          Slot.write(memory, a, kind, Type.normalize(Long.divideUnsigned(x, y), kind));
        }
        case IREM -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Type.normalize(Slot.bits(memory, b), kind), y = Type.normalize(Slot.bits(memory, c), kind);
          Slot.write(memory, a, kind, Type.normalize(x % y, kind));
        }
        case UREM -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long mask = -1L >>> (64 - kind.width());
          long x = Slot.bits(memory, b) & mask, y = Slot.bits(memory, c) & mask;
          Slot.write(memory, a, kind, Type.normalize(Long.remainderUnsigned(x, y), kind));
        }
        case BAND -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
          Slot.write(memory, a, kind, Type.normalize(x & y, kind));
        }
        case BOR -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
          Slot.write(memory, a, kind, Type.normalize(x | y, kind));
        }
        case BXOR -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
          Slot.write(memory, a, kind, Type.normalize(x ^ y, kind));
        }
        case SHL -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
          Slot.write(memory, a, kind, Type.normalize(x << ((int) y & (kind.width() - 1)), kind));
        }
        case SHR -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Type.normalize(Slot.bits(memory, b), kind), y = Type.normalize(Slot.bits(memory, c), kind);
          Slot.write(memory, a, kind, Type.normalize(x >> ((int) y & (kind.width() - 1)), kind));
        }
        case USHR -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long x = Type.normalize(Slot.bits(memory, b), kind), y = Type.normalize(Slot.bits(memory, c), kind);
          Slot.write(memory, a, kind, Type.normalize((kind.width() == 64 ? x >>> ((int) y & 63) : (x & ((1L << kind.width()) - 1)) >>> ((int) y & (kind.width() - 1))), kind));
        }
        case INEG -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          Slot.write(memory, a, kind, Type.normalize(-Slot.bits(memory, b), kind));
        }
        case BNOT -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          Slot.write(memory, a, kind, Type.normalize(~Slot.bits(memory, b), kind));
        }
        case FNEG -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          long sign = kind == Type.FLOAT ? 0x80000000L : Long.MIN_VALUE;
          Slot.write(memory, a, kind, Slot.bits(memory, b) ^ sign);
        }
        case FADD -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          if (kind == Type.FLOAT) {
            float x = Slot.getFloat(memory, b), y = Slot.getFloat(memory, c);
            Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(x + y)));
          } else {
            Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) + Slot.getDouble(memory, c)));
          }
        }
        case FSUB -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          if (kind == Type.FLOAT) {
            float x = Slot.getFloat(memory, b), y = Slot.getFloat(memory, c);
            Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(x - y)));
          } else {
            Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) - Slot.getDouble(memory, c)));
          }
        }
        case FMUL -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          if (kind == Type.FLOAT) {
            float x = Slot.getFloat(memory, b), y = Slot.getFloat(memory, c);
            Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(x * y)));
          } else {
            Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) * Slot.getDouble(memory, c)));
          }
        }
        case FDIV -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          if (kind == Type.FLOAT) {
            float x = Slot.getFloat(memory, b), y = Slot.getFloat(memory, c);
            Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(x / y)));
          } else {
            Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) / Slot.getDouble(memory, c)));
          }
        }
        case FREM -> {
          Type kind = Instr.type(memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
          if (kind == Type.FLOAT) {
            float x = Slot.getFloat(memory, b), y = Slot.getFloat(memory, c);
            Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(x % y)));
          } else {
            Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) % Slot.getDouble(memory, c)));
          }
        }
        case CX_BOOL_NOT -> {
          if (!operatorHook(Op.CX_BOOL_NOT.ordinal(), a, b, b)) {
            Slot.write(memory, a, Type.BOOL, condition(b) ? 0 : 1);
          }
        }
        case ICMP -> {
          int control = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          Type kind = Instr.type(control);
          long x = Type.normalize(Slot.bits(memory, b), kind), y = Type.normalize(Slot.bits(memory, c), kind);
          Slot.write(memory, a, Type.BOOL, compare(Long.compare(x, y), Instr.relation(control)) ? 1 : 0);
        }
        case UCMP -> {
          int control = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          Type kind = Instr.type(control);
          long mask = -1L >>> (64 - kind.width());
          long x = Slot.bits(memory, b) & mask, y = Slot.bits(memory, c) & mask;
          Slot.write(memory, a, Type.BOOL, compare(Long.compareUnsigned(x, y), Instr.relation(control)) ? 1 : 0);
        }
        case FCMP -> {
          int control = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          Type kind = Instr.type(control);
          Slot.write(memory, a, Type.BOOL, compareFloat(Type.asDouble(Slot.bits(memory, b), kind), Type.asDouble(Slot.bits(memory, c), kind), Instr.relation(control)) ? 1 : 0);
        }
        case EQ64 -> Slot.write(memory, a, Type.BOOL, Slot.bits(memory, b) == Slot.bits(memory, c) ? 1 : 0);
        case CX_ALLOC -> {
          safePoint();
          long pointer = heap.allocate(LAYOUTS[Math.toIntExact(Slot.bits(memory, c))], Math.toIntExact(Slot.bits(memory, b)));
          Slot.write(memory, a, Type.PTR, pointer);
        }
        case LEA -> Slot.write(memory, a, Type.PTR, Slot.bits(memory, b) + Slot.bits(memory, c));
        case LOAD8 -> Slot.write(memory, a, Type.UBYTE, Byte.toUnsignedLong(memory.load8(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES))));
        case LOAD16 -> Slot.write(memory, a, Type.USHORT, Short.toUnsignedLong(memory.load16(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES))));
        case LOAD32 -> Slot.write(memory, a, Type.UINT, Integer.toUnsignedLong(memory.load32(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES))));
        case LOAD64 -> Slot.write(memory, a, Type.ULONG, memory.load64(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES)));
        case STORE8 -> memory.store8(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES), (byte) Slot.bits(memory, a));
        case STORE16 -> memory.store16(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES), (short) Slot.bits(memory, a));
        case STORE32 -> memory.store32(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES), (int) Slot.bits(memory, a));
        case STORE64 -> memory.store64(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES), Slot.bits(memory, a));
        case BOUNDS -> {
          long index = Slot.bits(memory, a), length = Slot.bits(memory, b);
          if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("ELX index " + index + " / " + length);
          }
        }
        case CX_SAFEPOINT -> safePoint();
        case TRAP -> throw new IllegalStateException("ELX trap " + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES) + " at word " + pc);
        case JUMP -> pcs[frame] = pc + Instr.jumpOffset(word) / Instr.WORD_BYTES;
        case CX_BR_TRUE -> {
          if (condition(a)) {
            pcs[frame] = pc + Instr.branchOffset(word) / Instr.WORD_BYTES;
          }
        }
        case CX_BR_FALSE -> {
          if (!condition(a)) {
            pcs[frame] = pc + Instr.branchOffset(word) / Instr.WORD_BYTES;
          }
        }
        case CX_CALL -> {
          safePoint();
          int control = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          callValue(a, b, c, Instr.argumentCount(control), Instr.resultCount(control));
        }
        case CX_RETURN -> finish(Instr.a(word), Instr.b(word));
        case CX_EX_CALL -> hostCall(a, b, c, base, memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
        case CX_EX_GET -> {
          safePoint();
          external.nget(Slot.bits(memory, b), Slot.bits(memory, c), a);
        }
        case CX_EX_SET -> {
          safePoint();
          external.nset(Slot.bits(memory, a), Slot.bits(memory, b), c);
        }
        case CX_EX_ADDRESS -> {
          safePoint();
          long pointer = external.naddress(Slot.bits(memory, b), Slot.bits(memory, c));
          Slot.write(memory, a, Type.PTR, pointer);
        }
        case CX_EX_RETAIN -> external.retain(Slot.bits(memory, a));
        case CX_EX_RELEASE -> external.release(Slot.bits(memory, a));
        case CX_ADD -> dynamicAdd(a, b, c);
        case CX_SUB -> dynamicSub(a, b, c);
        case CX_MUL -> dynamicMul(a, b, c);
        case CX_DIV -> dynamicDiv(a, b, c);
        case CX_REM -> dynamicRem(a, b, c);
        case CX_AND -> dynamicAnd(a, b, c);
        case CX_OR -> dynamicOr(a, b, c);
        case CX_XOR -> dynamicXor(a, b, c);
        case CX_SHL -> dynamicShl(a, b, c);
        case CX_SHR -> dynamicShr(a, b, c);
        case CX_USHR -> dynamicUshr(a, b, c);
        case CX_GET_INDEX_CONST ->
            readIndex(a, b, Slot.address(linker.constants(id), memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES)));
        case CX_BR_CMP -> {
          int control = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          if (branchCompare(a, b, c, control)) {
            pcs[frame] = pc + memory.load32(instructionBase + (long) (pc + 2) * Instr.WORD_BYTES) / Instr.WORD_BYTES;
          }
        }
        case CX_ADD_CONST ->
            dynamicAdd(a, b, Slot.address(linker.constants(id), memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES)));
        case CX_SUB_CONST ->
            dynamicSub(a, b, Slot.address(linker.constants(id), memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES)));
        case CX_CMP -> dynamicCmp(a, b, c, memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
        case CX_DEREF -> {
          int wanted = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          if (Slot.type(memory, b) == Type.FUNCTION) {
            if (wanted != 1) {
              throw new IllegalArgumentException("A function address expands to one value");
            }
            Slot.write(memory, a, Type.PTR, Pointers.local(heap, frameTokens[frame], Instr.b(word)));
            break;
          }
          int count = Pointers.dereferenceCount(memory, b);
          if (wanted > count) {
            throw new IllegalArgumentException("Unpack needs " + wanted + " values, sequence has " + count);
          }
          Slot.copy(memory, a, pointerData(b), wanted);
        }
        case CX_RETURN_DEREF -> {
          int count = Instr.b(word) == 1 ? Pointers.argumentCount(memory, a) : Pointers.dereferenceCount(memory, a);
          if (count > MAX_RESULTS) {
            throw new IllegalArgumentException("At most four return values are supported");
          }
          finishValues(Instr.b(word) == 1 ? Pointers.argumentData(heap, a, frameTokens, bases, depth) : pointerData(a), count);
        }
        case CX_APPEND_DEREF -> {
          long target = Slot.bits(memory, a), data = pointerData(b);
          int count = Pointers.dereferenceCount(memory, b);
          for (int i = 0; i < count; i++) {
            ElxList.append(heap, target, Slot.address(data, i));
          }
        }
        case CX_CALL_PACKED -> {
          safePoint();
          int control = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          boolean list = (control & 256) != 0;
          int count = list ? Pointers.argumentCount(memory, c) : Pointers.dereferenceCount(memory, c);
          if (count > 255) {
            throw new IllegalArgumentException("Call argument count exceeds 255");
          }
          callValue(a, b, list ? Pointers.argumentData(heap, c, frameTokens, bases, depth) : pointerData(c), count, control & 255);
        }
        case CX_INCR -> {
          if (!operatorHook(Op.CX_INCR.ordinal(), a, b, b)) {
            ValueOperations.increment(memory, a, b, 1);
          }
        }
        case CX_DECR -> {
          if (!operatorHook(Op.CX_DECR.ordinal(), a, b, b)) {
            ValueOperations.increment(memory, a, b, -1);
          }
        }
        case CX_POS -> {
          if (!operatorHook(Op.CX_POS.ordinal(), a, b, b)) {
            ValueOperations.operator(heap, Op.CX_POS.ordinal(), a, b, b);
          }
        }
        case CX_CLOSURE -> createClosure(a, b, c, memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES));
        case CX_GET_CAPTURE ->
            Slot.copy(memory, a, Slot.address(handles[frame], memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES) + 1), Instr.b(word));
        case CX_SET_CAPTURE ->
            Slot.copy(memory, Slot.address(handles[frame], memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES) + 1), a, Instr.b(word));
        case CX_GET_INDEX -> readIndex(a, b, c);
        case CX_SET_INDEX -> writeIndex(a, b, c);
        case CX_SET_META -> {
          if (Slot.type(memory, b) != Type.TABLE || (Slot.type(memory, c) != Type.TABLE && Slot.type(memory, c) != Type.NULL)) {
            throw new IllegalArgumentException("setmetatable requires table and table/null");
          }
          long table = Slot.bits(memory, b), meta = Slot.type(memory, c) == Type.NULL ? 0 : Slot.bits(memory, c);
          ElxTable.setMetatable(heap, table, meta);
          Slot.write(memory, a, Type.TABLE, table);
        }
        case CX_GET_META -> {
          if (Slot.type(memory, b) != Type.TABLE) {
            throw new IllegalArgumentException("getmetatable requires table");
          }
          long meta = ElxTable.metatable(memory, Slot.bits(memory, b));
          Slot.write(memory, a, meta == 0 ? Type.NULL : Type.TABLE, meta);
        }
        case CX_NEXT -> nextValue(a, b, c);
        case CX_IADD_CONST -> {
          Type kind = Type.fromTag((byte) Instr.c(word));
          Slot.write(memory, a, kind, Type.normalize(Slot.bits(memory, b) + memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES), kind));
        }
        case CX_BR_ICMP -> {
          int control = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          Type kind = Instr.type(control);
          long x = Type.normalize(Slot.bits(memory, a), kind), y = Type.normalize(Slot.bits(memory, b), kind);
          if (compare(Long.compare(x, y), Instr.relation(control))) {
            pcs[frame] = pc + memory.load32(instructionBase + (long) (pc + 2) * Instr.WORD_BYTES) / Instr.WORD_BYTES;
          }
        }
        case CX_LOAD64_INDEXED -> Slot.write(memory, a, Type.ULONG, memory.load64(Slot.bits(memory, b) + Slot.bits(memory, c) * Long.BYTES));
        case CX_COPY_MEMORY -> memory.copyBytes(Slot.bits(memory, a), Slot.bits(memory, b), Math.toIntExact(Slot.bits(memory, c)));
        case CX_EX_CALL0 -> {
          safePoint();
          int count;
          if (Slot.type(memory, b) == Type.FUNCTION) {
            long function = Slot.bits(memory, b);
            if (linker.id(function) >= 0) {
              throw new IllegalArgumentException("CX_EX_CALL0 requires a native function");
            }
            count = callNative(function, 0, 0, a, 1);
          } else {
            count = external.ncall(Slot.bits(memory, b), Slot.bits(memory, c), 0, 0, a, 1);
          }
          if (count < 0) {
            throw new IllegalStateException("External call failed: " + count);
          }
          clearResults(a, count, 1);
        }
        case CX_HASH -> Slot.write(memory, a, Type.LONG, ValueOperations.hash(memory, b));
        case CX_EQUAL -> {
          if (!operatorHook(Op.CX_EQUAL.ordinal(), a, b, c)) {
            Slot.write(memory, a, Type.BOOL, ValueOperations.equal(memory, b, c) ? 1 : 0);
          }
        }
        case CX_OPERATOR -> {
          int control = memory.load32(instructionBase + (long) (pc + 1) * Instr.WORD_BYTES);
          if (!operatorHook(control, a, b, c)) {
            ValueOperations.operator(heap, control, a, b, c);
          }
        }
        case CX_GET_TABLE -> ElxTable.get(memory, Slot.bits(memory, b), c, a);
        case CX_SET_TABLE -> ElxTable.set(heap, Slot.bits(memory, a), b, c);
        case CX_LIST_APPEND -> ElxList.append(heap, Slot.bits(memory, a), b);
        case CX_GET_TABLE_IKV -> ElxTable.next(memory, a, Slot.bits(memory, b), Slot.bits(memory, c));
        case CX_GET_LIST_IV -> ElxList.next(memory, a, Slot.bits(memory, b), Slot.bits(memory, c));
      }
    }
  }

  private void environmentCall(long serviceSlot, long args, long results, int control) {
    safePoint();
    long service = Slot.bits(memory, serviceSlot);
    int count = Instr.argumentCount(control), capacity = Instr.resultCount(control);
    if (linker.id(service) >= 0) {
      throw new IllegalArgumentException("ECALL requires an execution-environment service address");
    }
    int returned = external.ncall(linker.nativeTarget(service), 0, args, count, results, capacity);
    if (returned < 0) {
      throw new IllegalStateException("Execution-environment service failed: " + returned);
    }
    clearResults(results, returned, capacity);
  }

  private void hostCall(long result, long target, long member, long base, int control) {
    runtimeHostCall(result, target, member, Slot.address(base, Instr.argumentBase(control)), control);
  }

  void runtimeHostCall(long result, long target, long member, long args, int control) {
    safePoint();
    int capacity = Instr.resultCount(control), count;
    if (Slot.type(memory, target) == Type.FUNCTION) {
      long function = Slot.bits(memory, target);
      if (linker.id(function) >= 0) {
        throw new IllegalArgumentException("CX_EX_CALL requires a native function");
      }
      count = callNative(function, args, Instr.argumentCount(control), result, capacity);
    } else {
      count = external.ncall(Slot.bits(memory, target), Slot.bits(memory, member), args, Instr.argumentCount(control), result, capacity);
    }
    if (count < 0) {
      throw new IllegalStateException("External call failed: " + count);
    }
    clearResults(result, count, capacity);
  }

  void runtimeCall(long result, long function, long args, int count, int capacity) {
    if (count < 0 || count > 255) {
      throw new IllegalArgumentException("Call argument count exceeds 255");
    }
    callValue(result, function, args, count, capacity);
  }

  void runtimeReturn(long data, int count) {
    if (count < 0 || count > MAX_RESULTS) {
      throw new IllegalArgumentException("At most four return values are supported");
    }
    finishValues(data, count);
  }

  void runtimeConvert(long result, long source, int kind) {
    int stop = depth;
    convertValue(result, source, kind);
    completeRuntimeHook(stop);
  }

  void runtimeAllocate(long result, long bytes, long layout) {
    Slot.write(memory, result, Type.PTR, heap.allocate(LAYOUTS[Math.toIntExact(Slot.bits(memory, layout))], Math.toIntExact(Slot.bits(memory, bytes))));
  }

  void runtimePoll() {
    safePoint();
  }

  private void indirectCall(long result, long function, long args, long base, int control) {
    safePoint();
    int count = Math.toIntExact(Slot.bits(memory, Slot.address(base, control & 255)));
    if (count < 0 || count > 255) {
      throw new IllegalArgumentException("Call argument count exceeds 255");
    }
    callValue(result, function, Slot.bits(memory, args), count, (control >>> 8) & 255);
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

  private void nextValue(long a, long b, long c) {
    Type type = Slot.type(memory, b);
    long pointer = Slot.bits(memory, b), cursor = Slot.bits(memory, c);
    if (type == Type.TABLE) {
      ElxTable.next(memory, a, pointer, cursor);
    } else if (type == Type.LIST) {
      ElxList.next(memory, a + Slot.SIZE, pointer, cursor);
      Slot.copy(memory, a, a + Slot.SIZE, 1);
    } else {
      throw new IllegalArgumentException("Not iterable: " + type);
    }
  }

  private void convertValue(long a, long b, int type) {
    Type target = Type.fromTag((byte) type);
    if (target == Type.BOOL && Slot.type(memory, b) == Type.TABLE) {
      long hook = ElxTable.hook(memory, Slot.bits(memory, b), Metamethod.BOOL.ordinal());
      if (hook == 0) {
        throw new IllegalArgumentException("Table has no __bool__ conversion");
      }
      long args = hookArguments(1);
      Slot.copy(memory, args, b, 1);
      invokeHook(hook, a, 1, 1, Type.BOOL.ordinal());
    } else if (target == Type.BOOL && Slot.type(memory, b) == Type.EXTERNAL) {
      long args = hookArguments(1);
      Slot.copy(memory, args, b, 1);
      safePoint();
      int returned = external.nmeta(Slot.bits(memory, b), Metamethod.BOOL, args, 1, a, 1);
      if (returned != 1 || Slot.type(memory, a) != Type.BOOL) {
        throw new IllegalArgumentException("External bool metamethod must return one BOOL");
      }
    } else {
      Slot.write(memory, a, target, Type.cast(Slot.bits(memory, b), Slot.type(memory, b), target));
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
      int returned = execute(function, bound, fixed + extra);
      Slot.copy(memory, destination, results(), returned);
      clearResults(destination, returned, MAX_RESULTS);
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

  // Keep the dispatch loop below HotSpot's huge-method compilation limit.
  private void dynamicAdd(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(x + y, kind));
    } else if (kind == right && kind == Type.FLOAT) {
      Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(Slot.getFloat(memory, b) + Slot.getFloat(memory, c))));
    } else if (kind == right && kind == Type.DOUBLE) {
      Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) + Slot.getDouble(memory, c)));
    } else if (!operatorHook(Op.IADD.ordinal(), a, b, c)) {
      if (kind == Type.STRING || right == Type.STRING) {
        Slot.write(memory, a, Type.STRING, heap.importString(format(b) + format(c)));
      } else {
        ValueOperations.operator(heap, Op.IADD.ordinal(), a, b, c);
      }
    }
  }

  private void dynamicSub(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(x - y, kind));
    } else if (kind == right && kind == Type.FLOAT) {
      Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(Slot.getFloat(memory, b) - Slot.getFloat(memory, c))));
    } else if (kind == right && kind == Type.DOUBLE) {
      Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) - Slot.getDouble(memory, c)));
    } else if (!operatorHook(Op.ISUB.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.ISUB.ordinal(), a, b, c);
    }
  }

  private void dynamicMul(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(x * y, kind));
    } else if (kind == right && kind == Type.FLOAT) {
      Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(Slot.getFloat(memory, b) * Slot.getFloat(memory, c))));
    } else if (kind == right && kind == Type.DOUBLE) {
      Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) * Slot.getDouble(memory, c)));
    } else if (!operatorHook(Op.IMUL.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.IMUL.ordinal(), a, b, c);
    }
  }

  private void dynamicDiv(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(kind.unsigned() ? Long.divideUnsigned(x, y) : x / y, kind));
    } else if (kind == right && kind == Type.FLOAT) {
      Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(Slot.getFloat(memory, b) / Slot.getFloat(memory, c))));
    } else if (kind == right && kind == Type.DOUBLE) {
      Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) / Slot.getDouble(memory, c)));
    } else if (!operatorHook(Op.IDIV.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.IDIV.ordinal(), a, b, c);
    }
  }

  private void dynamicRem(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(kind.unsigned() ? Long.remainderUnsigned(x, y) : x % y, kind));
    } else if (kind == right && kind == Type.FLOAT) {
      Slot.write(memory, a, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(Slot.getFloat(memory, b) % Slot.getFloat(memory, c))));
    } else if (kind == right && kind == Type.DOUBLE) {
      Slot.write(memory, a, kind, Double.doubleToRawLongBits(Slot.getDouble(memory, b) % Slot.getDouble(memory, c)));
    } else if (!operatorHook(Op.IREM.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.IREM.ordinal(), a, b, c);
    }
  }

  private void dynamicAnd(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(x & y, kind));
    } else if (!operatorHook(Op.BAND.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.BAND.ordinal(), a, b, c);
    }
  }

  private void dynamicOr(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(x | y, kind));
    } else if (!operatorHook(Op.BOR.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.BOR.ordinal(), a, b, c);
    }
  }

  private void dynamicXor(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(x ^ y, kind));
    } else if (!operatorHook(Op.BXOR.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.BXOR.ordinal(), a, b, c);
    }
  }

  private void dynamicShl(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(x << ((int) y & (kind.width() - 1)), kind));
    } else if (!operatorHook(Op.SHL.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.SHL.ordinal(), a, b, c);
    }
  }

  private void dynamicShr(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize(x >> ((int) y & (kind.width() - 1)), kind));
    } else if (!operatorHook(Op.SHR.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.SHR.ordinal(), a, b, c);
    }
  }

  private void dynamicUshr(long a, long b, long c) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      Slot.write(memory, a, kind, Type.normalize((kind.width() == 64 ? x : x & (-1L >>> (64 - kind.width()))) >>> ((int) y & (kind.width() - 1)), kind));
    } else if (!operatorHook(Op.USHR.ordinal(), a, b, c)) {
      ValueOperations.operator(heap, Op.USHR.ordinal(), a, b, c);
    }
  }

  private boolean branchCompare(long scratch, long left, long right, int control) {
    Type kind = Slot.type(memory, left), other = Slot.type(memory, right);
    Instr.Relation relation = Instr.relation(control);
    boolean result;
    if (kind == other && kind.integer()) {
      long x = Slot.bits(memory, left), y = Slot.bits(memory, right);
      result = compare(kind.unsigned() ? Long.compareUnsigned(x, y) : Long.compare(x, y), relation);
    } else if (kind == other && kind.numeric()) {
      result = compareFloat(Type.asDouble(Slot.bits(memory, left), kind), Type.asDouble(Slot.bits(memory, right), kind), relation);
    } else {
      // Only overloaded comparisons need a materialized result and a synchronous slow-path hook.
      runtimeOperator(Instr.operator(Op.ICMP, relation), scratch, left, right);
      result = condition(scratch);
    }
    return result == ((control & Instr.BRANCH_FALSE) == 0);
  }

  private void dynamicCmp(long a, long b, long c, int control) {
    Type kind = Slot.type(memory, b), right = Slot.type(memory, c);
    Instr.Relation relation = Instr.relation(control);
    if (kind == right && kind.integer()) {
      long x = Slot.bits(memory, b), y = Slot.bits(memory, c);
      int comparison = kind.unsigned() ? Long.compareUnsigned(x, y) : Long.compare(x, y);
      Slot.write(memory, a, Type.BOOL, compare(comparison, relation) ? 1 : 0);
    } else if (kind == right && kind.numeric()) {
      Slot.write(memory, a, Type.BOOL, compareFloat(Type.asDouble(Slot.bits(memory, b), kind), Type.asDouble(Slot.bits(memory, c), kind), relation) ? 1 : 0);
    } else {
      int comparison = Instr.operator(Op.ICMP, relation);
      if (!operatorHook(comparison, a, b, c)) {
        ValueOperations.operator(heap, comparison, a, b, c);
      }
    }
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

  void runtimeOperator(int control, long result, long left, long right) {
    int stop = depth;
    if (operatorHook(control, result, left, right)) {
      completeRuntimeHook(stop);
      return;
    }
    Op op = Instr.op(control);
    if (op == Op.IADD && (Slot.type(memory, left) == Type.STRING || Slot.type(memory, right) == Type.STRING)) {
      Slot.write(memory, result, Type.STRING, heap.importString(format(left) + format(right)));
    } else if (op == Op.CX_INCR || op == Op.CX_DECR) {
      ValueOperations.increment(memory, result, left, op == Op.CX_INCR ? 1 : -1);
    } else if (op == Op.CX_EQUAL) {
      Slot.write(memory, result, Type.BOOL, ValueOperations.equal(memory, left, right) ? 1 : 0);
    } else {
      ValueOperations.operator(heap, control, result, left, right);
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

  void runtimeNext(long result, long target, long cursor) {
    nextValue(result, target, cursor);
  }

  private boolean operatorHook(int control, long result, long left, long right) {
    int method = Metamethod.operator(control);
    if (method < 0) {
      return false;
    }
    Type leftType = Slot.type(memory, left), rightType = Slot.type(memory, right);
    if (leftType == Type.EXTERNAL || rightType == Type.EXTERNAL) {
      long target = leftType == Type.EXTERNAL ? left : right;
      Op op = Instr.op(control);
      int count = op == Op.INEG || op == Op.FNEG || op == Op.BNOT || op == Op.CX_BOOL_NOT
          || op == Op.CX_POS || op == Op.CX_INCR || op == Op.CX_DECR ? 1 : 2;
      long args = hookArguments(count);
      Slot.copy(memory, args, left, 1);
      if (count == 2) {
        Slot.copy(memory, args + Slot.SIZE, right, 1);
      }
      safePoint();
      int returned = external.nmeta(Slot.bits(memory, target), Metamethod.at(method), args, count, result, 1);
      if (returned != 1) {
        throw new IllegalArgumentException("External metamethod " + Metamethod.at(method)
            + " must return one value, got " + returned);
      }
      if (Metamethod.at(method).booleanResult() && Slot.type(memory, result) != Type.BOOL) {
        throw new IllegalArgumentException("External metamethod " + Metamethod.at(method)
            + " must return BOOL");
      }
      return true;
    }
    long cell = Slot.type(memory, left) == Type.TABLE ? ElxTable.hook(memory, Slot.bits(memory, left), method) : 0;
    if (cell == 0 && Slot.type(memory, right) == Type.TABLE) {
      cell = ElxTable.hook(memory, Slot.bits(memory, right), method);
    }
    boolean invert = false;
    if (cell == 0 && method == Metamethod.NE.ordinal()) {
      cell = Slot.type(memory, left) == Type.TABLE ? ElxTable.hook(memory, Slot.bits(memory, left), Metamethod.EQ.ordinal()) : 0;
      if (cell == 0 && Slot.type(memory, right) == Type.TABLE) {
        cell = ElxTable.hook(memory, Slot.bits(memory, right), Metamethod.EQ.ordinal());
      }
      invert = cell != 0;
    }
    if (cell == 0) {
      return false;
    }
    Op op = Instr.op(control);
    int count = op == Op.INEG || op == Op.FNEG || op == Op.BNOT || op == Op.CX_BOOL_NOT || op == Op.CX_POS || op == Op.CX_INCR || op == Op.CX_DECR ? 1 : 2;
    long args = hookArguments(count);
    Slot.copy(memory, args, left, 1);
    if (count == 2) {
      Slot.copy(memory, args + Slot.SIZE, right, 1);
    }
    invokeHook(cell, result, count, 1, Metamethod.at(method).booleanResult() ? Type.BOOL.ordinal() | (invert ? INVERT_RESULT : 0) : -1);
    return true;
  }

  private void readIndex(long result, long target, long key) {
    if (Slot.type(memory, target) == Type.EXTERNAL) {
      if (Slot.type(memory, key) != Type.STRING) {
        throw new IllegalArgumentException("Host member name must be STRING");
      }
      safePoint();
      external.nget(Slot.bits(memory, target), Slot.bits(memory, key), result);
      return;
    }
    long hook = Slot.type(memory, target) == Type.TABLE ? ElxTable.hook(memory, Slot.bits(memory, target), Metamethod.GET.ordinal()) : 0;
    if (hook == 0) {
      ValueOperations.getIndex(memory, target, key, result);
      return;
    }
    long args = hookArguments(2);
    Slot.copy(memory, args, target, 1);
    Slot.copy(memory, args + Slot.SIZE, key, 1);
    invokeHook(hook, result, 2, 1, -1);
  }

  private void writeIndex(long target, long key, long value) {
    if (Slot.type(memory, target) == Type.EXTERNAL) {
      if (Slot.type(memory, key) != Type.STRING) {
        throw new IllegalArgumentException("Host member name must be STRING");
      }
      safePoint();
      external.nset(Slot.bits(memory, target), Slot.bits(memory, key), value);
      return;
    }
    long hook = Slot.type(memory, target) == Type.TABLE ? ElxTable.hook(memory, Slot.bits(memory, target), Metamethod.SET.ordinal()) : 0;
    if (hook == 0) {
      ValueOperations.setIndex(heap, target, key, value);
      return;
    }
    long args = hookArguments(3);
    Slot.copy(memory, args, target, 1);
    Slot.copy(memory, args + Slot.SIZE, key, 1);
    Slot.copy(memory, args + 2L * Slot.SIZE, value, 1);
    invokeHook(hook, 0, 3, 0, -1);
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
      int returned = external.nmeta(Slot.bits(memory, function), Metamethod.CALL, args, count,
          destination, capacity);
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
