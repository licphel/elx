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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Internal non-moving heap in shared {@link Memory}. Values, strings, lists,
 * tables, closures, and linked images use explicit layouts so tracing can follow
 * only reference slots. Closing frees owned regions but does not close the
 * borrowed memory or arena. Instances are thread-confined.
 */
public final class ElxHeap extends ManagedHeap<Long> {
  private static final int CONTAINER_HEADER_SLOTS = 3;
  private static final int MIN_CONTAINER_CAPACITY = 8;
  private final Memory memory;
  private final Arena arena;
  private long allocatedBytes;
  private Layout[] layouts = new Layout[INITIAL_CAPACITY];
  private int[] lengths = new int[INITIAL_CAPACITY];

  /**
   * Creates an ELX heap using the supplied arena.
   *
   * @param arena allocation arena shared with the VM
   */
  public ElxHeap(Arena arena) {
    this.arena = arena;
    this.memory = arena.memory();
  }

  /** @return bytes currently reserved by live ELX allocations */
  public long allocatedBytes() {
    return allocatedBytes;
  }

  /** @return backing allocation arena */
  public Arena arena() {
    return arena;
  }

  /**
   * Returns the storage layout associated with a live pointer.
   *
   * @param pointer live allocation pointer
   * @return allocation layout
   * @throws IllegalStateException if the pointer is dead or the heap is closed
   */
  public Layout layout(long pointer) {
    return layouts[entry(pointer)];
  }

  /** @return shared raw memory backend */
  public Memory memory() {
    return memory;
  }

  /**
   * Allocates a linked image with traceable slots followed by opaque bytes.
   *
   * @param slots number of traceable slots
   * @param rawBytes opaque suffix size
   * @return image base pointer
   * @throws ArithmeticException if the requested byte size overflows
   * @throws OutOfMemoryError if the arena cannot grow
   * @throws IllegalStateException if this heap is closed
   */
  public long allocateImage(int slots, int rawBytes) {
    int bytes = Math.addExact(Math.multiplyExact(slots, Slot.SIZE), rawBytes);
    allocatedBytes += bytes;
    long address = arena.allocate(bytes);
    int entry = register(address);
    layouts[entry] = Layout.IMAGE;
    lengths[entry] = slots;
    for (int i = 0; i < slots; i++) {
      Slot.write(memory, Slot.address(address, i), Type.NULL, 0);
    }
    return address;
  }

  /**
   * Allocates and initializes a value region described by {@code layout}.
   *
   * <p>For list and table layouts, {@code length} is the initial backing
   * capacity and the descriptor starts with zero logical elements. Reference
   * slots are initialized to {@link Type#NULL}; table key slots are initialized
   * to {@link Type#INVALID}. The operation is linear in the number of slots
   * that must be initialized.</p>
   *
   * @param layout storage layout
   * @param length requested logical length or backing capacity
   * @return allocation base pointer
   * @throws IllegalArgumentException if {@code length} is negative
   * @throws ArithmeticException if the requested byte size overflows
   * @throws OutOfMemoryError if the arena cannot grow
   * @throws IllegalStateException if this heap is closed
   */
  public long allocate(Layout layout, int length) {
    checkOpen();
    if (length < 0) {
      throw new IllegalArgumentException("Negative allocation length: " + length);
    }
    int bytes = switch (layout) {
      case BYTES -> length;
      case VALUES, CLOSURE, IMAGE, REFERENCE -> Math.multiplyExact(length, Slot.SIZE);
      case STRING -> Math.addExact(Integer.BYTES, length);
      case LIST -> CONTAINER_HEADER_SLOTS * Slot.SIZE;
      case TABLE -> 5 * Slot.SIZE;
    };
    allocatedBytes += bytes;
    long ptr = arena.allocate(bytes);
    int entry = register(ptr);
    layouts[entry] = layout;
    lengths[entry] = length;
    if (layout == Layout.VALUES || layout == Layout.CLOSURE || layout == Layout.IMAGE || layout == Layout.REFERENCE) {
      for (int i = 0; i < length; i++) {
        Slot.write(memory, Slot.address(ptr, i), Type.NULL, 0);
      }
    }
    if (layout == Layout.LIST || layout == Layout.TABLE) {
      int capacity = MIN_CONTAINER_CAPACITY;
      while (capacity < length) {
        capacity = Math.multiplyExact(capacity, 2);
      }
      long data = allocate(Layout.VALUES, Math.multiplyExact(capacity, layout == Layout.TABLE ? 2 : 1));
      if (layout == Layout.TABLE) {
        for (int i = 0; i < capacity; i++) {
          Slot.write(memory, Slot.address(data, i * 2), Type.INVALID, 0);
        }
      }
      Slot.write(memory, ptr, Type.INT, 0);
      Slot.write(memory, Slot.address(ptr, 1), Type.INT, capacity);
      Slot.write(memory, Slot.address(ptr, 2), Type.PTR, data);
      if (layout == Layout.TABLE) {
        Slot.write(memory, Slot.address(ptr, 3), Type.NULL, 0);
        Slot.write(memory, Slot.address(ptr, 4), Type.NULL, 0);
      }
    }
    if (layout == Layout.STRING) {
      memory.store32(ptr, length);
    }
    return ptr;
  }

