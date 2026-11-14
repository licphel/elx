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
 * Value-window stack in shared {@link Memory}. A frame is a contiguous range of
 * slots used for registers, arguments, or results; the stack stores addresses
 * rather than Java value objects. Implementations are thread-confined and must
 * be empty before being closed.
 */
public interface Regs extends AutoCloseable {
  /** @return arena used to allocate owned register windows */
  Arena arena();

  /** @return memory containing all active and borrowed windows */
  Memory memory();

  /**
   * Allocates, clears, and enters an owned value window.
   *
   * @param valueCount number of slots in the new frame
   * @return base address of the frame
   * @throws IllegalArgumentException if {@code valueCount} is negative
   * @throws IllegalStateException if this stack is closed
   */
  long enter(int valueCount);

  /**
   * Enters an existing window without copying or taking ownership of its
   * storage. The caller must keep the window valid until {@link #leave()}.
   *
   * @param window base address of an existing slot window
   * @param valueCount number of readable slots in the window
   * @throws IllegalArgumentException if {@code valueCount} is negative
   * @throws IllegalStateException if this stack is closed
   */
  void enter(long window, int valueCount);

  /**
   * Leaves the innermost active window.
   *
   * @throws IllegalStateException if no frame is active or this stack is closed
   */
  void leave();

  /**
   * Returns the base address of the innermost active window.
   *
   * @return frame base address
   * @throws IllegalStateException if no frame is active or this stack is closed
   */
  long base();

  /**
   * Returns the number of values in the innermost active window.
   *
   * @return frame size, or zero when no frame is active
   * @throws IllegalStateException if this stack is closed
   */
  int size();

  /** @return number of nested active windows */
  int depth();

  /**
   * Computes the address of a slot in the current window.
   *
   * @param register zero-based slot index
   * @return slot address
   * @throws IllegalStateException if no frame is active
   */
  default long address(int register) {
    return Slot.address(base(), register);
  }

  /**
   * Visits reference-valued slots in every active window so a collector can
   * treat them as roots.
   *
   * @param visitor root visitor
   * @throws IllegalStateException if this stack is closed
   */
  void visitRoots(HeapVisitor visitor);

  /**
   * Closes the register stack after all windows have been left.
   *
   * @throws IllegalStateException if a frame is still active
   */
  @Override
  void close();
}
