package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.*;
import io.viki.elx.memory.*;
import io.viki.elx.vm.VM;
import io.viki.elx.bench.BraceSyntaxTests;
import io.viki.elx.bench.CapabilitiesTests;
import io.viki.elx.bench.MachineIsaTests;
import io.viki.elx.bench.OptimizationTests;
import io.viki.elx.bench.PreprocessorTests;
import io.viki.elx.bench.SimpleBenchTests;
import io.viki.elx.bench.StaticValueTests;
import io.viki.elx.bench.StringTests;
import io.viki.elx.compile.AdvancedTests;
import io.viki.elx.compile.Compiler;
import io.viki.elx.compile.CompilerTests;
import io.viki.elx.compile.LibraryTests;
import io.viki.elx.lib.Library;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/** Hand-assembled programs: no source compiler or Java fallback is involved. */
public final class VmTests {
  private static int checks;
  private static final class Box { long value=7; }
  private static final class Host extends ExtHeap {
    @Nullable VM vm;
    @Nullable Prototype reentry;
    Host(ElxHeap heap){super(heap);}
    @Override protected void readField(Object target,long name,long result){
      if(!memberName(name).equals("value"))throw new IllegalArgumentException("Unknown field");
      importJava(result,((Box)target).value);
    }
    @Override protected void writeField(Object target,long name,long value){
      if(!memberName(name).equals("value"))throw new IllegalArgumentException("Unknown field");
      ((Box)target).value= Slot.getLong(memory(),value);
    }
    @Override protected int invoke(Object target,long name,long args,int count,long result,int capacity){
      String method=memberName(name);
      if(method.equals("zero")){importJava(result,42L);return 1;}
      if(method.equals("add")){
        long a=Slot.getLong(memory(),args),b=Slot.getLong(memory(),Slot.address(args,1));
        importJava(result,a+b);return 1;
      }
      if(method.equals("reenter")){
        VM machine=vm;Prototype image=reentry;
        if(machine==null || image==null)throw new IllegalStateException("Host test not initialized");
        machine.execute(image);long value=Slot.getLong(memory(),machine.results());
        importJava(result,value);return 1;
      }
      return -7;
    }
  }
  public static void run(){try {
    PreprocessorTests.run();} catch(IOException failure) {throw new UncheckedIOException(failure);}
    CompilerTests.run();
    AdvancedTests.run();
    LibraryTests.run();exercise(new JavaMemory(32));exercise(new NativeMemory(32));System.out.println("ELX switch VM: "+(checks+AllOpTests.checks())+" opcode/value checks passed");}
  public static void main(String[] args) throws IOException {DispatchSizeTests.run();
    BraceSyntaxTests.run();
    MachineIsaTests.run();
    StaticValueTests.run();
    StringTests.run();
    OptimizationTests.run();
    CapabilitiesTests.run();run();
    SimpleBenchTests.run();}

