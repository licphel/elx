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
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;

/**
 * GC-managed references to ELX value slots. Handles describe local registers,
 * captures, globals, table entries, list indices, and owned slots; resolving a
 * handle is deferred until use so moving or resized containers can update the
 * target without changing the handle. Methods are thread-confined.
 */
public final class Pointers {
  private static final int KIND = 0, OWNER = 1, KEY = 2, AUX = 3;
  private static final int LOCAL = 1, GLOBAL = 2, INDEX = 3, OWNED_SLOT = 4;

  private Pointers() {
  }

  /**
   * Returns the number of values produced by dereferencing a pointer/list value.
   *
   * @param memory memory containing the value
   * @param value  source value slot
   * @return list length, or one for a single pointer
   * @throws IllegalArgumentException if the value is neither a list nor pointer
   */
  public static int dereferenceCount(Memory memory, long value) {
    Type type = Slot.type(memory, value);
    if (type == Type.LIST) {
      return ElxList.size(memory, Slot.bits(memory, value));
    }
    if (type == Type.PTR) {
      return 1;
    }
    throw new IllegalArgumentException("Cannot dereference " + type);
  }

  /**
   * Creates a pointer to a register in a live local frame.
   *
   * @param heap       owning ELX heap
   * @param frameToken frame identity recorded by the VM
   * @param register   register index
   * @return GC-managed pointer handle
   * @throws IllegalArgumentException if the register index is invalid
   */
  public static long local(ElxHeap heap, long frameToken, int register) {
    return create(heap, LOCAL, Type.LONG, frameToken, Type.INT, register);
  }

  /**
   * Creates a pointer to a closure capture slot.
   *
   * @param heap    owning ELX heap
   * @param closure closure allocation pointer
   * @param capture zero-based capture index
   * @return GC-managed pointer handle
   */
  public static long capture(ElxHeap heap, long closure, int capture) {
    return create(heap, OWNED_SLOT, Type.FUNCTION, closure, Type.INT, capture + 1L);
  }

  /**
   * Creates a pointer to a linked constant slot.
   *
   * @param heap      owning ELX heap
   * @param constants constant-region pointer
   * @param index     zero-based constant index
   * @return GC-managed pointer handle
   */
  public static long constant(ElxHeap heap, long constants, int index) {
    return create(heap, OWNED_SLOT, Type.PTR, constants, Type.INT, index);
  }

  /**
   * Creates a pointer to a global table entry, materializing a null slot if absent.
   *
   * @param heap    owning ELX heap
   * @param globals global table pointer
   * @param key     key slot identifying the global
   * @return GC-managed pointer handle
   */
  public static long global(ElxHeap heap, long globals, long key) {
    Memory memory = heap.memory();
    if (ElxTable.own(heap, globals, key) == 0) {
      long empty = heap.arena().allocate(Slot.SIZE);
      try {
        Slot.write(memory, empty, Type.NULL, 0);
        ElxTable.set(heap, globals, key, empty);
      } finally {
        heap.arena().free(empty);
      }
    }
    return create(heap, GLOBAL, Type.TABLE, globals, Slot.type(memory, key), Slot.bits(memory, key));
  }

  /**
   * Creates a pointer to a table entry or list index.
   *
   * @param heap      owning ELX heap
   * @param container list or table value slot
   * @param key       key/index slot
   * @return GC-managed pointer handle
   * @throws IllegalArgumentException if the container is not a list or table
   */
  public static long indexed(ElxHeap heap, long container, long key) {
    Memory memory = heap.memory();
    Type type = Slot.type(memory, container);
    if (type != Type.LIST && type != Type.TABLE) {
      throw new IllegalArgumentException("Addressable container required, got " + type + " (key type " + Slot.type(memory, key) + ")");
    }
    return create(heap, INDEX, type, Slot.bits(memory, container), Slot.type(memory, key), Slot.bits(memory, key));
  }

  private static long create(ElxHeap heap, int kind, Type ownerType, long owner, Type keyType, long key) {
    Memory memory = heap.memory();
    long reference = heap.allocate(ElxHeap.Layout.REFERENCE, 4);
    Slot.write(memory, Slot.address(reference, KIND), Type.INT, kind);
    Slot.write(memory, Slot.address(reference, OWNER), ownerType, owner);
    Slot.write(memory, Slot.address(reference, KEY), keyType, key);
    Slot.write(memory, Slot.address(reference, AUX), Type.NULL, 0);
    return reference;
  }

