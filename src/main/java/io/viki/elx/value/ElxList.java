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

package io.viki.elx.value;

import io.viki.elx.Type;
import io.viki.elx.asm.Op;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;

/**
 * List allocation and value operations over the shared slot layout. A list is a
 * descriptor pointing at a growable value-slot region; callers must use the
 * supplied heap so backing regions remain visible to GC. Static methods are
 * thread-confined to that heap and memory.
 */
public final class ElxList {
  private static final int LENGTH = 0, CAPACITY = 1, DATA = 2;

  private ElxList() {
  }

  /**
   * Allocates an empty list descriptor and backing storage.
   *
   * @param heap owning ELX heap
   * @return list pointer
   * @throws IllegalStateException if the heap is closed
   */
  public static long create(ElxHeap heap) {
    return heap.allocate(ElxHeap.Layout.LIST, 0);
  }

  /**
   * Appends one existing slot value to a list, growing the backing region as needed.
   *
   * @param heap owning ELX heap
   * @param list list pointer
   * @param value source slot address
   * @throws IndexOutOfBoundsException if the list descriptor is invalid
   * @throws ArithmeticException if the backing capacity overflows
   * @throws IllegalStateException if the heap is closed or the list is dead
   */
  public static void append(ElxHeap heap, long list, long value) {
    Memory memory = heap.memory();
    int length = ElxList.size(memory, list), capacity = ElxList.capacity(memory, list);
    long data = ElxList.data(memory, list);
    if (length == capacity) {
      int next = Math.multiplyExact(capacity, 2);
      long replacement = heap.allocate(ElxHeap.Layout.VALUES, next);
      Slot.copy(memory, replacement, data, length);
      data = replacement;
      Slot.write(memory, Slot.address(list, DATA), Type.PTR, data);
      Slot.write(memory, Slot.address(list, CAPACITY), Type.INT, next);
    }
    Slot.copy(memory, Slot.address(data, length), value, 1);
    ElxList.length(memory, list, length + 1);
  }

  /**
   * Appends a typed raw value to a list.
   *
   * @param heap owning ELX heap
   * @param list list pointer
   * @param type runtime type
   * @param bits raw payload
   * @throws IndexOutOfBoundsException if the list descriptor is invalid
   * @throws ArithmeticException if the backing capacity overflows
   * @throws IllegalStateException if the heap is closed or the list is dead
   */
  public static void append(ElxHeap heap, long list, Type type, long bits) {
    Memory memory = heap.memory();
    int length = ElxList.size(memory, list), capacity = ElxList.capacity(memory, list);
    long data = ElxList.data(memory, list);
    if (length == capacity) {
      int next = Math.multiplyExact(capacity, 2);
      long replacement = heap.allocate(ElxHeap.Layout.VALUES, next);
      Slot.copy(memory, replacement, data, length);
      data = replacement;
      Slot.write(memory, Slot.address(list, DATA), Type.PTR, data);
      Slot.write(memory, Slot.address(list, CAPACITY), Type.INT, next);
    }
    Slot.write(memory, Slot.address(data, length), type, bits);
    ElxList.length(memory, list, length + 1);
  }

  /**
   * Copies one list element into a destination slot.
   *
   * @param memory memory containing the list
   * @param list list pointer
   * @param index element index
   * @param result destination slot address
   * @throws IndexOutOfBoundsException if {@code index} is outside the logical list
   */
  public static void get(Memory memory, long list, int index, long result) {
    if (index < 0 || index >= ElxList.size(memory, list)) {
      throw new IndexOutOfBoundsException("ELX list index " + index + " / " + ElxList.size(memory, list));
    }
    Slot.copy(memory, result, Slot.address(ElxList.data(memory, list), index), 1);
  }

  /**
   * Replaces one element, or appends when {@code index == size}.
   *
   * @param heap owning ELX heap
   * @param list list pointer
   * @param index element index
   * @param value source slot address
   * @throws IndexOutOfBoundsException if {@code index} is neither an existing
   *                                  element nor the append position
   * @throws IllegalStateException if the heap is closed or the list is dead
   */
  public static void set(ElxHeap heap, long list, int index, long value) {
    Memory memory = heap.memory();
    if (index == ElxList.size(memory, list)) {
      ElxList.append(heap, list, value);
      return;
    }
    if (index < 0 || index >= ElxList.size(memory, list)) {
      throw new IndexOutOfBoundsException("ELX list index " + index + " / " + ElxList.size(memory, list));
    }
    Slot.copy(memory, Slot.address(ElxList.data(memory, list), index), value, 1);
  }

