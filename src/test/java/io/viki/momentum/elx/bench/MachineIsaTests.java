package io.viki.elx.bench;

import io.viki.elx.asm.Optimization;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;

/** Reserved O0 requests fail; O1 native reentry preserves borrowed inputs and roots. */
public final class MachineIsaTests {
  private MachineIsaTests() {}
  public static void run() {
    Compiler compiler=new Compiler();
    try {compiler.compile("return 1",Library.EMPTY,Optimization.O0);throw new AssertionError("O0 accepted");}
    catch(UnsupportedOperationException expected) {}
    try {compiler.compileFunction("function() { return 1 }",Library.EMPTY,Optimization.O0);throw new AssertionError("O0 accepted");}
    catch(UnsupportedOperationException expected) {}
    for(boolean nativeMemory:new boolean[]{false,true})try(Bench.Runtime r=new Bench.Runtime(nativeMemory)) {
      SimpleLibrary library=new SimpleLibrary().function("service",call->{
        call.arity(1);
        var root=call.scratch(1);root.setString(0,"alive");
        r.vm.collect(true);r.vm.execute(compiler.compile("return 11"));
        if(call.i(0)!=41||!root.s(0).equals("alive"))throw new AssertionError("Reentry lost inputs or scratch roots");
        call.rl(call.i(0)+1);
      });
      r.vm.execute(compiler.compile("return service(41)",library));
      if(io.viki.elx.memory.Slot.bits(r.memory,r.vm.results())!=42||r.registers.depth()!=0)throw new AssertionError("Native call window leak");
      r.vm.execute(compiler.compile("local function f(n) { if n==0 { return 0 } return f(n-1)+1 } return f(500)"));
      if(io.viki.elx.memory.Slot.bits(r.memory,r.vm.results())!=500)throw new AssertionError("Deep O1 call failed");
    }
    System.out.println("ELX O1 ABI and unavailable O0: passed");
  }
}
