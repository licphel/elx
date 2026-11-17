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
import org.jspecify.annotations.Nullable;

/**
 * Constant-only scalar evaluation; never evaluates allocations, host code or metamethods.
 */
final class ConstantFold {
  private ConstantFold() {
  }

  static @Nullable Value unary(Op op, int control, Value value) {
    Type type = value.type();
    long bits = value.bits();
    if (op == Op.TYPE) {
      return new Value(Type.UBYTE, Byte.toUnsignedLong(type.tag()));
    }
    if (!type.numeric()) {
      return null;
    }

    try {
      return switch (op) {
        case CONVERT -> new Value(Type.fromTag((byte) control), Type.cast(bits, type, Type.fromTag((byte) control)));
        case NEG ->
            new Value(type, type.integer() ? Type.normalize(-bits, type) : bits ^ (type == Type.FLOAT ? 0x80000000L : Long.MIN_VALUE));
        case BNOT -> type.integer() ? new Value(type, Type.normalize(~bits, type)) : null;
        case NOT -> type == Type.BOOL ? new Value(Type.BOOL, bits == 0 ? 1 : 0) : null;
        case INC, DEC -> type.integer() ? new Value(type, Type.normalize(bits + (op == Op.INC ? 1 : -1), type)) : null;
        default -> null;
      };
    } catch (ArithmeticException | IllegalArgumentException failure) {
      return null;
    } // Preserve runtime failures and their source locations.
  }

  static @Nullable Value fold(Op op, int control, Value left, Value right) {
    Type a = left.type(), b = right.type();
    if (!a.numeric() || !b.numeric()) {
      return null;
    }
    Type kind = Type.binary(a, b);
    Op scalar = op;
    if (op == Op.COMPARE && a != b) {
      return null;
    }
    long x = Type.cast(left.bits(), a, kind), y = Type.cast(right.bits(), b, kind);
    if (scalar == Op.COMPARE) {
      Instr.Relation relation = Instr.relation(control);
      boolean result;
      if (kind.integer()) {
        int compare = kind.unsigned() ? Long.compareUnsigned(x, y) : Long.compare(x, y);
        result = switch (relation) {
          case EQ -> compare == 0;
          case NE -> compare != 0;
          case LT -> compare < 0;
          case LE -> compare <= 0;
          case GT -> compare > 0;
          case GE -> compare >= 0;
        };
      } else {
        double l = Type.asDouble(x, kind), r = Type.asDouble(y, kind);
        result = switch (relation) {
          case EQ -> l == r;
          case NE -> l != r;
          case LT -> l < r;
          case LE -> l <= r;
          case GT -> l > r;
          case GE -> l >= r;
        };
      }
      return new Value(Type.BOOL, result ? 1 : 0);
    }
    if (kind.integer()) {
      long result;
      switch (scalar) {
        case ADD -> result = x + y;
        case SUB -> result = x - y;
        case MUL -> result = x * y;
        case DIV -> {
          if (y == 0) {
            return null;
          }
          result = kind.unsigned() ? Long.divideUnsigned(x, y) : x / y;
        }
        case MOD -> {
          if (y == 0) {
            return null;
          }
          result = kind.unsigned() ? Long.remainderUnsigned(x, y) : x % y;
        }
        case BAND -> result = x & y;
        case BOR -> result = x | y;
        case BXOR -> result = x ^ y;
        case SHL -> result = x << ((int) y & (kind.width() - 1));
        case SHR -> result = x >> ((int) y & (kind.width() - 1));
        case USHR ->
            result = (kind.width() == 64 ? x : x & (-1L >>> (64 - kind.width()))) >>> ((int) y & (kind.width() - 1));
        default -> {
          return null;
        }
      }
      return new Value(kind, Type.normalize(result, kind));
    }
    if (kind == Type.FLOAT) {
      float l = Float.intBitsToFloat((int) x), r = Float.intBitsToFloat((int) y), result;
      switch (scalar) {
        case ADD -> result = l + r;
        case SUB -> result = l - r;
        case MUL -> result = l * r;
        case DIV -> result = l / r;
        case MOD -> result = l % r;
        default -> {
          return null;
        }
      }
      if (Float.isNaN(result)) {
        return null;
      }
      return new Value(kind, Integer.toUnsignedLong(Float.floatToRawIntBits(result)));
    }
    double l = Double.longBitsToDouble(x), r = Double.longBitsToDouble(y), result;
    switch (scalar) {
      case ADD -> result = l + r;
      case SUB -> result = l - r;
      case MUL -> result = l * r;
      case DIV -> result = l / r;
      case MOD -> result = l % r;
      default -> {
        return null;
      }
    }
    if (Double.isNaN(result)) {
      return null;
    }
    return new Value(kind, Double.doubleToRawLongBits(result));
  }

  record Value(Type type, long bits) {
  }
}
