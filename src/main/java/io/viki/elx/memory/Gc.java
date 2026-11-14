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
import java.util.function.Consumer;

/**
 * Nonrecursive, non-moving coordinated mark/sweep across the internal and
 * external heaps. Roots come from active register windows, retained heap holds,
 * and caller-supplied roots; traversal is iterative so deeply nested ELX values
 * do not consume the Java call stack. Instances are thread-confined and a
 * collection cannot be re-entered.
 */
public final class Gc implements HeapVisitor {
  private final ElxHeap internal;
  private final ExtHeap external;
  private final Regs regs;
  private long[] pending = new long[64];
  private int count;
  private boolean running;

  /**
   * Creates a collector for heaps and register windows sharing one address space.
   *
   * @param internal internal ELX heap
   * @param external external-handle heap
   * @param regs register windows that form VM roots
   * @throws IllegalArgumentException if the heaps and registers do not share
   *                                  the same memory and arena
   */
  public Gc(ElxHeap internal, ExtHeap external, Regs regs) {
    if (regs.arena() != internal.arena() || external.memory() != internal.memory()) {
      throw new IllegalArgumentException("GC heaps and registers must share Memory");
    }
    this.internal = internal;
    this.external = external;
    this.regs = regs;
  }

  /**
   * Runs a collection with no extra roots beyond VM-owned roots.
   *
   * @param pressure whether weak pins may be reclaimed
   * @throws IllegalStateException if a collection is already running
   */
  public void collect(boolean pressure) {
    collect(pressure, visitor -> {
    });
  }

  /**
     * Runs a coordinated collection and lets the caller enumerate additional
     * roots while the mark phase is active.
     *
   * @param pressure whether weak pins may be reclaimed
   * @param extraRoots callback that visits temporary host roots
   * @throws IllegalStateException if a collection is already running
   * @throws NullPointerException if {@code extraRoots} is null
   */
  public void collect(boolean pressure, Consumer<HeapVisitor> extraRoots) {
    if (running) {
      throw new IllegalStateException("GC is not reentrant");
    }
    running = true;
    count = 0;
    try {
      internal.startGc(pressure);
      external.startGc(pressure);
      regs.visitRoots(this);
      internal.visitRetained(this);
      external.visitRetained(this);
      extraRoots.accept(this);
      while (count > 0) {
        long ptr = pending[--count];
        if (ptr < 0) {
          external.trace(ptr, this);
        } else {
          internal.trace(ptr, this);
        }
      }
      external.sweep();
      internal.sweep();
    } finally {
      running = false;
      count = 0;
    }
  }

  /**
   * Enqueues a newly discovered reference for iterative tracing.
   *
   * <p>Each pointer is marked before enqueueing, so every reachable allocation
   * is scanned once. The pending array grows geometrically and traversal uses
   * {@code O(R)} storage for {@code R} pending references.</p>
   *
   * @param type slot type describing the referenced domain
   * @param pointer reference value from that slot
   * @throws IllegalStateException if called outside an active collection
   */
  @Override
  public void visit(Type type, long pointer) {
    if (!running) {
      throw new IllegalStateException("Roots can only be visited during collection");
    }
    if (pointer == 0 || !type.reference()) {
      return;
    }
    // Non-heap internal pointers are not ELX reference handles and cannot keep allocations alive.
    if (type == Type.PTR && pointer > 0 && !internal.isAlive(pointer)) {
      return;
    }
    boolean fresh = pointer < 0 ? external.mark(pointer) : internal.mark(pointer);
    if (!fresh) {
      return;
    }
    if (count == pending.length) {
      pending = Arrays.copyOf(pending, count * 2);
    }
    pending[count++] = pointer;
  }
}
