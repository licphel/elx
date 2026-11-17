package io.viki.elx.compile;

import io.viki.elx.Type;
import io.viki.elx.asm.Assembler;
import io.viki.elx.asm.Op;
import io.viki.elx.asm.Prototype;
import io.viki.elx.lib.Span;
import io.viki.elx.vm.Slots;
import io.viki.elx.lib.DynamicLibrary;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.memory.*;
import io.viki.elx.value.ElxList;
import io.viki.elx.value.ElxTable;
import io.viki.elx.vm.VM;

/** Automatic relocation, continuous image ranges, shared definitions and native call contracts. */
public final class LibraryTests {
  private static int checks;
  private LibraryTests() { }
  private static final class Host extends ExtHeap {
    Host(ElxHeap heap){super(heap);}
    @Override protected void readField(Object target,long member,long result){throw new UnsupportedOperationException();}
    @Override protected void writeField(Object target,long member,long value){throw new UnsupportedOperationException();}
    @Override protected int invoke(Object target,long member,long args,int count,long result,int capacity){throw new UnsupportedOperationException();}
  }
  public static void run(){
    Compiler compiler=new Compiler();
    SimpleLibrary definitions=new SimpleLibrary();
    definitions.constant("seed",Type.LONG,7).text("label","你好");
    definitions.function("add",compiler.compileFunction("function(a,b) { return a+b+seed }",definitions));
    definitions.function("multiply",args->{args.rl(args.i(0)*args.i(1));});
    definitions.function("bytes",args->{args.ri(Slots.heap(args).strings().utf8Length(args.bits(0)));});
    SimpleLibrary dependent=new SimpleLibrary();dependent.function("forward",compiler.compileFunction("function(x) { return add(x,2) }",definitions));
    Prototype source=compiler.compile("local f=add;local callback=multiply;return f(2,3),callback(6,7),bytes(label)",definitions);
    for(boolean nativeMemory:new boolean[]{false,true}){
      Memory memory=nativeMemory?new NativeMemory(32):new JavaMemory(32);Arena arena=new Arena(memory);
      try(memory; ElxHeap heap=new ElxHeap(arena); Host host=new Host(heap); JavaRegs regs=new JavaRegs(arena); VM vm=new VM(heap,host,regs)){
        DynamicLibrary linked=vm.link(definitions);check(vm.link(definitions)==linked);check(linked.external()==host);check(linked.memory()==memory);
        long end=linked.address()+linked.bytes();
        for(int i=0;i<definitions.size();i++){
          check(linked.entry(i)==linked.address()+(long)i* Slot.SIZE);
          long entry=linked.entry(i),pointer=Slot.bits(memory,entry);Type type=Slot.type(memory,entry);
          if(type==Type.FUNCTION || type==Type.STRING)check(pointer>=linked.address() && pointer<end);
          if(type==Type.FUNCTION && Slot.bits(memory,pointer)>=0){
            long pool=Slot.bits(memory,pointer+Slot.SIZE),code=Slot.bits(memory,pointer+2L*Slot.SIZE);
            check(pool>=linked.address() && pool<end);check(code>=linked.address() && code<end);
          }
        }
        for(int n=0;n<3;n++){check(vm.execute(source)==3);check(Slot.bits(memory,vm.results())==12);check(Slot.bits(memory,vm.results()+Slot.SIZE)==42);check(Slot.bits(memory,vm.results()+2L*Slot.SIZE)==6);}
        check(heap.exportString(Slot.bits(memory,linked.entry(1))).equals("你好"));
        check(vm.execute(compiler.compile("return forward(3)",dependent))==1);check(Slot.bits(memory,vm.results())==12);
        Prototype gc=new Assembler().r(Op.SAFEPOINT,0,0,0).r(Op.RETURN,0,0,0).build(1,0);vm.requestGc(true);vm.execute(gc);
        check(vm.link(definitions)==linked);vm.execute(source);check(Slot.bits(memory,vm.results())==12);
        SimpleLibrary reentry=new SimpleLibrary();reentry.function("nested", args->{
          vm.requestGc(true);vm.execute(compiler.compile("local t=[];return 42"));args.r(Slot.type(memory,vm.results()),Slot.bits(memory,vm.results()));
        });
        vm.execute(compiler.compile("return nested()",reentry));check(Slot.bits(memory,vm.results())==42);check(regs.depth()==0);
        argsChecks(compiler, vm, memory, regs);
        failure(()->definitions.constant("late",Type.INT,1));
      }
    }
    System.out.println("ELX dynamic libraries: "+checks+" checks passed");
  }
  private static void argsChecks(Compiler compiler, VM vm, Memory memory, JavaRegs regs) {
    SimpleLibrary library=new SimpleLibrary();
    library.function("typed", a -> {
      a.arity(6);
      check(a.type(0)==Type.INT && a.i(0)==7);
      check(a.d(1)==2.5 && a.b(2));
      check(a.s(3).equals("你好"));
      check(a.containers().listSize(a,4)==2);
      check(a.containers().tableSize(a,5)==1);
      a.rl(a.i(0)+1);
      a.rd(a.d(1)*2);
      a.rb(a.b(2));
      a.rs(a.s(3)+"!");
    });
    vm.execute(compiler.compile("local a,b,c,d=typed(7,2.5,true,'你好',[1,2],{x=3}); return a,b,c,d",library));
    check(Slot.bits(memory,vm.results())==8);
    check(Slot.getDouble(memory,vm.results()+Slot.SIZE)==5);
    check((Slot.bits(memory,vm.results()+2L*Slot.SIZE)!=0));
    check(vm.heap().exportString(Slot.bits(memory,vm.results()+3L*Slot.SIZE)).equals("你好!"));

    Span[] outer=new Span[1];
    library=new SimpleLibrary();
    SimpleLibrary nested=library;
    library.function("reenter", a -> {
      long n=a.i(0);
      if(n==2) {
        outer[0]=a;
        vm.execute(compiler.compile("return reenter(1)",nested));
        check(a.i(0)==2 && a.size()==1);
      } else {
        check(a!=outer[0]);
        vm.collect(true);
      }
      a.rl(n);
    });
    vm.execute(compiler.compile("return reenter(2)",library));
    check(Slot.bits(memory,vm.results())==2 && regs.depth()==0);

    library=new SimpleLibrary();
    library.function("many", a -> { a.ri(1); a.ri(2); a.ri(3); a.ri(4); });
    vm.execute(compiler.compile("many(); local x=many(); return x",library));
    check(Slot.bits(memory,vm.results())==1);
    library=new SimpleLibrary();
    library.function("echo", a -> { check(a.o(0).equals("hello")); a.ro(a.o(0)); });
    vm.execute(compiler.compile("return echo('hello')",library));
    check(vm.heap().exportString(Slot.bits(memory,vm.results())).equals("hello"));

    library=new SimpleLibrary();
    library.function("bad", a -> { a.b(0); });
    try { vm.execute(compiler.compile("return bad(1)",library)); throw new AssertionError("Expected type error"); }
    catch(IllegalArgumentException expected) { check(expected.getMessage().contains("BOOL")); }
    vm.execute(compiler.compile("return bad(true)",library));
    check(regs.depth()==0);
  }

  private static void failure(Runnable action){checks++;try{action.run();}catch(IllegalStateException expected){return;}throw new AssertionError("Library must freeze on first link");}
  private static void check(boolean value){checks++;if(!value)throw new AssertionError("Library check #"+checks);}
}