  /**
   * Imports Java text as UTF-8 bytes in an ELX string allocation.
   *
   * @param text text to encode
   * @return string pointer
   * @throws NullPointerException if {@code text} is null
   * @throws OutOfMemoryError if the arena cannot grow
   * @throws IllegalStateException if this heap is closed
   */
  public long importString(String text) {
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    long ptr = allocate(Layout.STRING, utf8.length);
    for (int i = 0; i < utf8.length; i++) {
      memory.store8(ptr + Integer.BYTES + i, utf8[i]);
    }
    return ptr;
  }

  /**
   * Exports an ELX string allocation as Java text.
   *
   * <p>The pointer may be the base of a {@link Layout#STRING} allocation or
   * an image whose first four bytes contain a UTF-8 byte length. Interior
   * pointers and other layouts are rejected.</p>
   *
   * @param ptr string or compatible image base pointer
   * @return decoded Java text
   * @throws IllegalArgumentException if the pointer does not identify a string
   *                                  layout
   * @throws IllegalStateException if the pointer is dead or the heap is closed
   */
  public String exportString(long ptr) {
    int at = entry(ptr);
    if ((layouts[at] != Layout.STRING || ptr != pointer(at)) && layouts[at] != Layout.IMAGE) {
      throw new IllegalArgumentException("Not a string base: " + ptr);
    }
    byte[] bytes = new byte[memory.load32(ptr)];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = memory.load8(ptr + Integer.BYTES + i);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Override
  protected long base(long ptr) {
    return arena.owner(ptr);
  }

  @Override
  protected void grow(int capacity) {
    layouts = Arrays.copyOf(layouts, capacity);
    lengths = Arrays.copyOf(lengths, capacity);
  }

  @Override
  protected void dispose(int index, long ptr) {
    arena.free(ptr);
    layouts[index] = Layout.BYTES;
    lengths[index] = 0;
  }

  /**
   * Visits reference slots in an allocation without recursively traversing
   * their targets.
   *
   * @param ptr base or interior pointer to a traceable allocation
   * @param visitor callback for each reference slot
   * @throws IllegalStateException if the pointer is dead or the heap is closed
   */
  @Override
  public void trace(long ptr, HeapVisitor visitor) {
    int at = entry(ptr);
    if (layouts[at] != Layout.REFERENCE && layouts[at] != Layout.VALUES && layouts[at] != Layout.CLOSURE && layouts[at] != Layout.IMAGE && layouts[at] != Layout.LIST && layouts[at] != Layout.TABLE) {
      return;
    }
    long base = pointer(at);
    int count = layouts[at] == Layout.REFERENCE || layouts[at] == Layout.VALUES || layouts[at] == Layout.CLOSURE || layouts[at] == Layout.IMAGE
        ? lengths[at] : layouts[at] == Layout.TABLE ? 5 : CONTAINER_HEADER_SLOTS;
    for (int i = 0; i < count; i++) {
      long cell = Slot.address(base, i);
      if (Slot.isReference(memory, cell)) {
        visitor.visit(Slot.type(memory, cell), Slot.bits(memory, cell));
      }
    }
  }

  /**
   * Exposes an ELX pointer as its Java representation.
   *
   * @param ptr live base or interior pointer
   * @return the same pointer value
   * @throws IllegalStateException if the pointer is dead or the heap is closed
   */
  @Override
  public Long exportJava(long ptr) {
    entry(ptr);
    return ptr;
  }

  /** Storage layout used by an internal heap allocation. */
  public enum Layout {
    // LIST/TABLE length requests backing capacity; the container initially has zero elements.
    // Their descriptors/backing stores are VALUES regions so GC follows nested values.

    /** Opaque bytes with no traced slots. */
    BYTES,
    /** Consecutive traceable value slots. */
    VALUES,
    /** UTF-8 bytes prefixed by a byte length. */
    STRING,
    /** List descriptor and backing value region. */
    LIST,
    /** Table descriptor and key/value backing region. */
    TABLE,
    /** Closure capture slots. */
    CLOSURE,
    /** Linked image with a traceable export prefix. */
    IMAGE,
    /** Region containing one or more references. */
    REFERENCE
  }
}
