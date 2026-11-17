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

import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.Pin;
import io.viki.elx.memory.PinMode;
import io.viki.elx.memory.Slot;
import io.viki.elx.vm.VM;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Owns a snapshot of one execution's returned values. This object and its pins are
 * confined to the context's thread. Closing the results releases the snapshot,
 * while pins obtained with {@link #get(int)} keep their own independent lifetimes.
 * Heap-backed values remain valid while their context is open. The snapshot copies
 * return slots and shares the referenced containers, so later container mutations
 * remain visible. Capturing a snapshot takes O(n) time and storage for n return values.
 *
 * <p>A returned ELX {@code null} occupies a slot just like any other value. Its type
 * is {@link Type#NULL}, and {@code get(index)} supplies a primitive pin whose
 * {@link Pin#get()} returns Java {@code null}. A bare return instead has size zero.
 *
 * @see Context#execute(io.viki.elx.asm.Prototype)
 */
public final class Results implements AutoCloseable {
  private final Context context;
  private final int size;
  private final Pin.@Nullable Heap<Long> values;
  private boolean closed;

  Results(Context context, int size) {
    this.context = context;
    this.size = size;
    if (size == 0) {
      values = null;
    } else {
      VM vm = context.vm();
      long snapshot = vm.heap().allocate(ElxHeap.Layout.VALUES, size);
      Pin.Heap<Long> root = vm.heap().pin(snapshot, PinMode.STRONG);
      try {
        Slot.copy(vm.memory(), snapshot, vm.results(), size);
      } catch (RuntimeException | Error failure) {
        root.close();
        throw failure;
      }
      values = root;
    }
  }

  /**
   * Returns the number of values captured from the invocation, including null values.
   * This metadata remains readable after close.
   *
   * @return result count from zero through sixteen
   */
  public int size() {
    return size;
  }

  /**
   * Reads the captured runtime type in constant time.
   *
   * @param index zero-based return index
   * @return the value type, including {@link Type#NULL} for an explicit null
   * @throws IndexOutOfBoundsException if the index is outside the returned values
   * @throws IllegalStateException     if the results or context have been closed
   */
  public Type type(int index) {
    return Slot.type(context.vm().memory(), slot(index));
  }

  /**
   * Creates an independently owned strong pin for one returned value in constant time.
   * Integers export as {@link Long}, floating-point values as {@link Float} or
   * {@link Double}, and strings as {@link String}. Lists, tables, functions, and
   * pointers export as VM-local {@link Long} handles. Booleans export as {@link Boolean},
   * external objects use the configured adapter, ELX null exports as Java null, and void exports as VoidValue.INSTANCE.
   * A pin keeps the allocation
   * alive but retains the lifetime rules of the underlying value, including stack pointers.
   *
   * <p>Each call creates a fresh pin. Closing this results object leaves acquired
   * pins alive. The generic type expresses the caller's expected Java representation
   * and performs no numeric conversion or runtime type validation. Use {@link #type(int)}
   * to inspect values with an unknown type. {@link Pin#get()} remains nullable even
   * when the chosen generic type is non-nullable.
   *
   * @param index zero-based return index
   * @param <T>   exported Java type expected by the caller
   * @return a new pin owned by the caller, including for a returned null value
   * @throws IndexOutOfBoundsException if the index is outside the returned values
   * @throws IllegalStateException     if the results or context have been closed
   */
  public <T extends @Nullable Object> Pin<T> get(int index) {
    return context.pinSlot(slot(index));
  }

  private long slot(int index) {
    if (closed) {
      throw new IllegalStateException("ELX results are closed");
    }
    Objects.checkIndex(index, size);
    return Slot.address(Objects.requireNonNull(values).address(), index);
  }

  /**
   * Releases the snapshot's roots. Already acquired pins retain their own roots.
   * Repeated calls are harmless, including after the owning context has closed.
   * Subsequent indexed access throws {@link IllegalStateException}.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    if (values != null) {
      values.close();
    }
    closed = true;
  }
}
