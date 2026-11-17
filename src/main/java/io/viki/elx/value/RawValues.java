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
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.lib.ValueText;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;

import java.util.Arrays;

/**
 * Scalar operators, value dispatch, structural equality, and hashing. Operations
 * read/write the supplied slot storage directly, never invoke script hooks, and
 * keep cycle-detection state in a thread-local primitive stack. Callers must
 * obey the thread confinement of the supplied heap and memory.
 */
public final class RawValues {
  private static final long HASH_SEED = 0xcbf29ce484222325L, HASH_PRIME = 0x100000001b3L;
  // Pure structural comparison never invokes script hooks. Only primitive addresses survive between calls.
  private static final ThreadLocal<long[]> COMPARISONS = ThreadLocal.withInitial(() -> new long[33]);

  private RawValues() {
  }

  private static long mix(long value) {
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }

  private static boolean numericEqual(Type left, long x, Type right, long y) {
    return numericCompare(left, x, right, y) == 0;
  }

  // 2 denotes unordered, and is handled before applying a relation.
  private static int numericCompare(Type left, long x, Type right, long y) {
    if (left.integer() && right.integer()) {
      x = Type.normalize(x, left);
      y = Type.normalize(y, right);
      boolean ux = left == Type.ULONG && x < 0, uy = right == Type.ULONG && y < 0;
      if (ux != uy) {
        return ux ? 1 : -1;
      }
      return ux ? Long.compareUnsigned(x, y) : Long.compare(x, y);
    }
    if (!left.integer() && !right.integer()) {
      double a = Type.asDouble(x, left), b = Type.asDouble(y, right);
      if (Double.isNaN(a) || Double.isNaN(b)) {
        return 2;
      }
      return a == b ? 0 : a < b ? -1 : 1;
    }
    boolean integerLeft = left.integer();
    Type integer = integerLeft ? left : right;
    long value = Type.normalize(integerLeft ? x : y, integer);
    double number = integerLeft ? Type.asDouble(y, right) : Type.asDouble(x, left);
    if (Double.isNaN(number)) {
      return 2;
    }
    int result;
    if (integer == Type.ULONG) {
      if (number < 0) {
        result = 1;
      } else if (number >= 0x1p64) {
        result = -1;
      } else {
        long truncated = number >= 0x1p63 ? ((long) (number - 0x1p63)) | Long.MIN_VALUE : (long) number;
        result = Long.compareUnsigned(value, truncated);
        if (result == 0) {
          double a = Type.asDouble(value, integer);
          result = a == number ? 0 : a < number ? -1 : 1;
        }
      }
    } else if (number >= 0x1p63) {
      result = -1;
    } else if (number < -0x1p63) {
      result = 1;
    } else {
      result = Long.compare(value, (long) number);
      if (result == 0) {
        double a = (double) value;
        result = a == number ? 0 : a < number ? -1 : 1;
      }
    }
    return integerLeft ? result : -result;
  }

  private static boolean relation(int comparison, Instr.Relation relation) {
    return switch (relation) {
      case EQ -> comparison == 0;
      case NE -> comparison != 0;
      case LT -> comparison < 0;
      case LE -> comparison <= 0;
      case GT -> comparison > 0;
      case GE -> comparison >= 0;
    };
  }

  /**
   * Returns a deterministic hash for the value stored in one slot.
   *
   * <p>Numeric values use a common representation so equal integer and floating
   * values hash alike; lists and tables use identity-style hashes because their
   * contents are mutable.</p>
   *
   * @param memory memory containing the value slot
   * @param value  source slot address
   * @return deterministic hash used by table probing
   */
  public static long hash(Memory memory, long value) {
    return RawValues.hash(memory, Slot.type(memory, value), Slot.bits(memory, value));
  }

