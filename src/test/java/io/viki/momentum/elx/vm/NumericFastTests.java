package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.*;
import io.viki.elx.memory.*;
import io.viki.elx.value.RawValues;

/** Bytecode and public operator entry points agree across widths, IEEE cases and aliasing. */
final class NumericFastTests {
  private static final Op[] OPS={Op.ADD,Op.SUB,Op.MUL,Op.DIV,Op.MOD,Op.BAND,Op.BOR,Op.BXOR,Op.SHL,Op.SHR,Op.USHR};
  private NumericFastTests() {}
  static int run(VM vm,Memory memory) {
    int checks=0;
    for(Type type:Type.values()) {
      if(!type.numeric())continue;
      long[] values=type==Type.DOUBLE?new long[]{0,Long.MIN_VALUE,Double.doubleToRawLongBits(1.5),Double.doubleToRawLongBits(Double.NaN),Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)}
          :type==Type.FLOAT?new long[]{0,Integer.toUnsignedLong(Integer.MIN_VALUE),Float.floatToRawIntBits(1.5f),Float.floatToRawIntBits(Float.NaN),Float.floatToRawIntBits(Float.POSITIVE_INFINITY)}
          :new long[]{0,1,Type.normalize(-1,type),Type.normalize(Long.MIN_VALUE,type),Type.normalize(127,type)};
      long right=type==Type.DOUBLE?Double.doubleToRawLongBits(2):type==Type.FLOAT?Float.floatToRawIntBits(2):Type.normalize(3,type);
      for(long left:values)try(Slots.Scope inputs=vm.borrowSlots(2);Slots.Scope out=vm.borrowSlots(1)) {
        inputs.set(0,type,left);inputs.set(1,type,right);
        for(int i=0;i<OPS.length;i++) {
          if(!type.integer()&&i>=5)continue;
          Assembler code=new Assembler();code.constant(0,code.constant(type,left)).constant(1,code.constant(type,right));
          code.r(OPS[i],2,0,1).r(Op.RETURN1,2,0,0);
          vm.execute(code.build(3,0));
          vm.operations().binary(OPS[i],inputs,0,inputs,1,out,0);
          same(vm,out);checks++;
          RawValues.operator(vm.heap(), OPS[i].ordinal(), Slots.address(out,0), Slots.address(inputs,0), Slots.address(inputs,1));
          same(vm,out);checks++;
          vm.operations().binary(OPS[i], inputs, 0, inputs, 1, inputs, 0);
          same(vm, inputs);checks++;
          inputs.set(0,type,left);
        }
        for (Op op : new Op[]{Op.POS, Op.NEG, Op.BNOT, Op.NOT, Op.INC, Op.DEC}) {
          if (op == Op.NOT && type != Type.BOOL || op == Op.BNOT && !type.integer()) continue;
          vm.operations().unary(op,inputs,0,out,0);
          Type expectedType=out.type(0);long expectedBits=out.bits(0);
          if (op == Op.INC || op == Op.DEC) RawValues.increment(memory,Slots.address(out,0),Slots.address(inputs,0),op==Op.INC?1:-1);
          else RawValues.operator(vm.heap(),op.ordinal(),Slots.address(out,0),Slots.address(inputs,0),Slots.address(inputs,0));
          if (out.type(0)!=expectedType || out.bits(0)!=expectedBits) throw new AssertionError("Unary fast path differs: " + type + " " + op);
          checks++;
        }
        for(var relation:Instr.Relation.values()) {
          Assembler code=new Assembler();code.constant(0,code.constant(type,left)).constant(1,code.constant(type,right));
          code.rx(Op.COMPARE,2,0,1,relation.ordinal()).r(Op.RETURN1,2,0,0);
          vm.execute(code.build(3,0));
          boolean result=vm.operations().test(relation,inputs,0,inputs,1);
          if((Slot.bits(memory,vm.results())!=0)!=result)throw new AssertionError("Comparison differs: "+type+" "+relation);
          checks++;
        }
      }
    }
    return checks;
  }
  private static void same(VM vm,Slots out) {
    if(Slot.type(vm.memory(),vm.results())!=out.type(0)||Slot.bits(vm.memory(),vm.results())!=out.bits(0))
      throw new AssertionError("Bytecode and host operation disagree");
  }
}
