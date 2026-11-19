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
 * Hash-table and metatable storage operations. Tables use open addressing over
 * key/value slot pairs and keep metatable hooks in a dedicated backing region;
 * raw lookup is expected O(1), while mutation and resizing are confined to the
 * owning VM thread.
 */
public final class ElxTable {
  private static final int LENGTH = 0, CAPACITY = 1, DATA = 2;
  private static final int META = 3, HOOKS = 4;

  private ElxTable() {
  }

  /** Monotonic structural version, unchanged when replacing an existing value. */
  public static long version(Memory memory, long table) {
    return Slot.bits(memory, Slot.address(table, 5));
  }

  private static void modified(Memory memory, long table) {
    Slot.write(memory, Slot.address(table, 5), Type.LONG, version(memory, table) + 1);
  }

  /**
   * Allocates an empty open-addressed table descriptor.
   *
   * @param heap owning ELX heap
   * @return table pointer
   * @throws IllegalStateException if the heap is closed
   */
  public static long create(ElxHeap heap) {
    return heap.allocate(ElxHeap.Layout.TABLE, 0);
  }

  /**
   * Returns the table's metatable pointer, or zero when absent.
   *
   * @param memory memory containing the table
   * @param table  table pointer
   * @return metatable pointer, or zero
   */
  public static long metatable(Memory memory, long table) {
    return Slot.bits(memory, Slot.address(table, META));
  }

  /**
   * Installs or clears a metatable and materializes its hook cache.
   *
   * @param heap  owning ELX heap
   * @param table table receiving the metatable
   * @param meta  metatable pointer, or zero to clear it
   * @throws IllegalArgumentException if a metatable hook has an invalid type
   * @throws IllegalStateException    if the heap or table is dead
   */
  public static void setMetatable(ElxHeap heap, long table, long meta) {
    Memory memory = heap.memory();
    if (meta != 0) {
      ElxTable.ensureHooks(heap, meta);
    }
    Slot.write(memory, Slot.address(table, META), meta == 0 ? Type.NULL : Type.TABLE, meta);
  }

  /**
   * Returns a cached metamethod slot address, or zero when unavailable.
   *
   * @param memory memory containing the table
   * @param table  table whose metatable is queried
   * @param method metamethod ordinal
   * @return hook slot address, or zero when no hook is installed
   */
  public static long hook(Memory memory, long table, int method) {
    if (method < 0) {
      return 0;
    }
    long meta = ElxTable.metatable(memory, table);
    if (meta == 0) {
      return 0;
    }
    long hooks = Slot.bits(memory, Slot.address(meta, HOOKS)), cell = Slot.address(hooks, method);
    Type type = Slot.type(memory, cell);
    return type == Type.NULL || type == Type.VOID ? 0 : cell;
  }

  private static void ensureHooks(ElxHeap heap, long meta) {
    Memory memory = heap.memory();
    if (Slot.bits(memory, Slot.address(meta, HOOKS)) != 0) {
      return;
    }
    long hooks = heap.allocate(ElxHeap.Layout.VALUES, Metamethod.count());
    long data = ElxTable.data(memory, meta);
    int capacity = ElxTable.capacity(memory, meta);
    for (int i = 0; i < capacity; i++) {
      long at = Slot.address(data, i * 2);
      if (Slot.type(memory, at) != Type.STRING) {
        continue;
      }
      int method = Metamethod.name(memory, Slot.bits(memory, at));
      if (method < 0) {
        continue;
      }
      ElxTable.validateHook(memory, method, at + Slot.SIZE);
      Slot.copy(memory, Slot.address(hooks, method), at + Slot.SIZE, 1);
    }
    Slot.write(memory, Slot.address(meta, HOOKS), Type.PTR, hooks);
  }

  private static void validateHook(Memory memory, int method, long value) {
    Type type = Slot.type(memory, value);
    if (type == Type.NULL || type == Type.VOID || type == Type.FUNCTION) {
      return;
    }
    throw new IllegalArgumentException("Metamethod " + Metamethod.at(method) + " requires a function");
  }

  /**
   * Returns the number of live key/value pairs.
   *
   * @param memory    memory containing the table
   * @param container table pointer
   * @return number of occupied entries
   */
  public static int size(Memory memory, long container) {
    return (int) Slot.bits(memory, container);
  }

  /**
   * Returns the power-of-two probe capacity of a table.
   *
   * @param memory    memory containing the table
   * @param container table pointer
   * @return probe capacity
   */
  public static int capacity(Memory memory, long container) {
    return (int) Slot.bits(memory, Slot.address(container, CAPACITY));
  }

  /**
   * Returns the key/value backing-region pointer of a table.
   *
   * @param memory    memory containing the table
   * @param container table pointer
   * @return backing region pointer
   */
  public static long data(Memory memory, long container) {
    return Slot.bits(memory, Slot.address(container, DATA));
  }

  private static void length(Memory memory, long container, int size) {
    Slot.write(memory, Slot.address(container, LENGTH), Type.INT, size);
  }