  /**
   * Resolves the current target slot; local targets are valid only while their
   * owning frame is active. Table and list targets are looked up on every call,
   * so resize and hash-table relocation do not invalidate the handle.
   *
   * @param heap        owning ELX heap
   * @param pointer     pointer handle
   * @param frameTokens active frame identities from newest to oldest
   * @param bases       active frame bases corresponding to {@code frameTokens}
   * @param depth       number of active frames
   * @return current destination slot address
   * @throws IllegalStateException if the handle, frame, entry, or index is dead
   */
  public static long data(ElxHeap heap, long pointer, long[] frameTokens, long[] bases, int depth) {
    Memory memory = heap.memory();
    if (!heap.isAlive(pointer) || heap.layout(pointer) != ElxHeap.Layout.REFERENCE) {
      throw new IllegalStateException("Dangling ELX pointer handle: " + pointer);
    }
    int kind = (int) Slot.bits(memory, Slot.address(pointer, KIND));
    long owner = Slot.bits(memory, Slot.address(pointer, OWNER));
    long key = Slot.address(pointer, KEY);
    if (kind == LOCAL) {
      long token = owner;
      for (int i = depth - 1; i >= 0; i--) {
        if (frameTokens[i] == token) {
          int register = Math.toIntExact(Slot.bits(memory, key));
          return Slot.address(bases[i], register);
        }
      }
      throw new IllegalStateException("Pointer to a returned local frame");
    }
    if (kind == GLOBAL) {
      long target = ElxTable.own(heap, owner, key);
      if (target == 0) {
        throw new IllegalStateException("Pointer to a removed global");
      }
      return target;
    }
    if (kind == INDEX) {
      Type ownerType = Slot.type(memory, Slot.address(pointer, OWNER));
      if (ownerType == Type.TABLE) {
        long target = ElxTable.own(heap, owner, key);
        if (target == 0) {
          throw new IllegalStateException("Pointer to a removed table entry");
        }
        return target;
      }
      if (ownerType == Type.LIST) {
        int index = Math.toIntExact(Slot.bits(memory, key));
        if (index < 0 || index >= ElxList.size(memory, owner)) {
          throw new IllegalStateException("Pointer to an invalid list index: " + index);
        }
        return Slot.address(ElxList.data(memory, owner), index);
      }
    }
    if (kind == OWNED_SLOT) {
      int index = Math.toIntExact(Slot.bits(memory, key));
      return Slot.address(owner, index);
    }
    throw new IllegalStateException("Unknown ELX pointer handle kind: " + kind);
  }

  /**
   * Writes one value through a resolved pointer handle.
   *
   * @param heap        owning ELX heap
   * @param pointer     pointer handle
   * @param value       source slot
   * @param frameTokens active frame identities
   * @param bases       active frame bases
   * @param depth       number of active frames
   * @throws IllegalStateException if the handle cannot be resolved
   */
  public static void write(ElxHeap heap, long pointer, long value, long[] frameTokens, long[] bases, int depth) {
    Slot.copy(heap.memory(), data(heap, pointer, frameTokens, bases, depth), value, 1);
  }

  /**
   * Tests whether a pointer handle refers to a heap-owned, non-local target.
   *
   * @param heap    owning ELX heap
   * @param pointer pointer handle
   * @return {@code true} when the handle can be pinned independently of a
   * register frame
   */
  public static boolean canPin(ElxHeap heap, long pointer) {
    if (!heap.isAlive(pointer) || heap.layout(pointer) != ElxHeap.Layout.REFERENCE) {
      return false;
    }
    return Slot.getInt(heap.memory(), pointer) != LOCAL;
  }

  /**
   * Returns the slot range containing arguments represented by a list/pointer value.
   *
   * @param heap        owning ELX heap
   * @param value       list or pointer value slot
   * @param frameTokens active frame identities
   * @param bases       active frame bases
   * @param depth       number of active frames
   * @return first argument slot
   * @throws IllegalStateException if a pointer target is no longer valid
   */
  public static long argumentData(ElxHeap heap, long value, long[] frameTokens, long[] bases, int depth) {
    Memory memory = heap.memory();
    return Slot.type(memory, value) == Type.LIST
        ? ElxList.data(memory, Slot.bits(memory, value))
        : data(heap, Slot.bits(memory, value), frameTokens, bases, depth);
  }

  /**
   * Returns the argument count represented by a list/pointer value.
   *
   * @param memory memory containing the value
   * @param value  list or pointer value slot
   * @return list length or dereference count
   * @throws IllegalArgumentException if the value cannot be dereferenced
   */
  public static int argumentCount(Memory memory, long value) {
    return Slot.type(memory, value) == Type.LIST
        ? ElxList.size(memory, Slot.bits(memory, value))
        : dereferenceCount(memory, value);
  }
}
