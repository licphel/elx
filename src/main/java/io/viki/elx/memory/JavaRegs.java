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

import io.viki.elx.Type;

import java.util.Arrays;

/**
 * Reusable per-depth register windows in the VM's {@link Memory}. Entering a
 * borrowed window performs no copies; owned windows are resized and recycled by
 * the shared {@link Arena}. Active windows are GC roots. Instances are
 * thread-confined and must be closed after the stack is empty.
 */
public final class JavaRegs implements Regs {
  private final Memory memory;
  private final Arena arena;
  private long[] owned = new long[16], active = new long[16];
  private int[] capacities = new int[16], counts = new int[16];
  private int depth;
  private boolean closed;

  /**
   * Creates a register stack backed by the supplied arena.
   *
   * @param arena arena used for owned windows; it remains borrowed
   */
  public JavaRegs(Arena arena) {
    this.arena = arena;
    this.memory = arena.memory();
  }

  /** Returns the backing allocation arena. */
  @Override
  public Arena arena() {
    return arena;
  }

  /** Returns memory containing active windows. */
  @Override
  public Memory memory() {
    return memory;
  }

  /**
   * Allocates or reuses and enters an owned window of {@code valueCount} slots.
   * Existing values are cleared to {@link Type#NULL}; the returned window is
   * an active GC root until {@link #leave()} is called.
   *
   * @param valueCount number of slots to expose
   * @return base address of the active window
   * @throws IllegalArgumentException if {@code valueCount} is negative
   * @throws ArithmeticException if the byte size overflows
   * @throws IllegalStateException if this stack is closed
   */
  @Override
  public long enter(int valueCount) {
    if (valueCount < 0) {
      throw new IllegalArgumentException("Negative register count");
    }
    reserveFrame();
    if (owned[depth] == 0 || capacities[depth] < valueCount) {
      long next = arena.allocate(Math.multiplyExact(valueCount, Slot.SIZE));
      if (owned[depth] != 0) {
        arena.free(owned[depth]);
      }
      owned[depth] = next;
      capacities[depth] = valueCount;
    }
    long base = owned[depth];
    for (int i = 0; i < valueCount; i++) {
      Slot.write(memory, Slot.address(base, i), Type.NULL, 0);
    }
    active[depth] = base;
    counts[depth] = valueCount;
    depth++;
    return base;
  }

  /**
   * Borrows an existing window without copying its values.
   *
   * <p>The caller owns the supplied memory and must keep it valid until the
   * matching {@link #leave()}; this method does not retain or free it.</p>
   *
   * @param window existing slot-window base address
   * @param valueCount number of slots in the borrowed window
   * @throws IllegalArgumentException if {@code valueCount} is negative
   * @throws IllegalStateException if this stack is closed
   */
  @Override
  public void enter(long window, int valueCount) {
    if (valueCount < 0) {
      throw new IllegalArgumentException("Negative register count");
    }
    reserveFrame();
    active[depth] = window;
    counts[depth] = valueCount;
    depth++;
  }

  /**
   * Leaves the innermost register frame.
   *
   * @throws IllegalStateException if the stack is closed or has no active frame
   */
  @Override
  public void leave() {
    checkOpen();
    if (depth == 0) {
      throw new IllegalStateException("No register frame to leave");
    }
    --depth;
    active[depth] = 0;
    counts[depth] = 0;
  }

  /**
   * Returns the base address of the innermost frame.
   *
   * @return active frame base address
   * @throws IllegalStateException if the stack is closed or has no active frame
   */
  @Override
  public long base() {
    checkOpen();
    if (depth == 0) {
      throw new IllegalStateException("No register frame");
    }
    return active[depth - 1];
  }

  /**
   * Returns the value count of the innermost frame, or zero when empty.
   *
   * @return innermost frame slot count
   * @throws IllegalStateException if the stack is closed
   */
  @Override
  public int size() {
    checkOpen();
    return depth == 0 ? 0 : counts[depth - 1];
  }

  /**
   * Returns the number of active frames.
   *
   * @return current frame depth; zero when no frame is active
   */
  @Override
  public int depth() {
    return depth;
  }

  /**
   * Visits reference slots in every active frame.
   *
   * @param visitor callback receiving each reference type and pointer
   * @throws IllegalStateException if the stack is closed
   */
  @Override
  public void visitRoots(HeapVisitor visitor) {
    checkOpen();
    for (int frame = 0; frame < depth; frame++) {
      for (int i = 0; i < counts[frame]; i++) {
        long slot = Slot.address(active[frame], i);
        if (Slot.isReference(memory, slot)) {
          visitor.visit(Slot.type(memory, slot), Slot.bits(memory, slot));
        }
      }
    }
  }

  /**
   * Releases owned windows after all frames have been left.
   *
   * @throws IllegalStateException if active frames remain
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    if (depth != 0) {
      throw new IllegalStateException("Cannot close active register frames");
    }
    for (long address : owned) {
      if (address != 0) {
        arena.free(address);
      }
    }
    closed = true;
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("Registers are closed");
    }
  }

  private void reserveFrame() {
    checkOpen();
    if (depth < active.length) {
      return;
    }
    int size = active.length * 2;
    owned = Arrays.copyOf(owned, size);
    active = Arrays.copyOf(active, size);
    capacities = Arrays.copyOf(capacities, size);
    counts = Arrays.copyOf(counts, size);
  }
}
