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

package io.viki.elx.asm;

import io.viki.elx.Type;

/**
 * Pure 32-bit instruction-word encoding and operand extraction.
 *
 * <p>Instructions are represented as primitive words so fetch, relocation, and disassembly do not
 * allocate per-instruction objects. The static methods validate encodable operands and are safe to
 * call concurrently.
 */
public final class Instr {
  /** Number of bytes in one encoded instruction word. */
  public static final int WORD_BYTES = 4;
  /** Number of register values addressable by an eight-bit register field. */
  public static final int MAX_REGISTERS = 256;
  /** Number of entries addressable by a sixteen-bit constant index. */
  public static final int MAX_CONSTANTS = 65536;
  /** Branch on the false result without inverting an ordering relation (important for NaN and hooks). */
  public static final int BRANCH_FALSE = 1 << 16;
  private static final Op[] OPS = Op.values();
  private static final Relation[] RELATIONS = Relation.values();

  static {
    if (OPS.length > 256) {
      throw new ExceptionInInitializerError("Opcode byte exhausted");
    }
  }

  private Instr() {
  }

  /**
   * Encodes an opcode and three eight-bit register fields.
   *
   * @param op opcode to encode
   * @param a A register field
   * @param b B register field
   * @param c C register field
   * @return encoded instruction word
   * @throws IllegalArgumentException if a register field is outside the eight-bit range
   */
  public static int header(Op op, int a, int b, int c) {
    u8(a);
    u8(b);
    u8(c);
    return op.ordinal() | a << 8 | b << 16 | c << 24;
  }

  /**
   * Encodes an opcode, destination register, and sixteen-bit constant index.
   *
   * @param op opcode to encode
   * @param a destination register
   * @param index constant-pool index
   * @return encoded instruction word
   * @throws IllegalArgumentException if the register or index is outside its field width
   */
  public static int upper(Op op, int a, int index) {
    u8(a);
    if (index < 0 || index >= MAX_CONSTANTS) {
      throw new IllegalArgumentException("Constant index outside u16: " + index);
    }
    return op.ordinal() | a << 8 | index << 16;
  }

  /**
   * Encodes a conditional branch with a signed byte displacement relative to this instruction.
   *
   * @param op branch opcode
   * @param condition condition register
   * @param displacement byte displacement from the instruction's first word
   * @return encoded branch word
   * @throws IllegalArgumentException if the register or displacement is invalid
   */
  public static int branch(Op op, int condition, int displacement) {
    u8(condition);
    displacement(displacement, 16);
    return op.ordinal() | condition << 8 | (displacement & 65535) << 16;
  }

  /**
   * Encodes an unconditional jump with a signed twenty-four-bit byte displacement.
   *
   * @param displacement byte displacement from the instruction's first word
   * @return encoded jump word
   * @throws IllegalArgumentException if the displacement is unaligned or out of range
   */
  public static int jump(int displacement) {
    displacement(displacement, 24);
    return Op.JUMP.ordinal() | (displacement & 0xFFFFFF) << 8;
  }

  /**
   * Extracts the opcode from an encoded word.
   *
   * @param word encoded instruction word
   * @return opcode metadata
   */
  public static Op op(int word) {
    return OPS[word & 255];
  }

  /**
   * Extracts the A register field.
   *
   * @param word encoded instruction word
   * @return A field as an unsigned byte
   */
  public static int a(int word) {
    return word >>> 8 & 255;
  }

  /**
   * Extracts the B register field.
   *
   * @param word encoded instruction word
   * @return B field as an unsigned byte
   */
  public static int b(int word) {
    return word >>> 16 & 255;
  }

  /**
   * Extracts the C register field.
   *
   * @param word encoded instruction word
   * @return C field as an unsigned byte
   */
  public static int c(int word) {
    return word >>> 24;
  }

  /**
   * Extracts a sixteen-bit upper-format index.
   *
   * @param word encoded instruction word
   * @return unsigned constant or table index
   */
  public static int index(int word) {
    return word >>> 16;
  }

