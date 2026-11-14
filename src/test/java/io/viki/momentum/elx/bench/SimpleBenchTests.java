package io.viki.elx.bench;

import io.viki.elx.bench.*;
import io.viki.elx.compile.Compiler;
import io.viki.elx.memory.Slot;

/** Small correctness runs; actual benchmark main methods retain their full configured sizes. */
public final class SimpleBenchTests {
  private SimpleBenchTests() { }
  public static void run(){
    for(boolean nativeMemory:new boolean[]{false,true})try(Bench.Runtime runtime=new Bench.Runtime(nativeMemory)){
      check(runtime, Fib28Bench.CODE.replace("$N","10"),Fib28Bench.javaRun(10));
      check(runtime, LoopBench.CODE.replace("$N","1000"),LoopBench.javaRun(1000));
      check(runtime, TableBench.CODE.replace("$N","32"),TableBench.javaRun(32));
      check(runtime, ObjectBench.CODE.replace("$N","32"),ObjectBench.javaRun(32));
    }
    System.out.println("ELX simple benchmarks: four Java references matched on both backends");
  }
  private static void check(Bench.Runtime runtime,String source,long expected){
    int count=runtime.vm.execute(new Compiler().compile(source));
    long actual=Slot.bits(runtime.memory,runtime.vm.results());if(count!=1 || actual!=expected)throw new AssertionError(actual+" != "+expected+"\n"+source);
    runtime.collect();
  }
}
