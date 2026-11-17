package io.viki.elx.bench;

import io.viki.elx.Type;
import io.viki.elx.asm.*;
import io.viki.elx.compile.Compiler;
import io.viki.elx.memory.Slot;
import java.util.Arrays;

/** Result-demand propagation preserves effects, missing results and all sixteen outputs. */
public final class CallWindowTests {
  private CallWindowTests() {}
  public static void main(String[] args) { run(); }
  public static void run() {
    Compiler compiler=new Compiler();
    for (boolean nativeMemory:new boolean[]{false,true}) {
      for (String call:new String[]{"f(1)","f(*[1])","f(a=1)"}) {
        String source="local function f(a){return a,2,3;}"+call+";local a="+call+";local b,c="+call+";return a,b,c;";
        Prototype image=compiler.compile(source);
        int[] capacities=new int[3];int calls=0;
        for(int pc=0;pc<image.wordCount();) {
          Op op=Instr.op(image.word(pc));int control=op.format().words()>1?image.word(pc+1):0;
          if(op==Op.CALL || op==Op.CALL_PACKED || op==Op.CALL_NAMED) {
            capacities[calls++]=op==Op.CALL?Instr.resultCount(control):op==Op.CALL_PACKED?control&255:image.callSite(control).resultCapacity();
          }
          pc+=op.format().words();
        }
        if(calls!=3 || !Arrays.equals(capacities,new int[]{0,1,2})) throw new AssertionError("Result demands for "+call+": "+Arrays.toString(capacities));
        try(Bench.Runtime runtime=new Bench.Runtime(nativeMemory)) {
          runtime.vm.execute(image);
          long[] expected={1,1,2};
          for(int i=0;i<3;i++)if(Slot.bits(runtime.memory,Slot.address(runtime.vm.results(),i))!=expected[i])throw new AssertionError(source);
          String names=String.join(",",java.util.stream.IntStream.range(0,16).mapToObj(i->"v"+i).toList());
          String values=String.join(",",java.util.stream.IntStream.range(0,16).mapToObj(Integer::toString).toList());
          runtime.vm.execute(compiler.compile("local function all(){return "+values+";}local "+names+"=all();return "+names+";"));
          for(int i=0;i<16;i++)if(Slot.bits(runtime.memory,Slot.address(runtime.vm.results(),i))!=i)throw new AssertionError("Lost result "+i);
          runtime.vm.execute(compiler.compile("local function empty(){}local a,b,c=empty();return a,b,c"));
          for(int i=0;i<3;i++)if(Slot.type(runtime.memory,Slot.address(runtime.vm.results(),i))!=Type.VOID)throw new AssertionError("Missing result must remain void");
        }
      }
    }
    System.out.println("ELX call windows: discarded/scalar/multiple, named/spread and sixteen results passed");
  }
}