  /**
   * Extracts a signed sixteen-bit branch displacement.
   *
   * @param word encoded branch word
   * @return signed byte displacement
   */
  public static int branchOffset(int word) {
    return word >> 16;
  }

  /**
   * Extracts a signed twenty-four-bit jump displacement.
   *
   * @param word encoded jump word
   * @return signed byte displacement
   */
  public static int jumpOffset(int word) {
    return word >> 8;
  }

  /**
   * Encodes a numeric type and comparison relation in a control word.
   *
   * @param type numeric type
   * @param relation comparison relation
   * @return packed control word
   */
  public static int control(Type type, Relation relation) {
    return Byte.toUnsignedInt(type.tag()) | relation.ordinal() << 8;
  }

  /**
   * Encodes a numeric type with the equality relation.
   *
   * @param type numeric type
   * @return packed control word
   */
  public static int control(Type type) {
    return control(type, Relation.EQ);
  }

  /**
   * Encodes a dynamic operator and ordering relation.
   *
   * @param op dynamic operator
   * @param relation ordering relation
   * @return packed operator control word
   */
  public static int operator(Op op, Relation relation) {
    return op.ordinal() | relation.ordinal() << 8;
  }

  /**
   * Extracts the numeric type from a control word.
   *
   * @param control packed control word
   * @return encoded numeric type
   */
  public static Type type(int control) {
    return Type.fromTag((byte) control);
  }

  /**
   * Extracts a comparison relation from a control word.
   *
   * @param control packed control word
   * @return encoded comparison relation
   */
  public static Relation relation(int control) {
    return RELATIONS[control >>> 8 & 255];
  }

  /**
   * Packs a call's argument count and result capacity.
   *
   * @param arguments number of input arguments
   * @param results maximum number of returned values, from zero through four
   * @return packed call control word
   * @throws IllegalArgumentException if either count exceeds its field range
   */
  public static int callWindow(int arguments, int results) {
    u8(arguments);
    if (results < 0 || results > 4) {
      throw new IllegalArgumentException("Result capacity outside 0..4: " + results);
    }
    return arguments | results << 8;
  }

  /**
   * Packs an external call's argument-base register with its call window.
   *
   * @param base first argument register
   * @param arguments number of input arguments
   * @param results maximum number of returned values
   * @return packed external-call control word
   * @throws IllegalArgumentException if any field is outside its valid range
   */
  public static int externalWindow(int base, int arguments, int results) {
    u8(base);
    return callWindow(arguments, results) | base << 16;
  }

  /**
   * Extracts the argument count from a call control word.
   *
   * @param control packed call control word
   * @return argument count
   */
  public static int argumentCount(int control) {
    return control & 255;
  }

  /**
   * Extracts the result capacity from a call control word.
   *
   * @param control packed call control word
   * @return result capacity
   */
  public static int resultCount(int control) {
    return control >>> 8 & 255;
  }

  /**
   * Extracts the argument-base register from an external-call control word.
   *
   * @param control packed external-call control word
   * @return argument-base register
   */
  public static int argumentBase(int control) {
    return control >>> 16 & 255;
  }

  static void u8(int value) {
    if ((value & ~255) != 0) {
      throw new IllegalArgumentException("Operand outside u8: " + value);
    }
  }

  static void displacement(int value, int bits) {
    if ((value & 3) != 0 || (value << (32 - bits) >> (32 - bits)) != value) {
      throw new IllegalArgumentException("Unaligned/out-of-range " + bits + "-bit byte displacement: " + value);
    }
  }

  /** Comparison relations encoded in instruction control words. */
  public enum Relation {
    /** Equal values. */
    EQ,
    /** Unequal values. */
    NE,
    /** Left value is less than the right value. */
    LT,
    /** Left value is less than or equal to the right value. */
    LE,
    /** Left value is greater than the right value. */
    GT,
    /** Left value is greater than or equal to the right value. */
    GE
  }
}
