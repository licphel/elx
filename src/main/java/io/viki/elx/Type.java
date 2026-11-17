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

package io.viki.elx;

/**
 * Runtime value categories and the numeric conversion rules shared by the compiler and VM.
 *
 * <p>Each numeric type records its storage width and signedness. {@link #binary(Type, Type)}
 * describes the result type selected for arithmetic operands, while {@link #cast(long, Type,
 * Type)} applies an explicit conversion and {@link #normalize(long, Type)} canonicalizes an
 * integer payload. Enum ordinals are transient in-memory tags and are not stable serialization
 * identifiers.
 */
public enum Type {
  /** Invalid or unavailable type used to mark an unsupported operation. */
  INVALID("invalid", 0, Family.NONE),
  /** Missing/default value, distinct from an explicit null. */
  VOID("void", 0, Family.NONE),
  /** Null reference value with no payload width. */
  NULL("null", 0, Family.NONE),
  /** Boolean value stored as a one-bit unsigned integer. */
  BOOL("bool", 1, Family.UNSIGNED),
  /** Signed eight-bit integer. */
  BYTE("byte", 8, Family.SIGNED),
  /** Unsigned eight-bit integer. */
  UBYTE("ubyte", 8, Family.UNSIGNED),
  /** Signed sixteen-bit integer. */
  SHORT("short", 16, Family.SIGNED),
  /** Unsigned sixteen-bit integer. */
  USHORT("ushort", 16, Family.UNSIGNED),
  /** Signed thirty-two-bit integer. */
  INT("int", 32, Family.SIGNED),
  /** Unsigned thirty-two-bit integer. */
  UINT("uint", 32, Family.UNSIGNED),
  /** Signed sixty-four-bit integer. */
  LONG("long", 64, Family.SIGNED),
  /** Unsigned sixty-four-bit integer represented by uninterpreted bits. */
  ULONG("ulong", 64, Family.UNSIGNED),
  /** Thirty-two-bit IEEE-754 floating-point value. */
  FLOAT("float", 32, Family.FLOATING),
  /** Sixty-four-bit IEEE-754 floating-point value. */
  DOUBLE("double", 64, Family.FLOATING),
  /** Managed pointer value. */
  PTR("ptr", 64, Family.REFERENCE),
  /** Managed UTF-8 string value. */
  STRING("string", 64, Family.REFERENCE),
  /** Managed list value. */
  LIST("list", 64, Family.REFERENCE),
  /** Managed table value. */
  TABLE("table", 64, Family.REFERENCE),
  /** Managed function value. */
  FUNCTION("function", 64, Family.REFERENCE),
  /** Host-owned object represented through an external heap. */
  EXTERNAL("external", 64, Family.REFERENCE);

  private static final Type[] ALL = values();
  private static final Type[][] BINARY = new Type[ALL.length][ALL.length];

  static {
    for (Type left : ALL) {
      for (Type right : ALL) {
        if (!left.numeric || !right.numeric) {
          BINARY[left.ordinal()][right.ordinal()] = INVALID;
          continue;
        }
        int w = Math.max(left.width, right.width);
        Type result;
        if (!left.integer || !right.integer) {
          result = w <= 32 ? FLOAT : DOUBLE;
        } else {
          boolean u = left.width == w && left.unsigned || right.width == w && right.unsigned;
          result = w == 1 ? BOOL : w == 8 ? (u ? UBYTE : BYTE) : w == 16 ? (u ? USHORT : SHORT) : w == 32 ? (u ? UINT : INT) : u ? ULONG : LONG;
        }
        BINARY[left.ordinal()][right.ordinal()] = result;
      }
    }
  }

  private final String displayName;
  private final int width;
  private final boolean numeric, integer, unsigned, reference;

  Type(String name, int width, Family family) {
    this.displayName = name;
    this.width = width;
    this.integer = family == Family.SIGNED || family == Family.UNSIGNED;
    this.numeric = integer || family == Family.FLOATING;
    this.unsigned = family == Family.UNSIGNED;
    this.reference = family == Family.REFERENCE;
  }

  /**
   * Decodes a transient type tag produced by {@link #tag()}.
   *
   * @param tag ordinal tag stored in a VM slot
   * @return type represented by the unsigned tag
   * @throws ArrayIndexOutOfBoundsException if the tag does not identify a type
   */
  public static Type fromTag(byte tag) {
    return ALL[Byte.toUnsignedInt(tag)];
  }

  /**
   * Selects the common numeric type for a binary operation.
   *
   * @param left  left operand type
   * @param right right operand type
   * @return widened result type
   * @throws IllegalArgumentException if either operand is non-numeric
   */
  public static Type binary(Type left, Type right) {
    Type result = BINARY[left.ordinal()][right.ordinal()];
    if (result == INVALID) {
      throw new IllegalArgumentException("No numeric widening for " + left + " and " + right);
    }
    return result;
  }