  /**
   * Returns the value slot owning a key, or zero when the key is absent.
   *
   * @param memory memory containing the table
   * @param table  table pointer
   * @param key    source key slot
   * @return value slot address, or zero for an absent key
   */
  public static long own(Memory memory, long table, long key) {
    return own(memory, table, key, RawValues.hash(memory, key));
  }

  public static long own(ElxHeap heap, long table, long key) {
    return own(heap.memory(), table, key, RawValues.hash(heap, key));
  }

  private static long own(Memory memory, long table, long key, long hash) {
    long data = ElxTable.data(memory, table);
    int index = find(memory, data, capacity(memory, table), key, hash);
    long at = Slot.address(data, index * 2);
    return Slot.type(memory, at) == Type.INVALID ? 0 : at + Slot.SIZE;
  }

  /**
   * Reads a key and writes its value or {@link Type#NULL} to {@code result}.
   *
   * @param memory memory containing the table
   * @param table  table pointer
   * @param key    source key slot
   * @param result destination slot
   */
  public static void get(Memory memory, long table, long key, long result) {
    get(memory, own(memory, table, key), result);
  }

  public static void get(ElxHeap heap, long table, long key, long result) {
    get(heap.memory(), own(heap, table, key), result);
  }

  private static void get(Memory memory, long value, long result) {
    if (value == 0) {
      Slot.write(memory, result, Type.VOID, 0);
    } else {
      Slot.copy(memory, result, value, 1);
    }
  }

  /**
   * Removes a key/value pair and repairs the affected probe cluster.
   *
   * @param memory memory containing the table
   * @param table  table pointer
   * @param key    source key slot
   * @return {@code true} when an entry was removed
   */
  public static boolean remove(Memory memory, long table, long key) {
    long storage = ElxTable.data(memory, table);
    int capacity = ElxTable.capacity(memory, table), index = ElxTable.find(memory, storage, capacity, key);
    long cell = Slot.address(storage, index * 2);
    if (Slot.type(memory, cell) == Type.INVALID) {
      return false;
    }
    long hooks = Slot.bits(memory, Slot.address(table, HOOKS));
    int method = hooks != 0 && Slot.type(memory, key) == Type.STRING ? Metamethod.name(memory, Slot.bits(memory, key)) : -1;
    if (method >= 0) {
      Slot.write(memory, Slot.address(hooks, method), Type.NULL, 0);
    }
    Slot.write(memory, cell, Type.INVALID, 0);
    Slot.write(memory, cell + Slot.SIZE, Type.NULL, 0);
    for (int next = (index + 1) & (capacity - 1); ; next = (next + 1) & (capacity - 1)) {
      long item = Slot.address(storage, next * 2);
      Type type = Slot.type(memory, item);
      if (type == Type.INVALID) {
        break;
      }
      int target = ElxTable.find(memory, storage, capacity, item);
      if (target != next) {
        Slot.copy(memory, Slot.address(storage, target * 2), item, 2);
        Slot.write(memory, item, Type.INVALID, 0);
        Slot.write(memory, item + Slot.SIZE, Type.NULL, 0);
      }
    }
    modified(memory, table);
    Slot.write(memory, table, Type.INT, ElxTable.size(memory, table) - 1);
    return true;
  }

  /**
   * Inserts or replaces a key/value pair, resizing when the load factor reaches
   * 75 percent. Existing operands are read before writes so aliases are safe;
   * average lookup is {@code O(1)}, while a resize is linear in table capacity.
   *
   * @param heap  owning ELX heap
   * @param table table receiving the entry
   * @param key   source key slot
   * @param value source value slot
   * @throws IllegalArgumentException if the key is {@link Type#INVALID} or NaN,
   *                                  or a metamethod hook has the wrong type
   * @throws IllegalStateException    if the heap or table is dead
   */
  public static void set(ElxHeap heap, long table, long key, long value) {
    Memory memory = heap.memory();
    Type type = Slot.type(memory, key);
    long bits = Slot.bits(memory, key);
    long hooks = Slot.bits(memory, Slot.address(table, HOOKS));
    int method = hooks != 0 && type == Type.STRING ? Metamethod.name(memory, bits) : -1;
    if (method >= 0) {
      ElxTable.validateHook(memory, method, value);
    }
    if (type == Type.INVALID) {
      throw new IllegalArgumentException("INVALID is not a table key");
    }
    if (type == Type.FLOAT || type == Type.DOUBLE) {
      if (Double.isNaN(Type.asDouble(bits, type))) {
        throw new IllegalArgumentException("NaN is not a table key");
      }
    }
    int capacity = ElxTable.capacity(memory, table);
    long data = ElxTable.data(memory, table);
    long hash = RawValues.hash(heap, key);
    int index = find(memory, data, capacity, key, hash);
    boolean fresh = Slot.type(memory, Slot.address(data, index * 2)) == Type.INVALID;
    if (fresh) {
      modified(memory, table);
    }
    if (fresh && ElxTable.size(memory, table) + 1 > capacity - capacity / 4) {
      int next = Math.multiplyExact(capacity, 2);
      long replacement = heap.allocate(ElxHeap.Layout.VALUES, Math.multiplyExact(next, 2));
      for (int i = 0; i < next; i++) {
        Slot.write(memory, Slot.address(replacement, i * 2), Type.INVALID, 0);
      }
      for (int i = 0; i < capacity; i++) {
        long old = Slot.address(data, i * 2);
        Type oldType = Slot.type(memory, old);
        if (oldType == Type.INVALID) {
          continue;
        }
        int at = find(memory, replacement, next, old, RawValues.hash(heap, old));
        Slot.copy(memory, Slot.address(replacement, at * 2), old, 2);
      }
      data = replacement;
      capacity = next;
      Slot.write(memory, Slot.address(table, DATA), Type.PTR, data);
      Slot.write(memory, Slot.address(table, CAPACITY), Type.INT, capacity);
      index = find(memory, data, capacity, key, hash);
    }
    long at = Slot.address(data, index * 2);
    // Read operands before writing: result/operands may alias in hand-written programs.
    Type valueType = Slot.type(memory, value);
    byte flags = Slot.flags(memory, value);
    long valueBits = Slot.bits(memory, value);
    Slot.write(memory, at, type, bits);
    Slot.write(memory, at + Slot.SIZE, valueType, flags, valueBits);
    if (fresh) {
      ElxTable.length(memory, table, ElxTable.size(memory, table) + 1);
    }
    if (method >= 0) {
      Slot.write(memory, Slot.address(hooks, method), valueType, flags, valueBits);
    }
  }

