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
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Primitive lifetime bookkeeping shared by concrete heaps. Allocation handles
 * carry generation identities so a released slot cannot revive an old pin;
 * retention, strong/weak pin counts, mark bits, and free-index recycling are
 * maintained without per-allocation Java objects. Only {@link #exportJava(long)}
 * and pins expose Java values. Instances are thread-confined.
 */
abstract class ManagedHeap<T extends @Nullable Object> implements Heap<T> {
  /** Initial number of allocation metadata entries before geometric growth. */
  protected static final int INITIAL_CAPACITY = 16;
  private final Long2IntOpenHashMap lookup = new Long2IntOpenHashMap();
  private long[] pointers = new long[INITIAL_CAPACITY], identities = new long[INITIAL_CAPACITY];
  private int[] pins = new int[INITIAL_CAPACITY];
  private int[] holds = new int[INITIAL_CAPACITY], soft = new int[INITIAL_CAPACITY], next = new int[INITIAL_CAPACITY];
  private boolean[] marked = new boolean[INITIAL_CAPACITY];
  private int used, free = -1, liveCount;
  private long identity;
  private boolean closed, pressure;

  ManagedHeap() {
    lookup.defaultReturnValue(-1);
  }

  /**
   * Returns the number of allocations currently owned by this heap.
   *
   * @return live allocation count; released slots are excluded
   */
  public final int liveCount() {
    return liveCount;
  }

  /**
   * Resolves a possibly interior pointer to its allocation base.
   *
   * @param ptr pointer into this heap
   * @return base address of the containing allocation
   * @throws IllegalArgumentException if {@code ptr} is outside the backing heap
   */
  protected abstract long base(long ptr);

  /**
   * Grows subclass metadata arrays to hold the requested number of entries.
   *
   * @param capacity new metadata capacity
   */
  protected abstract void grow(int capacity);

  /**
   * Releases subclass-owned storage for one allocation.
   *
   * @param index metadata index being released
   * @param ptr allocation base address
   */
  protected abstract void dispose(int index, long ptr);

  /**
   * Supplies the root type reported when this allocation is retained.
   *
   * @param index metadata index
   * @return type tag describing the retained pointer
   */
  protected Type rootType(int index) {
    return Type.PTR;
  }

  /**
   * Ensures this heap still accepts allocation and lifetime operations.
   *
   * @throws IllegalStateException if {@link #close()} has completed
   */
  protected final void checkOpen() {
    if (closed) {
      throw new IllegalStateException("Heap is closed");
    }
  }

  /**
   * Registers a newly allocated base address and assigns a fresh generation.
   *
   * @param ptr allocation base address
   * @return reusable metadata index for the allocation
   * @throws IllegalStateException if this heap is closed
   */
  protected final int register(long ptr) {
    checkOpen();
    int index;
    if (free >= 0) {
      index = free;
      free = next[index];
    } else {
      index = used++;
      if (index == pointers.length) {
        int size = pointers.length * 2;
        pointers = Arrays.copyOf(pointers, size);
        identities = Arrays.copyOf(identities, size);
        pins = Arrays.copyOf(pins, size);
        holds = Arrays.copyOf(holds, size);
        soft = Arrays.copyOf(soft, size);
        next = Arrays.copyOf(next, size);
        marked = Arrays.copyOf(marked, size);
        grow(size);
      }
    }
    liveCount++;
    pointers[index] = ptr;
    identities[index] = ++identity;
    marked[index] = false;
    holds[index] = 0;
    pins[index] = 0;
    soft[index] = 0;
    lookup.put(ptr, index);
    return index;
  }

  /**
   * Finds the metadata entry for a base or interior pointer.
   *
   * @param ptr pointer into a live allocation
   * @return metadata index
   * @throws IllegalStateException if the pointer does not name a live allocation
   * @throws IllegalStateException if this heap is closed
   */
  protected final int entry(long ptr) {
    checkOpen();
    // Most references already name an allocation base. Only interior pointers need owner lookup.
    int index = lookup.get(ptr);
    if (index < 0) {
      index = lookup.get(base(ptr));
    }
    if (index < 0) {
      throw new IllegalStateException("Dead heap reference: " + ptr);
    }
    return index;
  }

  /**
   * Returns the base pointer stored at a metadata index.
   *
   * @param index live metadata index
   * @return allocation base address
   */
  protected final long pointer(int index) {
    return pointers[index];
  }

  /**
   * Starts a mark phase and clears marks from the previous collection.
   *
   * @param pressure whether weakly retained allocations may be reclaimed in
   *                  this cycle
   * @throws IllegalStateException if this heap is closed
   */
  @Override
  public final void startGc(boolean pressure) {
    checkOpen();
    this.pressure = pressure;
    Arrays.fill(marked, 0, used, false);
  }

  /**
   * Marks the allocation containing a pointer.
   *
   * @param ptr base or interior pointer to mark
   * @return {@code true} when this call changed an unmarked allocation to
   *         marked; {@code false} for null or an already marked pointer
   * @throws IllegalStateException if {@code ptr} is not a live heap pointer
   */
  @Override
  public final boolean mark(long ptr) {
    if (ptr == 0) {
      return false;
    }
    int index = entry(ptr);
    if (marked[index]) {
      return false;
    }
    marked[index] = true;
    return true;
  }

  /**
   * Reclaims unmarked allocations that have no strong retention or pins.
   *
   * <p>Weak pins survive a normal collection and are reclaimed when
   * {@link #startGc(boolean)} was called with pressure.</p>
   *
   * @throws IllegalStateException if this heap is closed
   */
  @Override
  public final void sweep() {
    checkOpen();
    for (int i = 0; i < used; i++) {
      if (pointers[i] != 0 && !marked[i] && holds[i] == 0 && pins[i] == 0 && (pressure || soft[i] == 0)) {
        remove(i);
      }
    }
  }

  /**
   * Adds one strong retention count for an allocation.
   *
   * @param ptr base or interior pointer to retain
   * @throws IllegalStateException if the pointer is dead or the heap is closed
   */
  @Override
  public final void retain(long ptr) {
    holds[entry(ptr)]++;
  }

  /**
   * Removes one strong retention count.
   *
   * @param ptr base or interior pointer previously retained
   * @throws IllegalStateException if the pointer is dead, the heap is closed,
   *                               or the retain count is already zero
   */
  @Override
  public final void release(long ptr) {
    int index = entry(ptr);
    if (holds[index] == 0) {
      throw new IllegalStateException("Unbalanced heap release: " + ptr);
    }
    holds[index]--;
  }

  /**
   * Visits allocations kept alive by retention or pins.
   *
   * @param visitor callback receiving the allocation type and base pointer
   * @throws IllegalStateException if this heap is closed
   */
  @Override
  public final void visitRetained(HeapVisitor visitor) {
    checkOpen();
    for (int i = 0; i < used; i++) {
      if (pointers[i] != 0 && (holds[i] > 0 || pins[i] > 0 || !pressure && soft[i] > 0)) {
        visitor.visit(rootType(i), pointers[i]);
      }
    }
  }

  /**
   * Tests whether a base or interior pointer still belongs to this heap.
   *
   * @param ptr pointer to test
   * @return {@code true} while the allocation is live; {@code false} for null,
   *         stale, or foreign pointers and after close
   */
  @Override
  public final boolean isAlive(long ptr) {
    if (closed || ptr == 0) {
      return false;
    }
    if (lookup.containsKey(ptr)) {
      return true;
    }
    try {
      return lookup.containsKey(base(ptr));
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      return false;
    }
  }

  /**
   * Creates a generation-checked pin for an allocation.
   *
   * @param ptr base or interior pointer to pin
   * @param mode retention policy; {@link PinMode#NEVER} creates a non-retaining
   *         view, {@link PinMode#WEAK} survives ordinary GC, and
   *         {@link PinMode#STRONG} prevents reclamation
   * @return pin that must be closed when the view is no longer needed
   * @throws IllegalStateException if the pointer is dead or the heap is closed
   */
  @Override
  public final Pin.Heap<T> pin(long ptr, PinMode mode) {
    int index = entry(ptr);
    if (mode == PinMode.STRONG) {
      pins[index]++;
    } else if (mode == PinMode.WEAK) {
      soft[index]++;
    }
    return new Pin.Heap<>(this, index, identities[index], ptr, mode);
  }

  /**
   * Disposes every live allocation and releases subclass-owned storage.
   *
   * <p>All allocations are attempted even when one disposer fails; subsequent
   * failures are attached as suppressed exceptions. The heap is marked closed
   * before a failure is rethrown, so close remains idempotent.</p>
   *
   * @throws RuntimeException if one or more allocation disposers fail
   */
  @Override
  public final void close() {
    if (closed) {
      return;
    }
    @Nullable RuntimeException failure = null;
    for (int i = 0; i < used; i++) {
      if (pointers[i] != 0) {
        try {
          remove(i);
        } catch (RuntimeException e) {
          if (failure == null) {
            failure = e;
          } else {
            failure.addSuppressed(e);
          }
        }
      }
    }
    closed = true;
    if (failure != null) {
      throw failure;
    }
  }

  private void remove(int index) {
    long ptr = pointers[index];
    try {
      dispose(index, ptr);
    } finally {
      lookup.remove(ptr);
      pointers[index] = 0;
      liveCount--;
      holds[index] = 0;
      pins[index] = 0;
      soft[index] = 0;
      next[index] = free;
      free = index;
    }
  }

  final boolean alive(int index, long stamp) {
    return !closed && pointers[index] != 0 && identities[index] == stamp;
  }

  final void unpin(int index, long stamp, PinMode mode) {
    if (!alive(index, stamp)) {
      return;
    }
    if (mode == PinMode.STRONG) {
      pins[index]--;
    } else if (mode == PinMode.WEAK) {
      soft[index]--;
    }
  }
}