  private static Prototype arithmetic(Op op, Type kind, long left, long right){
    Assembler a=new Assembler();a.constant(0,a.constant(kind,left)).constant(1,a.constant(kind,right));
    a.rx(op,2,0,1,Instr.control(kind)).r(Op.CX_RETURN,2,1,0);return a.build(3,0);
  }
  private static Prototype sum(){
    Assembler a=new Assembler();int zero=a.constant(Type.LONG,0),one=a.constant(Type.LONG,1),limit=a.constant(Type.LONG,100);
    int loop=a.label(),exit=a.label();
    a.constant(0,zero).constant(1,zero).constant(2,one).constant(3,limit).bind(loop);
    a.rx(Op.ICMP,4,0,3,Instr.control(Type.LONG,Instr.Relation.LT)).branch(Op.CX_BR_FALSE,4,exit);
    a.rx(Op.IADD,1,1,0,Instr.control(Type.LONG)).rx(Op.IADD,0,0,2,Instr.control(Type.LONG)).jump(loop);
    a.bind(exit).r(Op.CX_RETURN,1,1,0);return a.build(5,0);
  }
  private static void exercise(Memory memory){
    Arena arena=new Arena(memory);
    try(memory; ElxHeap heap=new ElxHeap(arena); Host host=new Host(heap); JavaRegs regs=new JavaRegs(arena); VM vm=new VM(heap,host,regs)){
      host.vm=vm;
      checks += NumericFastTests.run(vm, memory);
      Prototype add=arithmetic(Op.IADD,Type.LONG,20,22);
      equal(vm.execute(add),1);equal(value(vm),42L);equal(Slot.type(memory,vm.results()),Type.LONG);
      Prototype loop=sum();vm.execute(loop);equal(value(vm),4950L);
      System.out.println(memory.getClass().getSimpleName()+": sum(0..99)="+value(vm)+" instructions="+vm.instructions());
      vm.execute(arithmetic(Op.IADD,Type.SHORT,32767,1));equal(value(vm),-32768L);
      vm.execute(arithmetic(Op.UDIV,Type.ULONG,-1,2));equal(value(vm),Long.MAX_VALUE);
      vm.execute(arithmetic(Op.UREM,Type.ULONG,-1,2));equal(value(vm),1L);
      vm.execute(arithmetic(Op.IDIV,Type.LONG,Long.MIN_VALUE,-1));equal(value(vm),Long.MIN_VALUE);
      vm.execute(arithmetic(Op.BAND,Type.BOOL,1,0));equal(value(vm),0L);
      vm.execute(arithmetic(Op.USHR,Type.INT,-1,1));equal(value(vm),2147483647L);
      vm.execute(arithmetic(Op.FADD,Type.DOUBLE,Double.doubleToRawLongBits(1.25),Double.doubleToRawLongBits(2.5)));
      equal(Slot.getDouble(memory,vm.results()),3.75);
      for(Instr.Relation relation:Instr.Relation.values()){
        Assembler a=new Assembler();a.constant(0,a.constant(Type.DOUBLE,Double.doubleToRawLongBits(Double.NaN))).constant(1,a.constant(Type.DOUBLE,0));
        a.rx(Op.FCMP,2,0,1,Instr.control(Type.DOUBLE,relation)).r(Op.CX_RETURN,2,1,0);vm.execute(a.build(3,0));
        equal(value(vm),relation==Instr.Relation.NE?1L:0L);
      }
      Assembler casts=new Assembler();casts.constant(0,casts.constant(Type.DOUBLE,Double.doubleToRawLongBits(1.0)));
      casts.immediate(Op.CX_CONVERT,1,0,Type.BOOL.ordinal()).r(Op.CX_BOOL_NOT,2,1,0).r(Op.CX_TYPE,3,1,0).r(Op.CX_BITS,4,1,0);
      casts.immediate(Op.CX_GUARD,1,0,Type.BOOL.ordinal()).r(Op.CX_RETURN,1,4,0);
      vm.execute(casts.build(5,0));equal(value(vm),1L);equal(Slot.bits(memory,Slot.address(vm.results(),1)),0L);
      equal(Slot.bits(memory,Slot.address(vm.results(),2)),(long)Byte.toUnsignedInt(Type.BOOL.tag()));
      equal(Slot.bits(memory,Slot.address(vm.results(),3)),1L);
      Assembler fused=new Assembler();int fusedExit=fused.label();
      fused.constant(0,fused.constant(Type.LONG,40)).immediate(Op.CX_IADD_CONST,1,0,Byte.toUnsignedInt(Type.LONG.tag()),2);
      fused.compareBranch(0,1,Instr.control(Type.LONG,Instr.Relation.LT),fusedExit).immediate(Op.TRAP,0,0,0).bind(fusedExit).r(Op.CX_RETURN,1,1,0);
      vm.execute(fused.build(2,0));equal(value(vm),42L);

      Assembler neg=new Assembler();neg.constant(0,neg.constant(Type.DOUBLE,0)).rx(Op.FNEG,0,0,0,Instr.control(Type.DOUBLE)).r(Op.CX_RETURN,0,1,0);
      vm.execute(neg.build(1,0));equal(value(vm),Long.MIN_VALUE);

      Assembler alloc=new Assembler();alloc.constant(0,alloc.constant(Type.INT,24)).constant(1,alloc.constant(Type.INT,ElxHeap.Layout.BYTES.ordinal()));
      alloc.r(Op.CX_ALLOC,2,0,1).constant(3,alloc.constant(Type.LONG,-42)).immediate(Op.STORE64,3,2,8).immediate(Op.LOAD64,4,2,8).r(Op.CX_RETURN,4,1,0);
      vm.execute(alloc.build(5,0));equal(value(vm),-42L);equal(Slot.type(memory,vm.results()),Type.ULONG);
      for(Op load:new Op[]{Op.LOAD8,Op.LOAD16,Op.LOAD32}) {
        Assembler narrow=new Assembler();narrow.constant(0,narrow.constant(Type.INT,8)).constant(1,narrow.constant(Type.INT,ElxHeap.Layout.BYTES.ordinal()));
        narrow.r(Op.CX_ALLOC,2,0,1).constant(3,narrow.constant(Type.LONG,-1)).immediate(Op.STORE64,3,2,0).immediate(load,4,2,0).r(Op.CX_RETURN,4,1,0);
        vm.execute(narrow.build(5,0));equal(value(vm),load==Op.LOAD8?255L:load==Op.LOAD16?65535L:4294967295L);
      }
      Assembler cells=new Assembler();cells.constant(0,cells.constant(Type.INT,1)).constant(1,cells.constant(Type.INT,ElxHeap.Layout.VALUES.ordinal()));
      cells.r(Op.CX_ALLOC,2,0,1).constant(3,cells.constant(Type.LONG,99)).immediate(Op.STORE64,3,2,Slot.VALUE_OFFSET);
      cells.constant(4,cells.constant(Type.UBYTE,Byte.toUnsignedInt(Type.LONG.tag()))).immediate(Op.STORE8,4,2,0);
      cells.immediate(Op.LOAD8,4,2,0).immediate(Op.LOAD64,3,2,Slot.VALUE_OFFSET).r(Op.CX_PACK,5,4,3).r(Op.CX_RETURN,5,1,0);
      vm.execute(cells.build(6,0));equal(value(vm),99L);equal(Slot.type(memory,vm.results()),Type.LONG);

      Assembler child=new Assembler();child.rx(Op.IADD,2,0,1,Instr.control(Type.LONG)).r(Op.CX_RETURN,0,4,0);
      Prototype childImage=child.build(4,2);Assembler parent=new Assembler();
      parent.constant(0,parent.function(childImage)).constant(1,parent.constant(Type.LONG,20)).constant(2,parent.constant(Type.LONG,22));
      parent.rx(Op.CX_CALL,3,0,1,Instr.callWindow(2,4)).r(Op.CX_RETURN,3,4,0);vm.execute(parent.build(7,0));
      equal(value(vm),20L);equal(Slot.bits(memory,Slot.address(vm.results(),1)),22L);equal(Slot.bits(memory,Slot.address(vm.results(),2)),42L);
      equal(Slot.type(memory,Slot.address(vm.results(),3)),Type.NULL);

      Assembler text=new Assembler();text.constant(0,text.string("你好\0ELX")).r(Op.CX_SAFEPOINT,0,0,0).r(Op.CX_RETURN,0,1,0);
      vm.requestGc(true);vm.execute(text.build(1,0));long string=Slot.bits(memory,vm.results());equal(heap.exportString(string),"你好\0ELX");
      Box box=new Box();long object=host.add(box),args=arena.allocate(2*Slot.SIZE);
      Slot.write(memory,args,Type.EXTERNAL,object);
      vm.setGlobal("host_box",args);
      var compiler=new Compiler();
      for(Optimization mode:Optimization.values()) {
        box.value=7;
        vm.requestGc(true);
        vm.execute(compiler.compile("local before=host_box.value;host_box['value']=before+2;host_box.value+=1;return before,host_box.value,len(host_box)", Library.EMPTY,mode));
        equal(value(vm),7L);equal(Slot.bits(memory,vm.results()+Slot.SIZE),10L);
        equal(Slot.bits(memory,vm.results()+2L*Slot.SIZE),0L);equal(box.value,10L);
        expect(IllegalArgumentException.class,()->vm.execute(compiler.compile("return host_box[0]", Library.EMPTY,mode)));
        expect(IllegalArgumentException.class,()->vm.execute(compiler.compile("host_box[0]=1", Library.EMPTY,mode)));
      }
      box.value=7;
      long member=heap.importString("value");
      Slot.write(memory,args,Type.EXTERNAL,object);Slot.write(memory,Slot.address(args,1),Type.STRING,member);
      Assembler fields=new Assembler();fields.r(Op.CX_EX_GET,2,0,1).constant(3,fields.constant(Type.LONG,77)).r(Op.CX_EX_SET,0,1,3).r(Op.CX_EX_ADDRESS,4,0,1).r(Op.CX_RETURN,2,3,0);
      vm.requestGc(true);vm.execute(fields.build(5,2),args,2);equal(value(vm),7L);equal(box.value,77L);
      long field=Slot.bits(memory,Slot.address(vm.results(),2));equal(Slot.type(memory,Slot.address(vm.results(),2)),Type.PTR);
      Slot.write(memory,args,Type.PTR,field);Assembler deref=new Assembler();deref.constant(1,deref.constant(Type.LONG,0)).r(Op.CX_EX_GET,2,0,1).r(Op.CX_RETURN,2,1,0);
      vm.execute(deref.build(3,1),args,1);equal(value(vm),77L);

      Slot.write(memory,args,Type.EXTERNAL,object);Slot.write(memory,Slot.address(args,1),Type.STRING,heap.importString("add"));
      Assembler call=new Assembler();call.constant(2,call.constant(Type.LONG,20)).constant(3,call.constant(Type.LONG,22));
      call.rx(Op.CX_EX_CALL,2,0,1,Instr.externalWindow(2,2,2)).r(Op.CX_RETURN,2,2,0);
      vm.execute(call.build(4,2),args,2);equal(value(vm),42L);equal(Slot.type(memory,Slot.address(vm.results(),1)),Type.NULL);
      Assembler failCall=new Assembler();failCall.rx(Op.CX_EX_CALL,2,0,1,Instr.externalWindow(2,0,1)).r(Op.CX_RETURN,2,1,0);
      Slot.write(memory,Slot.address(args,1),Type.STRING,heap.importString("fail"));
      expect(IllegalStateException.class,()->vm.execute(failCall.build(3,2),args,2));equal(regs.depth(),0);
      host.reentry=add;Slot.write(memory,Slot.address(args,1),Type.STRING,heap.importString("reenter"));
      vm.execute(call.build(4,2),args,2);equal(value(vm),42L);equal(regs.depth(),0);
      arena.free(args);

      Assembler badCondition=new Assembler();int exit=badCondition.label();badCondition.constant(0,badCondition.constant(Type.INT,1)).branch(Op.CX_BR_TRUE,0,exit).bind(exit).r(Op.CX_RETURN,0,0,0);
      expect(IllegalArgumentException.class,()->vm.execute(badCondition.build(1,0)));equal(regs.depth(),0);
      expect(ArithmeticException.class,()->vm.execute(arithmetic(Op.IDIV,Type.LONG,1,0)));equal(regs.depth(),0);
      Assembler bounds=new Assembler();bounds.constant(0,bounds.constant(Type.LONG,-1)).constant(1,bounds.constant(Type.LONG,4)).r(Op.BOUNDS,0,1,0).r(Op.CX_RETURN,0,0,0);
      expect(IndexOutOfBoundsException.class,()->vm.execute(bounds.build(2,0)));
      Assembler guard=new Assembler();guard.constant(0,guard.constant(Type.INT,1)).immediate(Op.CX_GUARD,0,0,Type.BOOL.ordinal()).r(Op.CX_RETURN,0,0,0);
      expect(IllegalArgumentException.class,()->vm.execute(guard.build(1,0)));
      expect(IllegalArgumentException.class,()->vm.execute(childImage));
      Assembler trap=new Assembler();trap.immediate(Op.TRAP,0,0,123);expect(IllegalStateException.class,()->vm.execute(trap.build(1,0)));
      Assembler forever=new Assembler();int again=forever.label();forever.bind(again).jump(again);vm.budget(256);
      expect(IllegalStateException.class,()->vm.execute(forever.build(1,0)));equal(regs.depth(),0);
      vm.budget(100000000);vm.execute(add);equal(value(vm),42L);
      AllOpTests.run(vm,heap,host);
      System.out.println("Handwritten addition:\n"+ Disassembler.dump(add));
    }
  }
  private static long value(VM vm){return Slot.bits(vm.memory(),vm.results());}
  private static void equal(@Nullable Object actual,@Nullable Object expected){checks++;if(!Objects.equals(actual,expected))throw new AssertionError(actual+" != "+expected);}
  private static void expect(Class<? extends Throwable> type,Runnable task){checks++;try{task.run();}catch(Throwable failure){if(type.isInstance(failure))return;throw new AssertionError("Expected "+type,failure);}throw new AssertionError("Expected "+type);}
}
