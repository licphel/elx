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

/**
 * Value ABI: type at 0, flags at 1, six reserved padding bytes, payload at 8.
 * The stride is 16 bytes; Arena-based slot regions keep payloads eight-byte aligned.
 * Memory itself does not insert alignment. Static operations allocate no value wrappers and hold
 * no mutable state, so they are safe wherever the supplied Memory may be used.
 */
public final class Slot {
  /** Size in bytes of one ABI slot. */
  public static final int SIZE = 16;
  /** Byte offset of the one-byte type tag. */
  public static final int TYPE_OFFSET = 0;
  /** Byte offset of the one-byte flags field. */
  public static final int FLAGS_OFFSET = 1;
  /** Byte offset of the eight-byte payload. */
  public static final int VALUE_OFFSET = 8;

  private Slot() {
  }

  /**
   * Returns the byte address of a slot at {@code index}.
   *
   * @param base base address of a contiguous slot region
   * @param index zero-based slot index
   * @return byte address of the indexed slot
   * @throws ArithmeticException if the address calculation overflows
   */
  public static long address(long base, int index) {
    return base + (long) index * SIZE;
  }

  /**
   * Reads and decodes the type tag stored in a slot.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return decoded type
   */
  public static Type type(Memory memory, long address) {
    return Type.fromTag(memory.load8(address + TYPE_OFFSET));
  }

  /**
   * Reads the slot's raw flags byte.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return raw flags
   */
  public static byte flags(Memory memory, long address) {
    return memory.load8(address + FLAGS_OFFSET);
  }

  /**
   * Replaces the slot's raw flags byte.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @param flags new raw flags
   */
  public static void flags(Memory memory, long address, byte flags) {
    memory.store8(address + FLAGS_OFFSET, flags);
  }

  /**
   * Reads the raw 64-bit payload.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return raw payload bits
   */
  public static long bits(Memory memory, long address) {
    return memory.load64(address + VALUE_OFFSET);
  }

  /**
   * Tests whether the slot's type is traced by the GC.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return {@code true} when the slot contains a heap reference
   */
  public static boolean isReference(Memory memory, long address) {
    return type(memory, address).reference();
  }

  /**
   * Writes a value with zero flags.
   *
   * @param memory memory containing the destination slot
   * @param address destination slot byte address
   * @param type value type
   * @param bits raw payload bits
   */
  public static void write(Memory memory, long address, Type type, long bits) {
    write(memory, address, type, (byte) 0, bits);
  }

  /**
   * Writes a type tag, flags, and raw payload as one slot value.
   *
   * @param memory memory containing the destination slot
   * @param address destination slot byte address
   * @param type value type
   * @param flags raw flags
   * @param bits raw payload bits
   */
  public static void write(Memory memory, long address, Type type, byte flags, long bits) {
    memory.store8(address + TYPE_OFFSET, type.tag());
    memory.store8(address + FLAGS_OFFSET, flags);
    memory.store64(address + VALUE_OFFSET, bits);
  }

  /**
   * Copies a contiguous slot range with overlap-safe byte semantics.
   *
   * @param memory memory containing both ranges
   * @param destination destination range base
   * @param source source range base
   * @param values number of slots to copy
   * @throws IllegalArgumentException if {@code values} is negative
   * @throws ArithmeticException if the byte count overflows
   */
  public static void copy(Memory memory, long destination, long source, int values) {
    memory.copyBytes(destination, source, Math.multiplyExact(values, SIZE));
  }

  /**
   * Reads the payload as an 8-bit integer.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return low eight payload bits
   */
  public static byte getByte(Memory memory, long address) {
    return (byte) bits(memory, address);
  }

  /**
   * Reads the payload as a 16-bit integer.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return low sixteen payload bits
   */
  public static short getShort(Memory memory, long address) {
    return (short) bits(memory, address);
  }

  /**
   * Reads the payload as a 32-bit integer.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return low 32 payload bits
   */
  public static int getInt(Memory memory, long address) {
    return (int) bits(memory, address);
  }

  /**
   * Reads the payload as a 64-bit integer.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return payload bits
   */
  public static long getLong(Memory memory, long address) {
    return bits(memory, address);
  }

  /**
   * Reads the payload as raw IEEE-754 float bits.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return decoded float
   */
  public static float getFloat(Memory memory, long address) {
    return Float.intBitsToFloat((int) bits(memory, address));
  }

  /**
   * Reads the payload as raw IEEE-754 double bits.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return decoded double
   */
  public static double getDouble(Memory memory, long address) {
    return Double.longBitsToDouble(bits(memory, address));
  }

  /**
   * Reads a boolean as a nonzero payload test.
   *
   * @param memory memory containing the slot
   * @param address slot byte address
   * @return {@code true} when the payload is nonzero
   */
  public static boolean getBoolean(Memory memory, long address) {
    return bits(memory, address) != 0;
  }
}