  private static long hash(Memory memory, Type type, long bits) {
    if (type == Type.NULL) {
      return RawValues.mix(Type.NULL.ordinal());
    }
    if (type.numeric()) {
      double number = Type.asDouble(bits, type);
      return RawValues.mix(number == 0 ? 0 : Double.doubleToLongBits(number));
    }
    if (type == Type.STRING) {
      int length = memory.load32(bits);
      long hash = HASH_SEED;
      for (int i = 0; i < length; i++) {
        hash = (hash ^ Byte.toUnsignedInt(memory.load8(bits + Integer.BYTES + i))) * HASH_PRIME;
      }
      return hash;
    }
    if (type == Type.LIST || type == Type.TABLE) {
      return RawValues.mix(type.ordinal());
    }
    return RawValues.mix(bits ^ ((long) type.ordinal() << 56));
  }

  /**
   * Adds an integer delta to a numeric value and writes the result slot.
   *
   * @param memory memory containing source and destination slots
   * @param result destination slot
   * @param source source numeric slot
   * @param delta  signed increment or decrement
   * @throws IllegalArgumentException if the source is not numeric
   */
  public static void increment(Memory memory, long result, long source, int delta) {
    Type type = Slot.type(memory, source);
    long bits = Slot.bits(memory, source);
    if (type.integer()) {
      Slot.write(memory, result, type, Type.normalize(bits + delta, type));
    } else if (type == Type.FLOAT) {
      Slot.write(memory, result, type, Integer.toUnsignedLong(Float.floatToRawIntBits(Float.intBitsToFloat((int) bits) + delta)));
    } else if (type == Type.DOUBLE) {
      Slot.write(memory, result, type, Double.doubleToRawLongBits(Double.longBitsToDouble(bits) + delta));
    } else {
      throw new IllegalArgumentException("Increment/decrement requires a number, got " + type);
    }
  }

  /**
   * Converts an integer slot into an addressable Java index.
   *
   * @param memory memory containing the key slot
   * @param key    integer key slot
   * @return nonnegative Java index
   * @throws IllegalArgumentException  if the key is not integral
   * @throws IndexOutOfBoundsException if the value cannot fit a Java index
   */
  public static int index(Memory memory, long key) {
    Type type = Slot.type(memory, key);
    if (!type.integer()) {
      throw new IllegalArgumentException("Index requires an integer, got " + type);
    }
    long value = Slot.bits(memory, key);
    if (type == Type.ULONG && value < 0) {
      throw new IndexOutOfBoundsException("Unsigned index exceeds addressable memory");
    }
    return Math.toIntExact(Type.normalize(value, type));
  }

  /**
   * Writes a table entry or list element selected by an index key.
   *
   * @param heap   owning ELX heap
   * @param target list or table value slot
   * @param key    index/key slot
   * @param value  source value slot
   * @throws IllegalArgumentException  if the target is not indexable
   * @throws IndexOutOfBoundsException if a list index is invalid
   * @throws IllegalStateException     if the heap or target is dead
   */
  public static void setIndex(ElxHeap heap, long target, long key, long value) {
    Memory memory = heap.memory();
    Type type = Slot.type(memory, target);
    long pointer = Slot.bits(memory, target);
    if (type == Type.TABLE) {
      ElxTable.set(heap, pointer, key, value);
    } else if (type == Type.LIST) {
      ElxList.set(heap, pointer, RawValues.index(memory, key), value);
    } else {
      throw new IllegalArgumentException("Not assignable by index: " + type);
    }
  }

  /**
   * Tests an exact numeric relation, including mixed signed/unsigned widths and unordered NaN.
   *
   * @param relation relation to apply
   * @param left     left numeric type
   * @param x        raw left payload
   * @param right    right numeric type
   * @param y        raw right payload
   * @return whether the relation holds; unordered NaN satisfies only {@link Instr.Relation#NE}
   */
  public static boolean numericTest(Instr.Relation relation, Type left, long x, Type right, long y) {
    int order = numericCompare(left, x, right, y);
    return order == 2 ? relation == Instr.Relation.NE : relation(order, relation);
  }

