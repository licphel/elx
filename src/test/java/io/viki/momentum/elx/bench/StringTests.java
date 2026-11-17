package io.viki.elx.bench;

import io.viki.elx.asm.Optimization;
import io.viki.elx.bench.Bench;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Slot;

/** Language-level string behavior remains equivalent across optimization modes and backends. */
public final class StringTests {
  private StringTests() {}
  public static void run(){
    String[] programs={
      "return ('中'+18446744073709551615ul)=='中18446744073709551615'",
      "return ('x'+null)=='xnull' && (true+'x')=='truex'",
      "return str(0.1)=='0.1' && str(-0.0)=='-0.0'",
      "local s=require('str');return len('a中😀')==3 && s.codeAt('a中😀',2)==128512",
      "return len('Hello你好')==7 && len('a中😀')==3 && len('')==0",
      "return len('😀😀')==2 && len('a'+'中'+'😀')==3",
      "local s=require('str');return s.charAt('a中😀',1)=='中' && s.charAt('a中😀',2)=='😀'",
      "return len('é')==2 && len('é')==1",
      "local s=require('str');return s.toUpper('ß')=='SS' && s.toLower('ΟΣ')=='ος'",
      "return require('str').sub('a中😀z',1,3)=='中😀'",
      "local u=require('util');return u.decodeBase64(u.encodeBase64('a中😀'))=='a中😀'",
      "return ('a'+'b')=='ab' && ('a'+'b')!=('a'+'c')"
    };
    int checks=0;
    for(boolean nativeMemory:new boolean[]{false,true})for(Optimization level:new Optimization[]{Optimization.O1})try(Bench.Runtime r=new Bench.Runtime(nativeMemory)){
      for(String source:programs){int count=r.vm.execute(new Compiler().compile(source,Library.EMPTY,level));if(count!=1||Slot.bits(r.memory,r.vm.results())!=1)throw new AssertionError(source);checks++;}
    }
    System.out.println("ELX strings: "+checks+" checks passed across modes/backends");
  }
}