  /**
   * Canonicalizes an integer payload to the width and signedness of a type.
   *
   * <p>Unsigned values are zero-extended and signed values are sign-extended. Narrow integer
   * results wrap to the target width, including one-bit {@link #BOOL} arithmetic; {@link #ULONG}
   * retains its uninterpreted sixty-four bits.
   *
   * @param bits raw integer payload
   * @param type integer type that owns the payload
   * @return canonical payload bits
   * @throws IllegalArgumentException if {@code type} is not an integer type
   */
  public static long normalize(long bits, Type type) {
    if (!type.integer()) {
      throw new IllegalArgumentException("Not an integer kind: " + type.displayName());
    }
    int width = type.width();
    if (width == 64) {
      return bits;
    }
    long mask = (1L << width) - 1;
    return type.unsigned() ? bits & mask : (bits << (64 - width)) >> (64 - width);
  }

  /**
   * Converts a numeric payload to a Java double without boxing.
   *
   * @param bits raw payload bits
   * @param type numeric type that owns the payload
   * @return numeric value represented by the payload
   * @throws IllegalArgumentException if {@code type} is not numeric
   */
  public static double asDouble(long bits, Type type) {
    if (!type.numeric()) {
      throw new IllegalArgumentException("Not numeric: " + type.displayName());
    }
    if (type == DOUBLE) {
      return Double.longBitsToDouble(bits);
    }
    if (type == FLOAT) {
      return Float.intBitsToFloat((int) bits);
    }
    long value = normalize(bits, type);
    return type == ULONG && value < 0 ? (double) ((value >>> 1) | (value & 1)) * 2.0 : (double) value;
  }

  /**
   * Applies an explicit numeric conversion, including narrowing and boolean conversion.
   *
   * <p>Integer narrowing keeps the target's low bits. Finite floating values truncate toward zero
   * and wrap modulo the target width; nonfinite floating values cannot become integers. Boolean
   * conversion yields false only for numeric zero, including both signed zero representations.
   *
   * @param bits raw payload bits
   * @param from source type owning {@code bits}
   * @param to   target numeric type
   * @return payload bits encoded for {@code to}
   * @throws IllegalArgumentException if the conversion is not numeric
   * @throws ArithmeticException      if a nonfinite floating value is converted to an integer
   */
  public static long cast(long bits, Type from, Type to) {
    if (from == VOID && to.numeric()) {
      return 0;
    }
    if (!from.numeric() || !to.numeric()) {
      throw new IllegalArgumentException("No numeric cast from " + from.displayName() + " to " + to.displayName());
    }
    if (from == to) {
      return to.integer() ? normalize(bits, to) : to == FLOAT ? bits & 0xFFFFFFFFL : bits;
    }
    if (to == BOOL) {
      return from.integer() ? (normalize(bits, from) == 0 ? 0 : 1) : (asDouble(bits, from) == 0 ? 0 : 1);
    }
    if (to == DOUBLE) {
      return Double.doubleToRawLongBits(asDouble(bits, from));
    }
    if (to == FLOAT) {
      float value;
      if (from.integer()) {
        long integer = normalize(bits, from);
        // Direct conversion avoids long -> double -> float double rounding.
        value = from == ULONG && integer < 0 ? (float) ((integer >>> 1) | (integer & 1)) * 2.0f : (float) integer;
      } else {
        value = (float) asDouble(bits, from);
      }
      return Integer.toUnsignedLong(Float.floatToRawIntBits(value));
    }
    if (from.integer()) {
      return normalize(normalize(bits, from), to);
    }
    double value = asDouble(bits, from);
    if (!Double.isFinite(value)) {
      throw new ArithmeticException("Cannot cast nonfinite float to " + to.displayName());
    }
    // Modulo before Java's cast avoids its saturation at Long.MIN/MAX_VALUE.
    double remainder = value % 0x1p64;
    long raw = remainder >= 0x1p63 ? ((long) (remainder - 0x1p63) | Long.MIN_VALUE)
        : remainder < -0x1p63 ? (long) (remainder + 0x1p64) : (long) remainder;
    return normalize(raw, to);
  }

  /**
   * Checks whether a type indicates the object is a managed reference (that's to say, opaque to the script).
   *
   * @param type the type to check
   * @return {@code true} if it is managed
   */
  public static boolean isManagedReference(Type type) {
    return type == STRING || type == LIST || type == TABLE
        || type == FUNCTION || type == PTR;
  }

  /**
   * Returns the storage width used by this type.
   *
   * @return width in bits
   */
  public int width() {
    return width;
  }

  /**
   * Returns whether this type participates in numeric operations.
   *
   * @return {@code true} for integer and floating-point types
   */
  public boolean numeric() {
    return numeric;
  }

  /**
   * Returns whether this type is an integer category.
   *
   * @return {@code true} for signed, unsigned, and boolean integer types
   */
  public boolean integer() {
    return integer;
  }

  /**
   * Returns whether integer payloads use unsigned interpretation.
   *
   * @return {@code true} for unsigned integer categories
   */
  public boolean unsigned() {
    return unsigned;
  }

  /**
   * Returns whether this type identifies a managed or external reference.
   *
   * @return {@code true} for pointer, container, function, and external values
   */
  public boolean reference() {
    return reference;
  }

  /**
   * Returns the language spelling of this type.
   *
   * @return display name used in diagnostics and source-level type declarations
   */
  public String displayName() {
    return displayName;
  }

  /**
   * Returns the transient VM tag for this type.
   *
   * @return ordinal encoded as a byte
   */
  public byte tag() {
    return (byte) ordinal();
  }

  private enum Family {
    NONE,
    SIGNED,
    UNSIGNED,
    FLOATING,
    REFERENCE
  }
}
