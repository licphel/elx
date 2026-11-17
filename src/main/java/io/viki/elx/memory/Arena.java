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

import java.util.Arrays;

/**
 * Shared size-class allocation policy for heaps and register windows. Borrows Memory; thread-confined.
 * An array-backed treap indexes live extents: allocation bookkeeping, free and owner lookup are
 * expected O(log live regions). Nodes are recycled, without per-region Java objects or array shifts.
 */
public final class Arena {
  private static final int MIN_CLASS = 4;
  private static final int MAX_CLASS = 30;
  private static final int NULL_RESERVATION = 16;
  private final Memory memory;
  private final int[] free = new int[MAX_CLASS + 1];
  private int end = NULL_RESERVATION;
  private int[] bases = new int[16], sizes = new int[16];
  private int[] left = new int[16], right = new int[16], priorities = new int[16];
  private int root = -1, used, recycled = -1;

  /**
   * Creates an allocator using the supplied raw memory backend.
   *
   * <p>The arena owns allocation metadata and address ranges, while the
   * {@code memory} object remains owned by its caller and must outlive this
   * arena. Instances are thread-confined.</p>
   *
   * @param memory raw storage borrowed by this allocator
   */
  public Arena(Memory memory) {
    this.memory = memory;
  }

  // Hash the address so sequential bump allocations do not form a linear search tree.
  private static int priority(int address) {
    int value = address;
    value = (value ^ (value >>> 16)) * 0x7feb352d;
    value = (value ^ (value >>> 15)) * 0x846ca68b;
    return value ^ (value >>> 16);
  }

  /**
   * Returns the raw memory backend used for allocation storage.
   *
   * @return borrowed memory backend; closing the arena does not close it
   */
  public Memory memory() {
    return memory;
  }

  /**
   * Allocates a cleared power-of-two block.
   *
   * <p>The request is rounded up to a size class with a minimum of sixteen
   * bytes. Freed blocks are split from larger classes when possible; otherwise
   * the arena grows the backing memory. Allocation and bookkeeping take
   * expected {@code O(log R)} time for {@code R} live regions and do not move
   * existing regions.</p>
   *
   * @param bytes requested payload size
   * @return allocation base address, aligned to its size class
   * @throws IllegalArgumentException if the size exceeds the supported class range
   * @throws OutOfMemoryError         if the 32-bit ELX address space cannot grow
   */
  public long allocate(int bytes) {
    if (bytes < 0 || bytes > 1 << MAX_CLASS) {
      throw new IllegalArgumentException("Unsupported allocation size: " + bytes);
    }
    int power = Math.max(MIN_CLASS, 32 - Integer.numberOfLeadingZeros(Math.max(1, bytes) - 1));
    int size = 1 << power;
    if (free[power] == 0) {
      int larger = power + 1;
      while (larger <= MAX_CLASS && free[larger] == 0) {
        larger++;
      }
      if (larger <= MAX_CLASS) {
        int block = free[larger];
        free[larger] = memory.load32(block);
        while (larger > power) {
          --larger;
          int other = block + (1 << larger);
          memory.store32(other, free[larger]);
          free[larger] = other;
        }
        memory.store32(block, free[power]);
        free[power] = block;
      }
    }
    int base = free[power];
    if (base != 0) {
      free[power] = memory.load32(base);
    } else {
      long required = (long) end + size;
      if (required > Integer.MAX_VALUE) {
        throw new OutOfMemoryError("ELX address space exhausted");
      }
      memory.ensureCapacity((int) required);
      base = end;
      end = (int) required;
    }
    int node;
    if (recycled >= 0) {
      node = recycled;
      recycled = left[node];
    } else {
      node = used++;
      if (node == bases.length) {
        int capacity = node * 2;
        bases = Arrays.copyOf(bases, capacity);
        sizes = Arrays.copyOf(sizes, capacity);
        left = Arrays.copyOf(left, capacity);
        right = Arrays.copyOf(right, capacity);
        priorities = Arrays.copyOf(priorities, capacity);
      }
    }
    bases[node] = base;
    sizes[node] = size;
    left[node] = right[node] = -1;
    priorities[node] = priority(base);
    root = insert(root, node);
    memory.clear(base, size);
    return base;
  }

  /**
   * Returns a live allocation to its size-class free list.
   *
   * <p>The address must be the exact base returned by {@link #allocate(int)};
   * interior addresses are rejected so a double free cannot corrupt the free
   * lists. The released bytes become available to later allocations and are
   * cleared again when reused.</p>
   *
   * @param address allocation base address
   * @throws ArithmeticException      if the address does not fit the ELX address space
   * @throws IllegalArgumentException if the address is not a live allocation base
   */
  public void free(long address) {
    int base = Math.toIntExact(address), at = root;
    while (at >= 0 && bases[at] != base) {
      at = base < bases[at] ? left[at] : right[at];
    }
    if (at < 0) {
      throw new IllegalArgumentException("Not a live allocation base: " + address);
    }
    int power = Integer.numberOfTrailingZeros(sizes[at]);
    root = remove(root, base);
    left[at] = recycled;
    recycled = at;
    memory.store32(address, free[power]);
    free[power] = base;
  }

  /**
   * Finds the live allocation containing an address.
   *
   * <p>Lookup uses the treap of live extents and therefore has expected
   * {@code O(log R)} complexity. The result is the allocation base, not the
   * supplied interior address.</p>
   *
   * @param address address inside a live allocation
   * @return allocation base address
   * @throws ArithmeticException      if the address does not fit the ELX address space
   * @throws IllegalArgumentException if no live allocation contains the address
   */
  public long owner(long address) {
    int value = Math.toIntExact(address), at = root, candidate = -1;
    while (at >= 0) {
      if (value < bases[at]) {
        at = left[at];
      } else {
        candidate = at;
        if (value == bases[at]) {
          break;
        }
        at = right[at];
      }
    }
    if (candidate < 0 || address >= (long) bases[candidate] + sizes[candidate]) {
      throw new IllegalArgumentException("Address is not allocated: " + address);
    }
    return bases[candidate];
  }

  private int insert(int at, int node) {
    if (at < 0) {
      return node;
    }
    if (bases[node] < bases[at]) {
      left[at] = insert(left[at], node);
      if (priorities[left[at]] < priorities[at]) {
        int top = left[at];
        left[at] = right[top];
        right[top] = at;
        return top;
      }
    } else {
      right[at] = insert(right[at], node);
      if (priorities[right[at]] < priorities[at]) {
        int top = right[at];
        right[at] = left[top];
        left[top] = at;
        return top;
      }
    }
    return at;
  }

  private int remove(int at, int base) {
    if (base == bases[at]) {
      return merge(left[at], right[at]);
    }
    if (base < bases[at]) {
      left[at] = remove(left[at], base);
    } else {
      right[at] = remove(right[at], base);
    }
    return at;
  }

  private int merge(int lhs, int rhs) {
    if (lhs < 0) {
      return rhs;
    }
    if (rhs < 0) {
      return lhs;
    }
    if (priorities[lhs] < priorities[rhs]) {
      right[lhs] = merge(right[lhs], rhs);
      return lhs;
    }
    left[rhs] = merge(lhs, left[rhs]);
    return rhs;
  }
}
