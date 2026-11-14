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
 * Shared reachability/lifetime contract for heterogeneous heaps; thread-confined.
 *
 * @param <T> heap export type
 */
public interface Heap<T extends @Nullable Object> extends AutoCloseable {
  /**
   * Begins a mark phase; {@code pressure} allows weak roots to be reclaimed.
   *
   * @param pressure whether weak roots may be reclaimed
   */
  void startGc(boolean pressure);

  /**
   * Begins a normal mark phase that preserves weak roots.
   */
  default void startGc() {
    startGc(false);
  }

  /**
   * Marks a live allocation and returns whether it was newly marked.
   *
   * @param ptr allocation pointer
   * @return {@code true} when the allocation was newly marked
   */
  boolean mark(long ptr);

  /**
   * Visits references contained by one marked allocation.
   *
   * @param ptr allocation pointer
   * @param visitor reference visitor
   */
  void trace(long ptr, HeapVisitor visitor);

  /**
   * Reclaims every unmarked, unretained allocation after a mark phase.
   * Implementations must not be used for allocation or tracing while sweeping.
   */
  void sweep();

  /**
   * Adds a strong retention count to an allocation.
   *
   * @param ptr allocation pointer
   */
  void retain(long ptr);

  /**
   * Removes a strong retention count from an allocation.
   *
   * @param ptr allocation pointer
   */
  void release(long ptr);

  /**
   * Visits allocations retained independently of VM register roots.
   *
   * @param visitor reference visitor
   */
  void visitRetained(HeapVisitor visitor);

  /**
   * Tests whether a pointer currently denotes a live allocation.
   *
   * @param ptr allocation pointer
   * @return whether the allocation is live
   */
  boolean isAlive(long ptr);

  /**
   * Exports a value at the explicit Java boundary.
   *
   * @param ptr allocation pointer
   * @return exported host value
   */
  T exportJava(long ptr);

  /**
   * Creates an explicit lifetime token for one allocation.
   *
   * @param ptr allocation pointer
   * @param mode retention policy
   * @return generation-checked pin
   */
  Pin.Heap<T> pin(long ptr, PinMode mode);

  /** Closes the heap and releases every allocation it owns. */
  @Override
  void close();
}
