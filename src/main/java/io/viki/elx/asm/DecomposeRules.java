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

import java.util.*;

import static io.viki.elx.asm.DecomposeRule.*;
import static io.viki.elx.asm.DecomposeRule.Operand.*;
import static io.viki.elx.asm.Op.*;

/**
 * Canonical O0 expansions for complex VM operations.
 *
 * <p>Each rule lowers one integrated compiler operation to base instructions and Slot-native
 * library calls. Runtime symbols are resolved once by the linker; no runtime string registry or
 * Java reflection is involved in dispatch. The table is immutable after class initialization.
 */
public final class DecomposeRules {
  private static final Map<Op, List<DecomposeRule>> RULES = create();

  private DecomposeRules() {
  }

  private static Map<Op, List<DecomposeRule>> create() {
    EnumMap<Op, List<DecomposeRule>> rules = new EnumMap<>(Op.class);
    for (Op op : new Op[] {CX_ADD, CX_SUB, CX_MUL, CX_DIV, CX_REM, CX_AND, CX_OR, CX_XOR, CX_SHL, CX_SHR, CX_USHR, CX_CMP, CX_EQUAL, CX_OPERATOR, CX_INCR, CX_DECR, CX_POS}) {
      put(rules, op, function("operator", A, B, op == CX_INCR || op == CX_DECR || op == CX_POS ? B : C, OPERATION));
    }
    put(rules, CX_LOCAL_PTR, function("local.pointer", A, COUNT_B));
    put(rules, CX_CAPTURE_PTR, function("capture.pointer", A, IMM));
    put(rules, CX_CONST_PTR, function("constant.address", A, CONSTANT_INDEX));
    put(rules, CX_SELF, instruction(MOVE, A, FRAME));
    put(rules, CX_CALL, new LibraryCall("call", List.of(B, ARG_BASE, ARG_COUNT, RESULT_COUNT), List.of(RESULT_BASE)));
    put(rules, CX_CALL_INDIRECT, new LibraryCall("call", List.of(B, C, ARG_COUNT, RESULT_COUNT), List.of(RESULT_BASE)));
    put(rules, CX_RETURN, call("return", List.of(ADDRESS_A, COUNT_B)));
    put(rules, CX_RETURN_INDIRECT, call("return", List.of(A, B)));
    put(rules, CX_TYPE, instruction(LOAD8, A, ADDRESS_B, ZERO));
    put(rules, CX_BITS, instruction(LOAD64, A, ADDRESS_B, EIGHT));
    put(rules, CX_LEN, function("length", A, B));
    put(rules, CX_PACK, function("pack", A, B, C));
    put(rules, CX_GUARD, call("guard", List.of(A, IMM)));
    put(rules, CX_CONVERT, function("convert", A, B, IMM));
    put(rules, CX_TO_STRING, function("to.string", A, B));
    put(rules, CX_BOOL_NOT, function("bool.not", A, B));
    put(rules, CX_ALLOC, function("allocate", A, B, C));
    put(rules, CX_SAFEPOINT, call("poll", List.of()));
    put(rules, CX_BR_TRUE, function("bool.check", TMP0, A), instruction(BR_NONZERO, TMP0, LABEL));
    put(rules, CX_BR_FALSE, function("bool.check", TMP0, A), instruction(BR_ZERO, TMP0, LABEL));
    put(rules, CX_EX_CALL, new LibraryCall("host.call", List.of(B, C, ARG_BASE, ARG_COUNT, RESULT_COUNT), List.of(RESULT_BASE)));
    put(rules, CX_EX_GET, function("host.get", A, B, C));
    put(rules, CX_EX_SET, call("host.set", List.of(A, B, C)));
    put(rules, CX_EX_ADDRESS, function("host.address", A, B, C));
    put(rules, CX_EX_RETAIN, call("host.retain", List.of(A)));
    put(rules, CX_EX_RELEASE, call("host.release", List.of(A)));
    put(rules, CX_ENV, function("env", A));
    put(rules, CX_GET_GLOBAL_CONST, function("env", TMP0), function("table.get", A, TMP0, CONSTANT));
    put(rules, CX_ADD_CONST, function("operator", A, B, CONSTANT, OPERATION));
    put(rules, CX_SUB_CONST, function("operator", A, B, CONSTANT, OPERATION));
    put(rules, CX_GET_GLOBAL, function("env", TMP0), function("table.get", A, TMP0, B));
    put(rules, CX_SET_GLOBAL, function("env", TMP0), call("table.set", List.of(TMP0, A, B)));
    put(rules, CX_BR_PRESENT, instruction(LOAD8, TMP0, ADDRESS_A, ZERO), instruction(LC, TMP1, INVALID_TAG), instruction(ICMP, TMP0, TMP0, TMP1, UBYTE_NE), instruction(BR_NONZERO, TMP0, LABEL));
    put(rules, CX_CALL_NAMED, new LibraryCall("call.named", List.of(B, ADDRESS_C, IMM, FRAME), List.of(OUT0, OUT1, OUT2, OUT3)));
    put(rules, CX_DEREF, function("deref", TMP0, B, IMM), call("memory.copy", List.of(ADDRESS_A, TMP0, VALUE_COUNT)));
    put(rules, CX_RETURN_DEREF, new LibraryCall("unpack", List.of(A, ALLOW_LIST), List.of(TMP0, TMP1)), call("return", List.of(TMP0, TMP1)));
    put(rules, CX_APPEND_DEREF, call("append.unpack", List.of(A, B)));
    put(rules, CX_CALL_PACKED, new LibraryCall("unpack", List.of(C, ALLOW_LIST), List.of(TMP0, TMP1)), new LibraryCall("call", List.of(B, TMP0, TMP1, RESULT_COUNT), List.of(RESULT_BASE)));
    put(rules, CX_CLOSURE, function("closure", A, B, ADDRESS_C, IMM));
    put(rules, CX_GET_CAPTURE, instruction(LC, TMP1, CAPTURE_OFFSET), instruction(LEA, TMP0, FRAME, TMP1), call("memory.copy", List.of(ADDRESS_A, TMP0, WINDOW_BYTES)));
    put(rules, CX_SET_CAPTURE, instruction(LC, TMP1, CAPTURE_OFFSET), instruction(LEA, TMP0, FRAME, TMP1), call("memory.copy", List.of(TMP0, ADDRESS_A, WINDOW_BYTES)));
    put(rules, CX_GET_INDEX_CONST, function("index.get", A, B, CONSTANT));
    put(rules, CX_BR_CMP, function("operator", A, B, C, OPERATION), instruction(LC, TMP0, BRANCH_VALUE), instruction(EQ64, TMP0, A, TMP0), instruction(BR_NONZERO, TMP0, LABEL));
    put(rules, CX_GET_INDEX, function("index.get", A, B, C));
    put(rules, CX_SET_INDEX, call("index.set", List.of(A, B, C)));
    put(rules, CX_INDEX_PTR, function("index.pointer", A, B, C));
    put(rules, CX_GLOBAL_PTR, function("global.pointer", A, B));
    put(rules, CX_WRITE_PTR, call("pointer.write", List.of(A, B)));
    put(rules, CX_SET_META, function("meta.set", A, B, C));
    put(rules, CX_GET_META, function("meta.get", A, B));
    put(rules, CX_NEXT, new LibraryCall("next", List.of(B, C), List.of(OUT0, OUT1, OUT2, OUT3)));
    put(rules, CX_IADD_CONST, instruction(LC, TMP0, IMM), instruction(IADD, A, B, TMP0, KIND));
    put(rules, CX_BR_ICMP, instruction(ICMP, TMP0, A, B, IMM), instruction(BR_NONZERO, TMP0, LABEL));
    put(rules, CX_LOAD64_INDEXED, instruction(LC, TMP0, EIGHT), instruction(IMUL, TMP0, C, TMP0, I64), instruction(LEA, TMP0, B, TMP0), instruction(LOAD64, A, TMP0, ZERO));
    put(rules, CX_COPY_MEMORY, call("memory.copy", List.of(A, B, C)));
    put(rules, CX_HASH, function("hash", A, B));
    put(rules, CX_GET_TABLE, function("table.get", A, B, C));
    put(rules, CX_SET_TABLE, call("table.set", List.of(A, B, C)));
    put(rules, CX_LIST_APPEND, call("list.append", List.of(A, B)));
    put(rules, CX_GET_TABLE_IKV, new LibraryCall("table.next", List.of(B, C), List.of(OUT0, OUT1, OUT2, OUT3)));
    put(rules, CX_GET_LIST_IV, new LibraryCall("list.next", List.of(B, C), List.of(OUT0, OUT1, OUT2)));
    put(rules, CX_EX_CALL0, function("host.call", A, B, C, ZERO, ZERO, ONE));
    return Collections.unmodifiableMap(rules);
  }

  private static LibraryCall call(String symbol, List<Operand> inputs) {
    return new LibraryCall(symbol, inputs, List.of());
  }

  private static void put(Map<Op, List<DecomposeRule>> rules, Op op, Step... steps) {
    rules.put(op, List.of(of(op.name().toLowerCase(Locale.ROOT), steps)));
  }

  /**
   * Returns the ordered expansion alternatives for an operation.
   *
   * @param op integrated or base operation to lower
   * @return immutable alternatives, or an empty list when no O0 expansion exists
   */
  public static List<DecomposeRule> get(Op op) {
    return RULES.getOrDefault(op, List.of());
  }
}
