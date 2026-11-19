package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.*;
import io.viki.elx.memory.*;
import io.viki.elx.bench.*;
import io.viki.elx.compile.Compiler;
import io.viki.elx.compile.CompilerTests;
import io.viki.elx.compile.AdvancedTests;
import io.viki.elx.compile.LibraryTests;

/** O1 handwritten programs and the complete executable module regression suite. */
public final class VmTests {
  private static int checks;
  public static void main(String[] args)throws Exception {
    DispatchSizeTests.run();BraceSyntaxTests.run();MachineIsaTests.run();
    StaticValueTests.run();StringTests.run();OptimizationTests.run();CapabilitiesTests.run();
    PreprocessorTests.run();CompilerTests.run();AdvancedTests.run();LibraryTests.run();
    io.viki.momentum.elx.PcallTests.run();
    PointerClosureTests.run();
    StandardModuleTests.run();
    NativeBindingTests.run();
    VoidConversionTests.run();
    CallWindowTests.run();
    DecoratorTests.run();
    io.viki.elx.bench.CaptureTests.run();
    io.viki.elx.HashTests.run();
    exercise(new JavaMemory(32));exercise(new NativeMemory(32));
    SimpleBenchTests.run();
    System.out.println("ELX O1 VM: "+checks+" checks passed");
  }
  public static void run() {exercise(new JavaMemory(32));exercise(new NativeMemory(32));}
  private static Prototype arithmetic(Op op,Type type,long left,long right) {
    Assembler a=new Assembler();a.constant(0,a.constant(type,left)).constant(1,a.constant(type,right));
    a.r(op,2,0,1).r(Op.RETURN1,2,0,0);return a.build(3,0);
  }
  private static void exercise(Memory memory) {
    Arena arena=new Arena(memory);
    try(memory;ElxHeap heap=new ElxHeap(arena);ExtHeap host=new ExtHeap(heap) {};JavaRegs regs=new JavaRegs(arena);VM vm=new VM(heap,host,regs)) {
      checks+=NumericFastTests.run(vm,memory);
      vm.execute(arithmetic(Op.ADD,Type.LONG,20,22));eq(value(vm),42);
      vm.execute(arithmetic(Op.ADD,Type.SHORT,32767,1));eq(value(vm),-32768);
      vm.execute(arithmetic(Op.DIV,Type.ULONG,-1,2));eq(value(vm),Long.MAX_VALUE);
      vm.execute(arithmetic(Op.MOD,Type.ULONG,-1,2));eq(value(vm),1);
      vm.execute(arithmetic(Op.DIV,Type.LONG,Long.MIN_VALUE,-1));eq(value(vm),Long.MIN_VALUE);
      vm.execute(arithmetic(Op.BAND,Type.BOOL,1,0));eq(value(vm),0);
      vm.execute(arithmetic(Op.USHR,Type.INT,-1,1));eq(value(vm),2147483647);
      vm.execute(arithmetic(Op.ADD,Type.DOUBLE,Double.doubleToRawLongBits(1.25),Double.doubleToRawLongBits(2.5)));
      eq(value(vm),Double.doubleToRawLongBits(3.75));
      Assembler sum=new Assembler();int loop=sum.label(),end=sum.label();
      sum.constant(0,sum.constant(Type.LONG,0)).constant(1,sum.constant(Type.LONG,0))
          .constant(2,sum.constant(Type.LONG,1)).constant(3,sum.constant(Type.LONG,100)).bind(loop);
      sum.rx(Op.COMPARE,4,0,3,Instr.control(Instr.Relation.LT)).branch(Op.BR_FALSE,4,end)
          .r(Op.ADD,1,1,0).r(Op.ADD,0,0,2).jump(loop).bind(end).r(Op.RETURN1,1,0,0);
      vm.execute(sum.build(5,0));eq(value(vm),4950);
      Assembler child=new Assembler();child.r(Op.ADD,2,0,1).r(Op.RETURN,0,4,0);
      Assembler parent=new Assembler();
      parent.constant(0,parent.function(child.build(4,2))).constant(1,parent.constant(Type.LONG,20))
          .constant(2,parent.constant(Type.LONG,22)).rx(Op.CALL,3,0,1,Instr.callWindow(2,4)).r(Op.RETURN,3,4,0);
      eq(vm.execute(parent.build(7,0)),4);eq(value(vm),20);
      eq(Slot.bits(memory,vm.results()+Slot.SIZE),22);eq(Slot.bits(memory,vm.results()+2L*Slot.SIZE),42);
      if(Slot.type(memory,vm.results()+3L*Slot.SIZE)!=Type.VOID)throw new AssertionError("Unused results not cleared");
      Assembler text=new Assembler();text.constant(0,text.string("你好\0ELX")).r(Op.SAFEPOINT,0,0,0).r(Op.RETURN1,0,0,0);
      vm.requestGc(true);vm.execute(text.build(1,0));
      if(!heap.strings().exportJava(value(vm)).equals("你好\0ELX"))throw new AssertionError("String lost during GC");
      expect(ArithmeticException.class,()->vm.execute(arithmetic(Op.DIV,Type.LONG,1,0)));
      eq(regs.depth(),0);
      Assembler bad=new Assembler();int exit=bad.label();
      bad.constant(0,bad.constant(Type.INT,1)).branch(Op.BR_TRUE,0,exit).bind(exit).r(Op.RETURN0,0,0,0);
      expect(IllegalArgumentException.class,()->vm.execute(bad.build(1,0)));eq(regs.depth(),0);
      Assembler forever=new Assembler();int again=forever.label();forever.bind(again).jump(again);vm.budget(256);
      expect(IllegalStateException.class,()->vm.execute(forever.build(1,0)));eq(regs.depth(),0);
      vm.budget(100000000);
      AllOpTests.run(vm,heap,host);
    }
  }
  private static long value(VM vm){return Slot.bits(vm.memory(),vm.results());}
  private static void eq(long actual,long expected){checks++;if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
  private static void expect(Class<? extends Throwable> type,Runnable action) {
    checks++;try{action.run();}catch(Throwable failure){if(type.isInstance(failure))return;throw new AssertionError(failure);}
    throw new AssertionError("Expected "+type);
  }
}