  /**
   * Writes one iterator tuple (index, value, next cursor) into result slots.
   *
   * @param memory memory containing the list
   * @param result destination tuple address
   * @param list list pointer
   * @param cursor current element cursor
   * @throws IndexOutOfBoundsException if the destination tuple is not addressable
   */
  public static void next(Memory memory, long result, long list, long cursor) {
    int index = (int) cursor;
    if (cursor < 0 || index >= ElxList.size(memory, list)) {
      Slot.write(memory, result, Type.NULL, 0);
      Slot.write(memory, result + Slot.SIZE, Type.NULL, 0);
      Slot.write(memory, result + 2L * Slot.SIZE, Type.LONG, -1);
      return;
    }
    Slot.write(memory, result, Type.LONG, index);
    Slot.copy(memory, result + Slot.SIZE, Slot.address(ElxList.data(memory, list), index), 1);
    Slot.write(memory, result + 2L * Slot.SIZE, Type.LONG, index + 1L);
  }

  /**
   * Implements list repetition for the VM list operator.
   *
   * @param heap owning ELX heap
   * @param result destination slot
   * @param source source list pointer
   * @param times number of repetitions
   * @throws IllegalArgumentException if {@code times} or the resulting length is invalid
   * @throws ArithmeticException if the resulting length overflows
   */
  static void repeat(ElxHeap heap, long result, long source, long times) {
    Memory memory = heap.memory();
    if (times < 0) {
      throw new IllegalArgumentException("Negative list repeat: " + times);
    }
    long count = Math.multiplyExact(ElxList.size(memory, source), times);
    if (count > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("List repeat too large: " + count);
    }
    long list = heap.allocate(ElxHeap.Layout.LIST, (int) count), sourceData = ElxList.data(memory, source);
    if (count == 0) {
      Slot.write(memory, result, Type.LIST, list);
      return;
    }
    for (long i = 0; i < times; i++) {
      for (int j = 0; j < ElxList.size(memory, source); j++) {
        ElxList.append(heap, list, Slot.address(sourceData, j));
      }
    }
    Slot.write(memory, result, Type.LIST, list);
  }

  private static boolean contains(Memory memory, long list, long value) {
    long data = ElxList.data(memory, list);
    for (int i = 0; i < ElxList.size(memory, list); i++) {
      if (ValueOperations.equal(memory, Slot.address(data, i), value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the number of logical elements in a list descriptor.
   *
   * @param memory memory containing the list
   * @param container list pointer
   * @return logical element count
   * @throws IllegalArgumentException if the descriptor does not contain a valid count
   */
  public static int size(Memory memory, long container) {
    return (int) Slot.bits(memory, container);
  }

  /**
   * Returns the allocated backing capacity of a list descriptor.
   *
   * @param memory memory containing the list
   * @param container list pointer
   * @return backing slot capacity
   */
  static int capacity(Memory memory, long container) {
    return (int) Slot.bits(memory, Slot.address(container, CAPACITY));
  }

  /**
   * Returns the backing value-region pointer of a list descriptor.
   *
   * @param memory memory containing the list
   * @param container list pointer
   * @return backing value-region pointer
   * @throws IllegalArgumentException if the descriptor does not contain a pointer
   */
  public static long data(Memory memory, long container) {
    return Slot.bits(memory, Slot.address(container, DATA));
  }

  private static void length(Memory memory, long container, int size) {
    Slot.write(memory, Slot.address(container, LENGTH), Type.INT, size);
  }

  /**
   * Combines two lists for ELX addition/union/intersection/difference operators.
   *
   * @param heap owning ELX heap
   * @param op list operator
   * @param result destination slot
   * @param left left list pointer
   * @param right right list pointer
   * @throws IllegalArgumentException if {@code op} is not a supported list operator
   * @throws IllegalStateException if the heap is closed or an operand is dead
   */
  public static void combine(ElxHeap heap, Op op, long result, long left, long right) {
    long output = create(heap);
    copySide(heap, op == Op.BOR ? Op.IADD : op == Op.BXOR ? Op.ISUB : op, output, left, right);
    if (op == Op.IADD) {
      copySide(heap, Op.IADD, output, right, left);
    } else if (op == Op.BOR || op == Op.BXOR) {
      copySide(heap, Op.ISUB, output, right, left);
    }
    Slot.write(heap.memory(), result, Type.LIST, output);
  }

  private static void copySide(ElxHeap heap, Op op, long output, long source, long other) {
    Memory memory = heap.memory();
    long storage = data(memory, source);
    for (int i = 0; i < size(memory, source); i++) {
      long key = Slot.address(storage, i);
      boolean include = op == Op.IADD || (op == Op.BAND) == contains(memory, other, key);
      if (include) {
        append(heap, output, key);
      }
    }
  }
}
