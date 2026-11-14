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
 * Raw virtual byte storage only: no value types, flags, alignment policy or GC.
 * Addresses are direct offsets. Access may be unaligned; layout belongs to Slot
 * and other users. Bounds/lifetime failures use the backend's natural checks.
 * Thread-confined; close after all borrowing arenas, heaps and registers.
 */
public interface Memory extends AutoCloseable {
  /**
   * Returns the current size of the address space. Addresses from zero through
   * {@code capacity() - 1} are potentially addressable; the implementation may
   * still reject an access that falls outside a primitive operation's width.
   *
   * @return reserved byte capacity
   */
  long capacity();

  /**
   * Grows the backing store when necessary so an address below {@code bytes}
   * can be used. Existing bytes are preserved; shrinking is not supported.
   *
   * @param bytes required byte capacity
   * @throws IllegalArgumentException if {@code bytes} is negative
   * @throws IllegalStateException if this memory is closed
   */
  void ensureCapacity(int bytes);

  /**
   * Tests whether this storage can still be accessed.
   *
   * @return {@code true} until {@link #close()} completes
   */
  boolean isOpen();

  /**
   * Fills a contiguous range with zero bytes.
   *
   * @param address first byte to clear
   * @param bytes number of bytes to clear
   * @throws IndexOutOfBoundsException if the range is outside this memory
   */
  void clear(long address, int bytes);

  /**
   * Copies a range with {@code memmove} semantics, so overlapping source and
   * destination ranges are handled correctly.
   *
   * @param destination first destination byte
   * @param source first source byte
   * @param bytes number of bytes to copy
   * @throws IndexOutOfBoundsException if either range is outside this memory
   */
  void copyBytes(long destination, long source, int bytes);

  /**
   * Reads one byte.
   *
   * @param address byte address
   * @return stored byte
   */
  byte load8(long address);

  /**
   * Reads two bytes in the backend's native byte order.
   *
   * @param address first byte address
   * @return stored 16-bit value
   */
  short load16(long address);

  /**
   * Reads four bytes in the backend's native byte order.
   *
   * @param address first byte address
   * @return stored 32-bit value
   */
  int load32(long address);

  /**
   * Reads eight bytes in the backend's native byte order.
   *
   * @param address first byte address
   * @return stored 64-bit value
   */
  long load64(long address);

  /**
   * Writes one byte.
   *
   * @param address byte address
   * @param value byte to store
   */
  void store8(long address, byte value);

  /**
   * Writes two bytes in the backend's native byte order.
   *
   * @param address first byte address
   * @param value 16-bit value to store
   */
  void store16(long address, short value);

  /**
   * Writes four bytes in the backend's native byte order.
   *
   * @param address first byte address
   * @param value 32-bit value to store
   */
  void store32(long address, int value);

  /**
   * Writes eight bytes in the backend's native byte order.
   *
   * @param address first byte address
   * @param value 64-bit value to store
   */
  void store64(long address, long value);

  /**
   * Releases the backing storage. Calling this method more than once is safe;
   * all other operations become invalid after the first call.
   */
  @Override
  void close();
}
