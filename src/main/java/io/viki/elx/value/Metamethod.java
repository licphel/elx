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

import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.memory.Memory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Fixed metatable hook indices. Name matching occurs only when a metatable is
 * attached; execution indexes the cached hook slots directly, so operators do
 * not perform string lookups on the VM hot path.
 */
public enum Metamethod {
  ADD("+"),
  SUB("-"),
  MUL("*"),
  DIV("/"),
  MOD("%"),
  AND("&"),
  OR("|"),
  XOR("^"),
  SHL("<<"),
  SHR(">>"),
  USHR(">>>"),
  INVERT("~"),
  NOT("!"),
  BOOL("bool"),
  EQ("=="),
  NE("!="),
  LT("<"),
  LE("<="),
  GT(">"),
  GE(">="),
  GET("getattr"),
  SET("setattr"),
  CALL("call"),
  INIT("init"),
  STR("str"),
  INC("++"),
  DEC("--"),
  GC("gc");

  private static final Metamethod[] ALL = values();
  private static final int[] OPERATORS = new int[Op.values().length];

  static {
    Arrays.fill(OPERATORS, -1);
    bind(Op.IADD, ADD);
    bind(Op.ISUB, SUB);
    bind(Op.IMUL, MUL);
    bind(Op.IDIV, DIV);
    bind(Op.IREM, MOD);
    bind(Op.BAND, AND);
    bind(Op.BOR, OR);
    bind(Op.BXOR, XOR);
    bind(Op.SHL, SHL);
    bind(Op.SHR, SHR);
    bind(Op.USHR, USHR);
    bind(Op.INEG, SUB);
    bind(Op.FNEG, SUB);
    bind(Op.CX_POS, ADD);
    bind(Op.BNOT, INVERT);
    bind(Op.CX_BOOL_NOT, NOT);
    bind(Op.CX_EQUAL, EQ);
    bind(Op.CX_INCR, INC);
    bind(Op.CX_DECR, DEC);
  }

  private final byte[][] names;

  Metamethod(String... names) {
    this.names = new byte[names.length][];
    for (int i = 0; i < names.length; i++) {
      this.names[i] = names[i].getBytes(StandardCharsets.UTF_8);
    }
  }

  private static void bind(Op op, Metamethod method) {
    OPERATORS[op.ordinal()] = method.ordinal();
  }

  /**
   * Returns the number of hook slots in a metatable hook cache.
   *
   * @return stable hook count
   */
  public static int count() {
    return ALL.length;
  }

  /**
   * Returns a hook by its stable cache index.
   *
   * @param index ordinal returned by {@link #operator(int)} or stored in a hook cache
   * @return metamethod at the index
   * @throws ArrayIndexOutOfBoundsException if the index is outside the hook cache
   */
  public static Metamethod at(int index) {
    return ALL[index];
  }

  /**
   * Resolves an encoded operator/control word to its hook index.
   *
   * @param control encoded instruction/control word
   * @return stable metamethod ordinal, or {@code -1} when no hook is associated
   */
  public static int operator(int control) {
    Op op = Instr.op(control);
    if (op == Op.ICMP) {
      return switch (Instr.relation(control)) {
        case EQ -> EQ.ordinal();
        case NE -> NE.ordinal();
        case LT -> LT.ordinal();
        case LE -> LE.ordinal();
        case GT -> GT.ordinal();
        case GE -> GE.ordinal();
      };
    }
    return OPERATORS[op.ordinal()];
  }

  /**
   * Resolves a UTF-8 string allocation to a metamethod ordinal.
   *
   * @param memory memory containing the string
   * @param string ELX string base pointer
   * @return matching ordinal, or {@code -1} when the name is not recognized
   */
  static int name(Memory memory, long string) {
    int length = memory.load32(string);
    for (Metamethod method : ALL) {
      for (byte[] name : method.names) {
        if (name.length != length) {
          continue;
        }
        int i = 0;
        while (i < length && name[i] == memory.load8(string + Integer.BYTES + i)) {
          i++;
        }
        if (i == length) {
          return method.ordinal();
        }
      }
    }
    return -1;
  }

  /**
   * Tests whether this hook produces a boolean result by convention.
   *
   * @return {@code true} for boolean predicates and conversions
   */
  public boolean booleanResult() {
    return this == NOT || this == BOOL || this == EQ || this == NE || this == LT || this == LE || this == GT || this == GE;
  }
}