  /**
   * Writes one table iterator tuple and advances the cursor.
   *
   * @param memory memory containing the table
   * @param result destination tuple of four slots
   * @param table  table pointer
   * @param cursor packed ordinal/index cursor, or a negative exhausted cursor
   */
  public static void next(Memory memory, long result, long table, long cursor) {
    int index = (int) cursor, ordinal = (int) (cursor >>> 32), capacity = ElxTable.capacity(memory, table);
    long data = ElxTable.data(memory, table);
    while (cursor >= 0 && index >= 0 && index < capacity) {
      long at = Slot.address(data, index * 2);
      if (Slot.type(memory, at) != Type.INVALID) {
        Slot.copy(memory, result + Slot.SIZE, at, 2);
        Slot.write(memory, result, Type.INT, ordinal);
        Slot.write(memory, result + 3L * Slot.SIZE, Type.LONG, ((long) (ordinal + 1) << 32) | (index + 1L));
        return;
      }
      index++;
    }
    ElxTable.exhausted(memory, result, 4);
  }

  private static void exhausted(Memory memory, long result, int count) {
    for (int i = 0; i < count - 1; i++) {
      Slot.write(memory, Slot.address(result, i), Type.NULL, 0);
    }
    Slot.write(memory, Slot.address(result, count - 1), Type.LONG, -1);
  }

  private static boolean has(Memory memory, long table, long key) {
    long data = ElxTable.data(memory, table);
    return Slot.type(memory, Slot.address(data, ElxTable.find(memory, data, ElxTable.capacity(memory, table), key) * 2)) != Type.INVALID;
  }

  private static int find(Memory memory, long data, int capacity, long key) {
    return find(memory, data, capacity, key, RawValues.hash(memory, key));
  }

  private static int find(Memory memory, long data, int capacity, long key, long hash) {
    int index = (int) hash & (capacity - 1);
    for (; ; ) {
      long probe = Slot.address(data, index * 2);
      if (Slot.type(memory, probe) == Type.INVALID || RawValues.equal(memory, probe, key)) {
        return index;
      }
      index = (index + 1) & (capacity - 1);
    }
  }

  /**
   * Combines two tables for ELX union, intersection, difference, or symmetric
   * difference operators.
   *
   * @param heap   owning ELX heap
   * @param op     table operator
   * @param result destination slot
   * @param left   left table pointer
   * @param right  right table pointer
   * @throws IllegalArgumentException if {@code op} is unsupported
   * @throws IllegalStateException    if the heap or an operand is dead
   */
  public static void combine(ElxHeap heap, Op op, long result, long left, long right) {
    long output = create(heap);
    copySide(heap, op == Op.BOR ? Op.ADD : op == Op.BXOR ? Op.SUB : op, output, left, right);
    if (op == Op.ADD) {
      copySide(heap, Op.ADD, output, right, left);
    } else if (op == Op.BOR || op == Op.BXOR) {
      copySide(heap, Op.SUB, output, right, left);
    }
    Slot.write(heap.memory(), result, Type.TABLE, output);
  }

  private static void copySide(ElxHeap heap, Op op, long output, long source, long other) {
    Memory memory = heap.memory();
    long storage = data(memory, source);
    for (int i = 0; i < capacity(memory, source); i++) {
      long key = Slot.address(storage, i * 2);
      if (Slot.type(memory, key) == Type.INVALID) {
        continue;
      }
      boolean include = op == Op.ADD || (op == Op.BAND) == has(memory, other, key);
      if (include) {
        set(heap, output, key, key + Slot.SIZE);
      }
    }
  }
}