  /**
   * Applies the same-type numeric fast path used by both VM and host operations.
   *
   * @param op     arithmetic or unary operation
   * @param memory memory containing all slots
   * @param result destination slot
   * @param left   left or unary operand slot
   * @param right  right operand slot
   * @return {@code true} when the operation was handled by this fast path; callers
   * must use general dispatch when it returns {@code false}
   */
  public static boolean numericOperator(Op op, Memory memory, long result, long left, long right) {
    Type kind = Slot.type(memory, left);
    if (!kind.numeric()) {
      return false;
    }
    boolean unary = op == Op.POS || op == Op.NEG || op == Op.BNOT || op == Op.NOT || op == Op.INC || op == Op.DEC;
    if (!unary && kind != Slot.type(memory, right)) {
      return false;
    }
    long x = Slot.bits(memory, left), y = unary ? 0 : Slot.bits(memory, right);
    if (op == Op.POS) {
      Slot.copy(memory, result, left, 1);
      return true;
    }
    if (op == Op.NOT) {
      if (kind != Type.BOOL) {
        return false;
      }
      Slot.write(memory, result, kind, x == 0 ? 1 : 0);
      return true;
    }
    if (kind.integer()) {
      x = Type.normalize(x, kind);
      y = Type.normalize(y, kind);
      long value;
      switch (op) {
        case ADD -> value = x + y;
        case SUB -> value = x - y;
        case MUL -> value = x * y;
        case DIV -> {
          // Allocate the language error explicitly: HotSpot fast-throw exceptions
          // may disable suppressed traces after this instruction becomes hot.
          if (y == 0) {
            throw new ArithmeticException("/ by zero");
          }
          value = kind.unsigned() ? Long.divideUnsigned(x, y) : x / y;
        }
        case MOD -> {
          if (y == 0) {
            throw new ArithmeticException("/ by zero");
          }
          value = kind.unsigned() ? Long.remainderUnsigned(x, y) : x % y;
        }
        case BAND -> value = x & y;
        case BOR -> value = x | y;
        case BXOR -> value = x ^ y;
        case SHL -> value = x << ((int) y & (kind.width() - 1));
        case SHR -> value = x >> ((int) y & (kind.width() - 1));
        case USHR ->
            value = (kind.width() == 64 ? x : x & ((1L << kind.width()) - 1)) >>> ((int) y & (kind.width() - 1));
        case NEG -> value = -x;
        case BNOT -> value = ~x;
        case INC -> value = x + 1;
        case DEC -> value = x - 1;
        default -> {
          return false;
        }
      }
      Slot.write(memory, result, kind, Type.normalize(value, kind));
      return true;
    }
    // Unary negation flips only the sign bit, preserving NaN payloads and signed zero.
    if (op == Op.NEG) {
      Slot.write(memory, result, kind, x ^ (kind == Type.FLOAT ? 0x80000000L : Long.MIN_VALUE));
      return true;
    }
    if (kind == Type.FLOAT) {
      float a = Float.intBitsToFloat((int) x), b = Float.intBitsToFloat((int) y), value;
      switch (op) {
        case ADD -> value = a + b;
        case SUB -> value = a - b;
        case MUL -> value = a * b;
        case DIV -> value = a / b;
        case MOD -> value = a % b;
        case INC -> value = a + 1;
        case DEC -> value = a - 1;
        default -> {
          return false;
        }
      }
      Slot.write(memory, result, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(value)));
    } else {
      double a = Double.longBitsToDouble(x), b = Double.longBitsToDouble(y), value;
      switch (op) {
        case ADD -> value = a + b;
        case SUB -> value = a - b;
        case MUL -> value = a * b;
        case DIV -> value = a / b;
        case MOD -> value = a % b;
        case INC -> value = a + 1;
        case DEC -> value = a - 1;
        default -> {
          return false;
        }
      }
      Slot.write(memory, result, kind, Double.doubleToRawLongBits(value));
    }
    return true;
  }

  /**
   * Executes one encoded scalar, string, or container operator over slot values.
   *
   * <p>Operands are read before writes where aliasing could otherwise change a
   * subsequent operand. Container operations delegate to {@link ElxList} and
   * {@link ElxTable}; this method does not invoke script metamethods.</p>
   *
   * @param heap    owning ELX heap
   * @param control encoded operator/control word
   * @param result  destination slot
   * @param left    left or unary operand slot
   * @param right   right operand slot
   * @throws IllegalArgumentException if operand types do not support the operator
   * @throws ArithmeticException      if result allocation sizes overflow
   * @throws IllegalStateException    if the heap or referenced container is dead
   */
  public static void operator(ElxHeap heap, int control, long result, long left, long right) {
    Memory memory = heap.memory();
    Op op = Instr.op(control);
    Type a = Slot.type(memory, left);
    long x = Slot.bits(memory, left);
    if (op == Op.POS) {
      if (!a.numeric()) {
        throw new IllegalArgumentException("Unary + requires a number, got " + a);
      }
      Slot.copy(memory, result, left, 1);
      return;
    }
    if (op == Op.COMPARE && (Instr.relation(control >>> 8) == Instr.Relation.EQ || Instr.relation(control >>> 8) == Instr.Relation.NE)) {
      boolean same = RawValues.equal(memory, left, right);
      Slot.write(memory, result, Type.BOOL, same == (Instr.relation(control >>> 8) == Instr.Relation.EQ) ? 1 : 0);
      return;
    }

    if (op == Op.NOT) {
      if (a != Type.BOOL) {
        throw new IllegalArgumentException("NOT requires BOOL, got " + a);
      }
      Slot.write(memory, result, Type.BOOL, x == 0 ? 1 : 0);
      return;
    }
    if (op == Op.NEG) {
      if (a.integer()) {
        Slot.write(memory, result, a, Type.normalize(-x, a));
      } else if (a == Type.FLOAT) {
        Slot.write(memory, result, a, x ^ 0x80000000L);
      } else if (a == Type.DOUBLE) {
        Slot.write(memory, result, a, x ^ Long.MIN_VALUE);
      } else {
        throw new IllegalArgumentException("Negation requires numeric value, got " + a);
      }
      return;
    }
    if (op == Op.BNOT) {
      if (!a.integer()) {
        throw new IllegalArgumentException("Bitwise complement requires integer, got " + a);
      }
      Slot.write(memory, result, a, Type.normalize(~x, a));
      return;
    }

    Type b = Slot.type(memory, right);
    long y = Slot.bits(memory, right);
    if (op == Op.ADD && (a == Type.STRING || b == Type.STRING) && a != b) {
      if (a != Type.STRING && !a.numeric() && a != Type.NULL || b != Type.STRING && !b.numeric() && b != Type.NULL) {
        throw new IllegalArgumentException("Cannot concatenate " + a + " and " + b);
      }
      String text = ValueText.string(memory, left) + ValueText.string(memory, right);
      Slot.write(memory, result, Type.STRING, heap.importString(text));
      return;
    }
    if (a == Type.STRING && b == Type.STRING && op == Op.ADD) {
      int leftLength = memory.load32(x), rightLength = memory.load32(y);
      long text = heap.allocate(ElxHeap.Layout.STRING, Math.addExact(leftLength, rightLength));
      memory.copyBytes(text + Integer.BYTES, x + Integer.BYTES, leftLength);
      memory.copyBytes(text + Integer.BYTES + leftLength, y + Integer.BYTES, rightLength);
      Slot.write(memory, result, Type.STRING, text);
      return;
    }
    if (a == Type.STRING && b == Type.STRING && op == Op.COMPARE) {
      Slot.write(memory, result, Type.BOOL, RawValues.relation(RawValues.compareStrings(memory, x, y), Instr.relation(control >>> 8)) ? 1 : 0);
      return;
    }
    if (a == Type.LIST && b.integer() && op == Op.MUL) {
      ElxList.repeat(heap, result, x, Type.normalize(y, b));
      return;
    }
    if (a == b && (a == Type.LIST || a == Type.TABLE)) {
      if (op == Op.ADD || op == Op.BOR || op == Op.SUB || op == Op.BXOR || op == Op.BAND) {
        if (a == Type.LIST) {
          ElxList.combine(heap, op, result, x, y);
        } else {
          ElxTable.combine(heap, op, result, x, y);
        }
        return;
      }
    }
    if (!a.numeric() || !b.numeric()) {
      throw new IllegalArgumentException("No " + op + " for " + a + " / " + b);
    }
    if (op == Op.COMPARE) {
      Instr.Relation relation = Instr.relation(control >>> 8);
      int order = numericCompare(a, x, b, y);
      boolean matches = order == 2 ? relation == Instr.Relation.NE : relation(order, relation);
      Slot.write(memory, result, Type.BOOL, matches ? 1 : 0);
      return;
    }
    Type kind = Type.binary(a, b);
    x = Type.cast(x, a, kind);
    y = Type.cast(y, b, kind);
    if (!kind.integer()) {

      if (kind == Type.FLOAT) {
        float lhs = Float.intBitsToFloat((int) x), rhs = Float.intBitsToFloat((int) y);
        float value = switch (op) {
          case ADD -> lhs + rhs;
          case SUB -> lhs - rhs;
          case MUL -> lhs * rhs;
          case DIV -> lhs / rhs;
          case MOD -> lhs % rhs;
          default -> throw new IllegalArgumentException("Not lhs floating operator: " + op);
        };
        Slot.write(memory, result, kind, Integer.toUnsignedLong(Float.floatToRawIntBits(value)));
      } else {
        double lhs = Double.longBitsToDouble(x), rhs = Double.longBitsToDouble(y);
        double value = switch (op) {
          case ADD -> lhs + rhs;
          case SUB -> lhs - rhs;
          case MUL -> lhs * rhs;
          case DIV -> lhs / rhs;
          case MOD -> lhs % rhs;
          default -> throw new IllegalArgumentException("Not lhs floating operator: " + op);
        };
        Slot.write(memory, result, kind, Double.doubleToRawLongBits(value));
      }

      return;
    }
    long value = switch (op) {
      case ADD -> x + y;
      case SUB -> x - y;
      case MUL -> x * y;
      case DIV -> {
        if (y == 0) {
          throw new ArithmeticException("/ by zero");
        }
        yield kind.unsigned() ? Long.divideUnsigned(x, y) : x / y;
      }
      case MOD -> {
        if (y == 0) {
          throw new ArithmeticException("/ by zero");
        }
        yield kind.unsigned() ? Long.remainderUnsigned(x, y) : x % y;
      }
      case BAND -> x & y;
      case BOR -> x | y;
      case BXOR -> x ^ y;
      case SHL -> x << ((int) y & (kind.width() - 1));
      case SHR -> x >> ((int) y & (kind.width() - 1));
      case USHR -> (kind.width() == 64 ? x : x & ((1L << kind.width()) - 1)) >>> ((int) y & (kind.width() - 1));
      default -> throw new IllegalArgumentException("Not an integer operator: " + op);
    };
    Slot.write(memory, result, kind, Type.normalize(value, kind));
  }

  private static int compareStrings(Memory memory, long left, long right) {
    int a = memory.load32(left), b = memory.load32(right), length = Math.min(a, b);
    for (int i = 0; i < length; i++) {
      int comparison = Integer.compare(Byte.toUnsignedInt(memory.load8(left + Integer.BYTES + i)), Byte.toUnsignedInt(memory.load8(right + Integer.BYTES + i)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(a, b);
  }

  /**
   * Compares two values structurally without invoking metatable hooks.
   *
   * <p>Lists and tables recurse through their contents with thread-local cycle
   * tracking. Scalar comparison is constant time except for strings, which is
   * linear in the compared byte length; container comparison is proportional to
   * the visited graph.</p>
   *
   * @param memory    memory containing both slots
   * @param leftSlot  left value slot
   * @param rightSlot right value slot
   * @return {@code true} when values are equal under raw ELX semantics
   */
  public static boolean equal(Memory memory, long leftSlot, long rightSlot) {
    Type left = Slot.type(memory, leftSlot), right = Slot.type(memory, rightSlot);
    long x = Slot.bits(memory, leftSlot), y = Slot.bits(memory, rightSlot);
    if (left.numeric() && right.numeric()) {
      return numericEqual(left, x, right, y);
    }
    if (left != right) {
      return false;
    }
    if (left == Type.STRING) {
      return x == y || compareStrings(memory, x, y) == 0;
    }
    if (left != Type.LIST && left != Type.TABLE) {
      return left == Type.NULL || x == y;
    }
    if (x == y) {
      return true;
    }
    return equalContainers(memory, left, x, y);
  }

  private static boolean equalContainers(Memory memory, Type left, long x, long y) {
    int count = Slot.getInt(memory, x);
    if (count != Slot.getInt(memory, y)) {
      return false;
    }
    long[] work = COMPARISONS.get();
    int pairs = (int) work[0];
    for (int i = 0; i < pairs; i++) {
      if (work[1 + 2 * i] == x && work[2 + 2 * i] == y) {
        return true;
      }
    }
    if (2 * pairs + 2 >= work.length) {
      work = Arrays.copyOf(work, 2 * work.length - 1);
      COMPARISONS.set(work);
    }
    work[1 + 2 * pairs] = x;
    work[2 + 2 * pairs] = y;
    work[0] = pairs + 1;
    try {
      if (left == Type.LIST) {
        long a = ElxList.data(memory, x), b = ElxList.data(memory, y);
        for (int i = 0; i < count; i++) {
          if (!equal(memory, Slot.address(a, i), Slot.address(b, i))) {
            return false;
          }
        }
      } else {
        long data = ElxTable.data(memory, x);
        int capacity = ElxTable.capacity(memory, x);
        for (int i = 0; i < capacity; i++) {
          long key = Slot.address(data, i * 2);
          if (Slot.type(memory, key) == Type.INVALID) {
            continue;
          }
          long other = ElxTable.own(memory, y, key);
          if (other == 0 || !equal(memory, key + Slot.SIZE, other)) {
            return false;
          }
        }
      }
      return true;
    } finally {
      COMPARISONS.get()[0]--;
    }
  }

  /**
   * Returns ELX length semantics for a list, table, string, or scalar value.
   * Strings are counted by UTF-8 code points without allocating a Java string;
   * numeric lengths report the number of bytes in the numeric type.
   *
   * @param memory memory containing the value slot
   * @param value  source value slot
   * @return logical ELX length
   */
  public static int length(Memory memory, long value) {
    Type type = Slot.type(memory, value);
    return switch (type) {
      case LIST -> ElxList.size(memory, Slot.bits(memory, value));
      case TABLE -> ElxTable.size(memory, Slot.bits(memory, value));
      case STRING -> stringLength(memory, Slot.bits(memory, value));
      default -> type.numeric() ? (type.width() + Byte.SIZE - 1) / Byte.SIZE : 0;
    };
  }

  private static int stringLength(Memory memory, long pointer) {
    int bytes = memory.load32(pointer);
    int count = 0;
    // Strings contain valid UTF-8. Count code points without exporting/allocating a Java String.
    for (int i = 0; i < bytes; i++) {
      if ((memory.load8(pointer + Integer.BYTES + i) & 0xc0) != 0x80) {
        count++;
      }
    }
    return count;
  }

  /**
   * Reads a table entry or list element into a result slot.
   *
   * @param memory memory containing source and destination slots
   * @param target list or table value slot
   * @param key    index/key slot
   * @param result destination slot
   * @throws IllegalArgumentException  if the target is not indexable
   * @throws IndexOutOfBoundsException if a list index is invalid
   */
  public static void getIndex(Memory memory, long target, long key, long result) {
    Type type = Slot.type(memory, target);
    long pointer = Slot.bits(memory, target);
    if (type == Type.TABLE) {
      ElxTable.get(memory, pointer, key, result);
      return;
    }
    if (type == Type.LIST) {
      int at = index(memory, key);
      ElxList.get(memory, pointer, at, result);
      return;
    }
    throw new IllegalArgumentException("Not indexable: " + type);
  }
}
