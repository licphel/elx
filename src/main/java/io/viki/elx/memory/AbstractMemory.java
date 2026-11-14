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

/**
 * Shared lifecycle, growth, clearing, and overlap-safe copy logic for memory
 * backends. Allocation policy remains in {@link Arena}; implementations only
 * provide primitive loads/stores and storage resize/disposal. Instances are
 * thread-confined and become unusable after {@link #close()}.
 */
abstract class AbstractMemory implements Memory {
  protected int capacity;
  private boolean open = true;

  /**
   * Creates an open backend with the requested initial capacity.
   *
   * @param capacity initial byte capacity
   * @throws IllegalArgumentException if {@code capacity} is less than one
   */
  AbstractMemory(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Memory capacity must be positive");
    }
    this.capacity = capacity;
  }

  /**
   * Replaces the backing storage with a larger region while preserving bytes.
   *
   * @param capacity new byte capacity
   */
  protected abstract void resize(int capacity);

  /** Releases backend-specific storage during {@link #close()}. */
  protected abstract void dispose();

  @Override
  /** @return current byte capacity */
  public final long capacity() {
    return capacity;
  }

  @Override
  /**
   * Grows the backend when {@code bytes} exceeds the current capacity.
   *
   * @param bytes required capacity
   * @throws IllegalStateException if this backend is closed
   */
  public final void ensureCapacity(int bytes) {
    if (!open) {
      throw new IllegalStateException("Memory is closed");
    }
    if (bytes <= capacity) {
      return;
    }
    int next = (int) Math.min(Integer.MAX_VALUE, Math.max(bytes, (long) capacity * 2));
    resize(next);
    capacity = next;
  }

  @Override
  /** @return whether the backend has not been closed */
  public final boolean isOpen() {
    return open;
  }

  @Override
  /**
   * Clears a range using wide stores followed by a byte tail.
   *
   * @param address first byte to clear
   * @param bytes number of bytes to clear
   */
  public void clear(long address, int bytes) {
    int i = 0;
    for (; i <= bytes - Long.BYTES; i += Long.BYTES) {
      store64(address + i, 0);
    }
    for (; i < bytes; i++) {
      store8(address + i, (byte) 0);
    }
  }

  @Override
  /**
   * Copies a range with overlap-safe direction selection.
   *
   * @param destination first destination byte
   * @param source first source byte
   * @param bytes number of bytes to copy
   */
  public void copyBytes(long destination, long source, int bytes) {
    if (destination == source) {
      return;
    }
    if (destination > source && destination < source + bytes) {
      int i = bytes;
      while (i >= Long.BYTES) {
        i -= Long.BYTES;
        store64(destination + i, load64(source + i));
      }
      while (i > 0) {
        --i;
        store8(destination + i, load8(source + i));
      }
    } else {
      int i = 0;
      for (; i <= bytes - Long.BYTES; i += Long.BYTES) {
        store64(destination + i, load64(source + i));
      }
      for (; i < bytes; i++) {
        store8(destination + i, load8(source + i));
      }
    }
  }

  @Override
  /** Closes this backend and releases its storage exactly once. */
  public final void close() {
    if (!open) {
      return;
    }
    dispose();
    open = false;
  }
}
