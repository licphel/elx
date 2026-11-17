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

package io.viki.elx;

import io.viki.elx.asm.Prototype;
import io.viki.elx.memory.*;
import io.viki.elx.vm.VM;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Owns one complete ELX runtime instance and exposes its host-facing execution boundary.
 *
 * <p>A context wires together the VM, managed heap, register storage, and optional host-object
 * heap. Heap-backed views depend on their context and corresponding {@link Pin} remaining alive.
 * Callers use {@link #execute(Prototype)} to retain returned values and {@link #pin(String)}
 * to retain globals across VM calls or garbage collection. Close results and independently
 * acquired pins after use, and close the thread-confined context after the last VM operation.
 *
 * @see VM
 * @see Pin
 * @see HostAccess
 * @see ExternalHeapFactory
 */
public final class Context implements AutoCloseable {
  private final Memory memory;
  private final Arena arena;
  private final ElxHeap heap;
  private final ExtHeap external;
  private final JavaRegs registers;
  private final VM vm;
  private boolean closed;

  private Context() {
    this(DefaultExtHeap::new);
  }

  private Context(ExternalHeapFactory factory) {
    memory = new JavaMemory();
    arena = new Arena(memory);
    heap = new ElxHeap(arena);
    external = Objects.requireNonNull(factory.create(heap));
    registers = new JavaRegs(arena);
    vm = new VM(heap, external, registers);
  }

  /**
   * Creates a context with the built-in runtime and automatic {@link HostAccess} dispatch.
   *
   * @return a new thread-confined ELX context
   */
  public static Context open() {
    return new Context();
  }

  /**
   * Creates a context with a caller-supplied low-level external heap.
   *
   * @param factory factory that creates the context's low-level external-object heap
   * @return a new thread-confined ELX context
   * @throws NullPointerException if {@code factory} is {@code null}
   */
  public static Context open(ExternalHeapFactory factory) {
    return new Context(Objects.requireNonNull(factory));
  }

  /**
   * Returns the VM that executes code in this context.
   *
   * @return this context's VM
   * @throws IllegalStateException if this context has been closed
   */
  public VM vm() {
    ensureOpen();
    return vm;
  }

  /**
   * Pins a named global so Java code can inspect it after the current VM operation.
   *
   * <p>Managed references remain rooted until the returned pin is closed. Primitive values are
   * copied into an independent pin, while external values are retained by the configured
   * {@link ExternalHeapFactory}. A missing global produces {@link VoidValue#INSTANCE}; an explicit ELX null
   * produces a primitive pin whose {@link Pin#get()} returns Java null.
   *
   * @param name global name to resolve
   * @param <T>  Java type expected by the caller
   * @return a non-null pin representing the global value, which may itself be null
   * @throws IllegalStateException if this context has been closed
   */
  public <T extends @Nullable Object> Pin<T> pin(String name) {
    ensureOpen();
    long slot = arena.allocate(Slot.SIZE);
    try {
      vm.getGlobal(name, slot);
      return pinSlot(slot);
    } finally {
      arena.free(slot);
    }
  }

  /**
   * Executes a compiled script and owns a snapshot of its returned values.
   *
   * <p>The snapshot survives subsequent executions and garbage collection until it is closed.
   * Each {@link Results#get(int)} call acquires a separate pin that the caller also closes.
   * Execution failure propagates to the caller before a results object is created.
   *
   * @param image compiled script or zero-argument function
   * @return caller-owned results, possibly containing zero values
   * @throws IllegalStateException if the context is closed or execution fails
   */
  public Results execute(Prototype image) {
    ensureOpen();
    return new Results(this, vm.execute(image));
  }

  /**
   * Executes a compiled function with arguments in a contiguous VM slot window.
   *
   * <p>Arguments are borrowed for this invocation. The caller retains ownership of their
   * storage. Returned results have the same independent lifetime as {@link #execute(Prototype)}.
   *
   * @param image compiled function
   * @param args  address of the first input slot, or zero when {@code count} is zero
   * @param count number of input slots
   * @return caller-owned snapshot of the returned values
   * @throws IllegalArgumentException if the function rejects the supplied arguments
   * @throws IllegalStateException    if the context is closed or execution fails
   */
  public Results execute(Prototype image, long args, int count) {
    ensureOpen();
    return new Results(this, vm.execute(image, args, count));
  }

  /**
   * Executes a loaded function in this context and retains its returned values.
   *
   * @param function live function handle belonging to this context's VM
   * @param args     address of the first input slot, or zero when {@code count} is zero
   * @param count    number of input slots
   * @return caller-owned snapshot of the returned values
   * @throws IllegalArgumentException if the handle or supplied arguments are invalid
   * @throws IllegalStateException    if the context is closed or execution fails
   */
  public Results execute(long function, long args, int count) {
    ensureOpen();
    return new Results(this, vm.execute(function, args, count));
  }

  @SuppressWarnings({"all", "unchecked"})
  <T extends @Nullable Object> Pin<T> pinSlot(long slot) {
    ensureOpen();
    Type type = Slot.type(memory, slot);
    long bits = Slot.bits(memory, slot);
    if (type == Type.EXTERNAL) {
      return (Pin<T>) external.pin(bits, PinMode.STRONG);
    }
    if (Type.isManagedReference(type)) {
      Pin.Heap<Long> rooted = heap.pin(bits, PinMode.STRONG);

      return (Pin<T>) new Pin<Object>() {
        @Override
        public Object get() {
          long address = rooted.address();
          return type == Type.STRING ? heap.exportString(address) : Long.valueOf(address);
        }

        @Override
        public boolean isPrimitive() {
          return false;
        }

        @Override
        public boolean isAlive() {
          return rooted.isAlive();
        }

        @Override
        public void close() {
          rooted.close();
        }
      };
    }
    return new Pin.Primitive<>((T) external.exportValue(slot));
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("ELX context is closed");
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    vm.close();
    // Host GC callbacks still borrow register windows while releasing external values.
    external.close();
    registers.close();
    heap.close();
    memory.close();
    closed = true;
  }

  /**
   * Creates the low-level host-object heap used for values of {@link Type#EXTERNAL}.
   * Ordinary Java objects should implement {@link HostAccess} and use {@link #open()}.
   */
  @FunctionalInterface
  public interface ExternalHeapFactory {
    /**
     * Creates an external heap associated with the managed ELX heap.
     *
     * @param internal managed heap used by the runtime
     * @return external heap for host-object values
     */
    ExtHeap create(ElxHeap internal);
  }

  private static final class DefaultExtHeap extends ExtHeap {
    private DefaultExtHeap(ElxHeap internal) {
      super(internal);
    }
  }
}
