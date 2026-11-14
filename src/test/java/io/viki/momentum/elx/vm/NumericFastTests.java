package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.Assembler;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.vm.VM;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;

/** Differential checks against the generic numeric path, including narrow and IEEE values. */
final class NumericFastTests {
  private static final Op[] FAST = {Op.CX_ADD, Op.CX_SUB, Op.CX_MUL, Op.CX_DIV, Op.CX_REM,
      Op.CX_AND, Op.CX_OR, Op.CX_XOR, Op.CX_SHL, Op.CX_SHR, Op.CX_USHR};
  private static final Op[] GENERIC = {Op.IADD, Op.ISUB, Op.IMUL, Op.IDIV, Op.IREM,
      Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SHR, Op.USHR};

  private NumericFastTests() {}

  static int run(VM vm, Memory memory) {
    int checks = 0;
    for (Type type : Type.values()) {
      if (!type.numeric()) continue;
      long[] samples = type == Type.DOUBLE
          ? new long[]{0, Long.MIN_VALUE, Double.doubleToRawLongBits(1.5), Double.doubleToRawLongBits(Double.NaN), Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)}
          : type == Type.FLOAT
          ? new long[]{0, Integer.toUnsignedLong(Integer.MIN_VALUE), Float.floatToRawIntBits(1.5f), Float.floatToRawIntBits(Float.NaN), Float.floatToRawIntBits(Float.POSITIVE_INFINITY)}
          : new long[]{0, 1, Type.normalize(-1, type), Type.normalize(Long.MIN_VALUE, type), Type.normalize(127, type)};
      long right = type == Type.DOUBLE ? Double.doubleToRawLongBits(2)
          : type == Type.FLOAT ? Float.floatToRawIntBits(2) : Type.normalize(3, type);
      for (long left : samples) {
        for (int i = 0; i < FAST.length; i++) {
          if (!type.integer() && i >= 5) continue;
          Assembler code = new Assembler();
          code.constant(0, code.constant(type, left)).constant(1, code.constant(type, right));
          code.rx(Op.CX_OPERATOR, 2, 0, 1, GENERIC[i].ordinal());
          code.r(FAST[i], 3, 0, 1).r(Op.CX_RETURN, 2, 2, 0);
          vm.execute(code.build(4, 0));
          same(memory, vm.results(), FAST[i] + " " + type);
          checks++;
        }
        for (Instr.Relation relation : Instr.Relation.values()) {
          Assembler code = new Assembler();
          code.constant(0, code.constant(type, left)).constant(1, code.constant(type, right));
          code.rx(Op.CX_OPERATOR, 2, 0, 1, Instr.operator(Op.ICMP, relation));
          code.rx(Op.CX_CMP, 3, 0, 1, Instr.control(type, relation)).r(Op.CX_RETURN, 2, 2, 0);
          vm.execute(code.build(4, 0));
          same(memory, vm.results(), relation + " " + type);
          checks++;
        }
      }
    }
    return checks;
  }

  private static void same(Memory memory, long expected, String operation) {
    long actual = expected + Slot.SIZE;
    if (Slot.type(memory, expected) != Slot.type(memory, actual)
        || Slot.bits(memory, expected) != Slot.bits(memory, actual)) {
      throw new AssertionError(operation + ": fast path differs from generic path");
    }
  }
}
