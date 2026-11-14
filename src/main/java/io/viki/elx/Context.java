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

import io.viki.elx.memory.*;
import io.viki.elx.memory.*;
import io.viki.elx.vm.VM;

import java.util.Objects;

/**
 * Owns one complete ELX runtime instance and exposes its host-facing execution boundary.
 *
 * <p>A context wires together the VM, managed heap, register storage, and optional host-object
 * heap. Values remain valid only while their context and the corresponding {@link Pin} are alive;
 * callers use {@link #pin(String)} when Java code must retain a value across VM calls or garbage
 * collection. The context is thread-confined and must be closed after the last VM operation.
 *
 * @see VM
 * @see Pin
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
    this(EmptyExtHeap::new);
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
   * Creates a context with the built-in runtime and no host-object adapters.
   *
   * @return a new thread-confined ELX context
   */
  public static Context open() {
    return new Context();
  }

  /**
   * Creates a context with a caller-supplied host-object adapter.
   *
   * @param factory factory that creates the context's external-object heap
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
   * {@link ExternalHeapFactory}.
   *
   * @param name global name to resolve
   * @param <T> Java type expected by the caller
   * @return a pin representing the global value
   * @throws IllegalStateException if this context has been closed
   * @throws IllegalArgumentException if the global name is unknown
   */
  @SuppressWarnings("unchecked")
  public <T> Pin<T> pin(String name) {
    ensureOpen();
    long slot = arena.allocate(Slot.SIZE);
    try {
      vm.getGlobal(name, slot);
      Type type = Slot.type(memory, slot);
      long bits = Slot.bits(memory, slot);
      if (type == Type.EXTERNAL) {
        return (Pin<T>) external.pin(bits, PinMode.STRONG);
      }
      if (Type.isManagedReference(type)) {
        Pin.Heap<Long> rooted = heap.pin(bits, PinMode.STRONG);
        return new Pin<>() {
          @Override
          public T get() {
            Long address = rooted.get();
            return (T) (type == Type.STRING ? heap.exportString(address) : address);
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
    } finally {
      arena.free(slot);
    }
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
    registers.close();
    external.close();
    heap.close();
    memory.close();
    closed = true;
  }

  /**
   * Creates the host-object heap used for values of {@link Type#EXTERNAL}.
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

  private static final class EmptyExtHeap extends ExtHeap {
    private EmptyExtHeap(ElxHeap internal) {
      super(internal);
    }

    @Override
    protected void readField(Object target, long memberNamePtr, long result) {
      throw new UnsupportedOperationException("No host-object adapter is installed");
    }

    @Override
    protected void writeField(Object target, long memberNamePtr, long value) {
      throw new UnsupportedOperationException("No host-object adapter is installed");
    }

    @Override
    protected int invoke(Object target, long memberNamePtr, long args, int argCount,
                         long results, int resultCapacity) {
      throw new UnsupportedOperationException("No host-object adapter is installed");
    }
  }
}
