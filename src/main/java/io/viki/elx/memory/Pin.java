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

package io.viki.elx.memory;

import org.jspecify.annotations.Nullable;

/**
 * Explicit host-boundary lifetime token. All implementations are
 * thread-confined; callers must close heap pins when a borrowed pointer is no
 * longer needed. A pin object represents ownership even when its exported value
 * is Java {@code null}, so a non-null pin and a non-null exported value are separate
 * guarantees. Heap-backed pins depend on their owning runtime remaining open.
 *
 * @param <T> the pinned value type, nullable
 */
public interface Pin<T extends @Nullable Object> extends AutoCloseable {
  /**
   * Exports the value represented by this pin.
   *
   * <p>The result may be null independently of {@code T}. ELX {@code null} is
   * represented by a primitive pin containing Java {@code null}. An external-field
   * adapter may also export a null field value. Heap-backed access checks liveness
   * and throws on expiration rather than using null to indicate a closed pin.
   *
   * @return current value, possibly {@code null}
   * @throws IllegalStateException if a heap-backed pin is no longer alive
   */
  @Nullable T get();

  /**
   * Releases this pin; equivalent to {@link #close()}.
   * Repeated calls are harmless.
   */
  default void unpin() {
    close();
  }

  /**
   * Tests whether this pin contains an independently owned Java value.
   *
   * @return {@code true} for {@link Primitive} pins
   */
  boolean isPrimitive();

  /**
   * Tests whether the pinned allocation is still live.
   *
   * @return {@code true} until close, collection, or generation reuse
   */
  boolean isAlive();

  /**
   * Releases this pin and makes future access invalid where applicable.
   * Repeated calls are harmless.
   */
  @Override
  void close();

  /**
   * Immutable exported primitive; closing has no effect on its independent lifetime.
   *
   * @param <T> the pinned value type, nullable
   */
  final class Primitive<T extends @Nullable Object> implements Pin<T> {
    private final @Nullable T value;

    /**
     * Creates a pin around an independently owned Java value.
     *
     * @param value value returned by {@link #get()}
     */
    public Primitive(@Nullable T value) {
      this.value = value;
    }

    /**
     * Returns the stored primitive value.
     *
     * @return immutable value, possibly {@code null}
     */
    @Override
    public @Nullable T get() {
      return value;
    }

    /**
     * @return always {@code true} for primitive pins
     */
    @Override
    public boolean isPrimitive() {
      return true;
    }

    /**
     * @return always {@code true}; the value is independent of a heap
     */
    @Override
    public boolean isAlive() {
      return true;
    }

    /** Primitive pins require no cleanup. */
    @Override
    public void close() {
    }
  }

  /**
   * A generation-checked hold; reused memory never revives an expired pin.
   *
   * @param <T> the pinned value type, nullable
   */
  final class Heap<T extends @Nullable Object> implements Pin<T> {
    private final ManagedHeap<T> owner;
    private final int index;
    private final long identity, address;
    private final PinMode mode;
    private boolean closed;

    Heap(ManagedHeap<T> owner, int index, long identity, long address, PinMode mode) {
      this.owner = owner;
      this.index = index;
      this.identity = identity;
      this.address = address;
      this.mode = mode;
    }

    /**
     * Returns the live VM-local allocation address.
     *
     * @return pinned allocation address, which may be an interior address
     * @throws IllegalStateException if the pin is closed or its generation was collected
     */
    public long address() {
      if (!isAlive()) {
        throw new IllegalStateException("Heap pin is closed or its allocation has been collected");
      }
      return address;
    }

    /**
     * Exports the currently pinned allocation through its owning heap.
     *
     * @return exported value, possibly {@code null}
     * @throws IllegalStateException if the pin is closed or its generation was collected
     */
    @Override
    public @Nullable T get() {
      return owner.exportJava(address());
    }

    /**
     * @return always {@code false} for heap pins
     */
    @Override
    public boolean isPrimitive() {
      return false;
    }

    /**
     * @return whether the generation token still names a live allocation
     */
    @Override
    public boolean isAlive() {
      return !closed && owner.alive(index, identity);
    }

    /**
     * Releases the hold once; repeated close calls are harmless.
     */
    @Override
    public void close() {
      if (closed) {
        return;
      }
      owner.unpin(index, identity, mode);
      closed = true;
    }
  }
}
