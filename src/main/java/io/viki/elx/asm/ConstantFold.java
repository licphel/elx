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
    if (op == Op.CX_TYPE) {
      return new Value(Type.UBYTE, Byte.toUnsignedLong(type.tag()));
    }
    if (!type.numeric()) {
      return null;
    }
    if ((op == Op.INEG || op == Op.FNEG || op == Op.BNOT) && Instr.type(control) != type) {
      return null;
    }
    try {
      return switch (op) {
        case CX_BITS -> new Value(Type.ULONG, bits);
        case CX_CONVERT -> new Value(Type.fromTag((byte) control), Type.cast(bits, type, Type.fromTag((byte) control)));
        case INEG, FNEG ->
            new Value(type, type.integer() ? Type.normalize(-bits, type) : bits ^ (type == Type.FLOAT ? 0x80000000L : Long.MIN_VALUE));
        case BNOT -> type.integer() ? new Value(type, Type.normalize(~bits, type)) : null;
        case CX_BOOL_NOT -> type == Type.BOOL ? new Value(Type.BOOL, bits == 0 ? 1 : 0) : null;
        case CX_INCR, CX_DECR ->
            type.integer() ? new Value(type, Type.normalize(bits + (op == Op.CX_INCR ? 1 : -1), type)) : null;
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
    boolean dynamic = op.integrated();
    Op scalar = switch (op) {
      case CX_ADD -> Op.IADD;
      case CX_SUB -> Op.ISUB;
      case CX_MUL -> Op.IMUL;
      case CX_DIV -> Op.IDIV;
      case CX_REM -> Op.IREM;
      case CX_AND -> Op.BAND;
      case CX_OR -> Op.BOR;
      case CX_XOR -> Op.BXOR;
      case CX_SHL -> Op.SHL;
      case CX_SHR -> Op.SHR;
      case CX_USHR -> Op.USHR;
      case CX_CMP, CX_EQUAL -> Op.ICMP;
      default -> op;
    };
    Type kind = dynamic ? Type.binary(a, b) : Instr.type(control);
    if (!dynamic && (a != kind || b != kind)) {
      return null;
    }
    if ((op == Op.CX_CMP || op == Op.CX_EQUAL) && a != b) {
      return null; // mixed numeric equality has exactness rules beyond widening.
    }
    long x = Type.cast(left.bits(), a, kind), y = Type.cast(right.bits(), b, kind);
    if (scalar == Op.ICMP || scalar == Op.UCMP || scalar == Op.FCMP) {
      Instr.Relation relation = op == Op.CX_EQUAL ? Instr.Relation.EQ : Instr.relation(control);
      boolean result;
      if (kind.integer()) {
        int compare = kind.unsigned() || scalar == Op.UCMP ? Long.compareUnsigned(x, y) : Long.compare(x, y);
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
        case IADD -> result = x + y;
        case ISUB -> result = x - y;
        case IMUL -> result = x * y;
        case IDIV, UDIV -> {
          if (y == 0) {
            return null;
          }
          result = kind.unsigned() || scalar == Op.UDIV ? Long.divideUnsigned(x, y) : x / y;
        }
        case IREM, UREM -> {
          if (y == 0) {
            return null;
          }
          result = kind.unsigned() || scalar == Op.UREM ? Long.remainderUnsigned(x, y) : x % y;
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
        case FADD, IADD -> result = l + r;
        case FSUB, ISUB -> result = l - r;
        case FMUL, IMUL -> result = l * r;
        case FDIV, IDIV -> result = l / r;
        case FREM, IREM -> result = l % r;
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
      case FADD, IADD -> result = l + r;
      case FSUB, ISUB -> result = l - r;
      case FMUL, IMUL -> result = l * r;
      case FDIV, IDIV -> result = l / r;
      case FREM, IREM -> result = l % r;
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
