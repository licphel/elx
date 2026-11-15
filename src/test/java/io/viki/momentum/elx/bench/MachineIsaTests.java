package io.viki.elx.bench;

import io.viki.elx.Type;
import io.viki.elx.asm.*;
import io.viki.elx.bench.Bench;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.Slot;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;

/** O0 machine/ABI separation, environment calls, register roots and nested entry. */
public final class MachineIsaTests {
  private static int checks;
  private MachineIsaTests() {}
  public static void run() {
    for(Op op:Op.values()) {
      check(op.base()!=op.integrated(),"unclassified opcode "+op);
      if(!op.base())check(!DecomposeRules.get(op).isEmpty(),"missing lowering for "+op);
    }
    for(boolean nativeMemory:new boolean[]{false,true})try(Bench.Runtime r=new Bench.Runtime(nativeMemory)) {
      Assembler a=new Assembler();int zero=a.label(),nonzero=a.label();
      a.constant(0,a.constant(Type.LONG,0)).branch(Op.BR_ZERO,0,zero).immediate(Op.TRAP,0,0,1).bind(zero);
      a.constant(0,a.constant(Type.LONG,9)).immediate(Op.REG_WRITE,0,0, MachineAbi.A4);
      a.constant(0,a.constant(Type.LONG,5)).immediate(Op.REG_READ,1,0,MachineAbi.A4);
      a.rx(Op.IADD,2,0,1,Instr.control(Type.LONG)).branch(Op.BR_NONZERO,2,nonzero).immediate(Op.TRAP,0,0,2);
      a.bind(nonzero).r(Op.CX_RETURN,2,1,0);
      execute(r,a.build(3,0),14);

      SimpleLibrary services=new SimpleLibrary();
      services.function("service",args -> {
        args.arity(1);
        args.vm().collect(true);
        args.vm().execute(new Compiler().compile("return 11"));
        check(args.i(0)==41,"reentry overwrote environment input span");
        args.rl(args.i(0)+1);
      });
      a=new Assembler();
      // ECALL spans use real ABI addresses; CX_LOCAL_PTR now creates a managed language pointer.
      a.constant(0,a.constant(Type.LONG,41)).immediate(Op.REG_READ,1,0,MachineAbi.SP);
      a.immediate(Op.REG_WRITE,1,0,MachineAbi.A0).immediate(Op.REG_WRITE,1,0,MachineAbi.A2);
      a.constant(2,a.constant(Type.INT,1)).immediate(Op.REG_WRITE,2,0,MachineAbi.A1).immediate(Op.REG_WRITE,2,0,MachineAbi.A3);
      a.constant(3,a.symbol(services.resolve("service"))).immediate(Op.REG_WRITE,3,0,MachineAbi.A7);
      a.r(Op.ECALL,0,0,0).r(Op.CX_RETURN,0,1,0);
      execute(r,a.build(4,0),42);

      long object=r.heap.allocate(ElxHeap.Layout.VALUES,1),args=r.heap.arena().allocate(Slot.SIZE);
      Slot.write(r.memory,object,Type.LONG,7);
      Slot.write(r.memory,args,Type.PTR,object);
      try {
        a=new Assembler();
        a.immediate(Op.REG_WRITE,0,0,MachineAbi.A4).constant(0,a.constant(Type.NULL,0));
        a.r(Op.CX_SAFEPOINT,0,0,0).immediate(Op.REG_READ,1,0,MachineAbi.A4).immediate(Op.LOAD64,2,1,Slot.VALUE_OFFSET).r(Op.CX_RETURN,2,1,0);
        r.vm.requestGc(true);
        Prototype code= Decomposer.lower(a.build(3,1));
        check(r.vm.execute(code,args,1)==1 && Slot.bits(r.memory,r.vm.results())==7,"machine register was not traced");
        check(r.heap.isAlive(object),"live physical register lost its object");
        r.collect();
        check(!r.heap.isAlive(object),"inactive register bank retained an object");
        execute(r,new Compiler().compileRaw("reused-bank","require('gc').collect();return 1", Library.EMPTY),1);
      } finally {r.heap.arena().free(args);}

      execute(r,new Compiler().compileRaw("deep-call","local function f(n) { if n==0 { return 0 } return f(n-1)+1 } return f(500)", Library.EMPTY),500);
      check(r.registers.depth()==0,"lowered calls leaked storage windows");
    }
    System.out.println("ELX O0 machine ISA: "+checks+" checks passed");
  }
  private static void execute(Bench.Runtime r,Prototype source,long expected) {
    Prototype code=Decomposer.lower(source);
    for(int pc=0;pc<code.wordCount();) {
      Op op=Instr.op(code.word(pc));check(op.base(),"runtime opcode escaped into O0: "+op);pc+=op.format().words();
    }
    int count=r.vm.execute(code);
    check(count==1 && Slot.bits(r.memory,r.vm.results())==expected,"wrong lowered result");
  }
  private static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
}
