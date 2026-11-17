package io.viki.elx.bench;

import io.viki.elx.Type;
import io.viki.elx.asm.*;
import io.viki.elx.bench.Bench;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Slot;
import java.util.*;

/** Unoptimized/raw/O1 execution equivalence, bytecode shape and semantic barriers. */
public final class OptimizationTests {
  private static final Compiler COMPILER=new Compiler();
  private static int checks;
  private OptimizationTests() {}
  public static void main(String[] args){run();}
  public static void run(){
    List<String> programs=new ArrayList<>(List.of(
      "local function less(a,b) { if a<b { return 1 } else { return 2 } }; return less(1,2),less(2,1),less(0.0/0.0,1.0),less(1.0,0.0/0.0)",
      "local count=0;local t={};setmetatable(t,{['lt']=function(a,b) { count+=1;return true }});local function less(a,b) { if a<b { return 1 } else { return 2 } }; return less(t,2),count",
      "local n=0;local function f(a,b) { if a==b { n+=1 } if a!=b { n+=2 } return n }; return f(2,2),f(2,3)",
      "local t={x=3};local function f(t) {return t.x }; return f(t),f({x=7})",
      "env.answer=42;setmetatable(env,{get=function(self,key){return getrawattr(self,key)}});return answer",
      "local t={};setmetatable(t,{get=function(self,k) { local gc=require('gc');gc.collect();return k }});return t.field,t['other']",
      "local t={x=1};local p=&t;local function change() { *p={x=7};return 0 }; change();return t.x",
      "function f() {return 1 }; local old=f;f=function() {return 2 }; return f(),old()",
      "local t={};setmetatable(t,{['add']=function(self,rhs) { return rhs*3 },['sub']=function(self,rhs) {return rhs*2 }});local function f(x) {return x+3,x-2 }; return f(t)",
      "local function f(x) {return x+3.0,x-2l }; return f(2.5)",
      "local value=0;{ local x=5;value=x;{ local x=9;value+=x } }; return value",
      "local x=1;local p=&x;local y=x;*p=5;return x,y,*p",
      "local x=1;local p=&x;local function change() { *p=9;return 2 }; local y=x+change();return y,x",
      "local x=1;local p=&x;local function change() { *p=9;return 2 }; local function f(a,b) {return a+b }; return f(x,change()),x",
      "local x=4;if x>1 { x='hi' } else { x=9 } return x",
      "local x=2;local y=3;if false { x=99 } return x+y",
      "local total=0;for i=0,i<50,i++ { if i%3==0 { continue } total+=i;if total>300 { break } } return total",
      "local n=0;for i=0,i<5,i++ { for j=0,j<7,j++ { n+=i+j } } return n",
      "local function fib(n) {if n<2 { return n }; return fib(n-1)+fib(n-2) }; return fib(12)",
      "local function f(a,b=3,tail...) {local n=a+b;for v in itr(tail) { n+=v }; return n }; return f(1,2,3,4),f(b=4,a=2)",
      "local function f(a,b=2) { return a+b } local t=[3,4];return f(*t),*[1,2]",
      "local function f(rest...) {return len(rest) }; return f(1,*[2,3],4)",
      "local x=2;local p=&x;local t={};setmetatable(t,{['add']=function(a,b) { *p=7;return 12 }});local v=t+1;return x,v",
      "local t={x=3};setmetatable(t,{get=function(self,k) {return getrawattr(self,k)+1 },set=function(self,k,v) {setrawattr(self,k,v*2)}});t.x=8;return t.x",
      "local a={};local b={};setmetatable(a,{['eq']=function(x,y) {return true }});return a==b,a!=b",
      "local n=0;local t={};setmetatable(t,{call=function(self,a,b=2) {return a+b }});return t(a=3)",
      "local s='a'+'b';return s,referenceequal(s,'ab'),str(0.1)+'!',str(18446744073709551615ul)",
      "local a=[1,2];local b=[2,3];return len(a+b),len(a-b),len(a^b),len(a&b)",
      "global_count=2;local sys=require('sys');sys.env.global_count+=3;return global_count,require('table').remove(sys.env,'global_count')",
      "local strlib=require('str');return strlib.toLower('ΟΣ'),strlib.toUpper('ß'),strlib.sub('a中😀z',1,3)",
      "local m=require('math');local x=m.sqrt(-1.0);return x==x,x!=x,-0.0,1.0/-0.0",
      "local a=32767s;local b=1s;return short(a+b),bool(2),~true,18446744073709551615ul>0ul",
      "local count=0;while count<30 { count+=1 } return count",
      "local t=[1,2,3];local sum=0;local i=0;for v in itr(t) { sum+=i+v;i++ } return sum",
      "local xs=[];for i=0,i<20,i++ { xs[i]=i } local p=&xs[0];*p=20;return xs[0]",
      "local t={x=2};local gc=require('gc');local p=gc.pin(&t.x);gc.collect();*p=7;gc.unpin(p);return t.x"
    ));
    for(String relation:List.of("==","!=","<","<=",">",">=")) {
      for(String values:List.of("1,2","2,1","2,2","1.0,0.0/0.0","0.0/0.0,1.0","1,1.0","18446744073709551615ul,1ul","'a','b'")) {
        programs.add("local function f(a,b) { if a"+relation+"b { return 1 } else { return 2 } }; return f("+values+")");
      }
      String hook=switch(relation){case "=="->"eq";case "!="->"ne";case "<"->"lt";case "<="->"le";case ">"->"gt";default->"ge";};
      programs.add("local t={};setmetatable(t,{['"+hook+"']=function(a,b){return false }});local function f(a,b){if a"+relation+"b { return 1 } else { return 2 } }; return f(t,t)");
    }
    long state=7;
    for(int i=0;i<80;i++){
      state^=state<<13;state^=state>>>7;state^=state<<17;int a=(int)state,b=(int)(state>>>32)|1;
      programs.add("local a="+a+";local b="+b+";return (a+b)*(a-b),(a^b)&(a|b),a/b,a%b");
    }
    for(boolean nativeMemory:new boolean[]{false,true})for(String source:programs){
      Prototype raw=COMPILER.compileRaw("equivalence.elx",source,Library.EMPTY),o1=Optimizer.optimize(raw);
      String expected=execute(raw,nativeMemory);
      same(expected,execute(o1,nativeMemory),"O1: "+source);
      same(source,raw.sourceName().equals("equivalence.elx")?source:"metadata lost","source identity");
    }
    for(String source:List.of("local z=0;return 1/z","local x=1;return !x","local function f() {return 1 }; return f(missing=2)","local t={};setmetatable(t,{['eq']=function(a,b) {return 3 }});return t=={}")){
      Prototype raw=COMPILER.compileRaw("failure.elx",source,Library.EMPTY);
      String expected=failure(raw);same(expected,failure(Optimizer.optimize(raw)),"O1 failure");
    }
    var raw=COMPILER.compileRaw("loop","local n=0;for i=0,i<1000,i++ { n+=i } return n",Library.EMPTY);
    var optimized=Optimizer.optimize(raw);
    check(optimized.wordCount()<raw.wordCount(),"O1 did not reduce loop bytecode");
    try(Bench.Runtime r=new Bench.Runtime(false)){
      r.vm.execute(raw);long before=r.vm.instructions();r.vm.execute(optimized);
      check(r.vm.instructions()<before,"O1 did not reduce executed instructions");
      System.out.println("ELX O1 loop: "+before+" -> "+r.vm.instructions()+" instructions");
    }
    var keyed=Optimizer.optimize(COMPILER.compileRaw("member-shape","local t={x=3};return t.x",Library.EMPTY));
    check(has(keyed,Op.GETFIELD),"constant member lookup was not fused");
    var branched=Optimizer.optimize(COMPILER.compileRaw("branch-shape","local function f(a,b) { if a<b { return 1 }; return 2 }; return f(1,2)",Library.EMPTY));
    check(has(branched,Op.BR_COMPARE),"dynamic comparison branch was not fused");
    var constants=Optimizer.optimize(COMPILER.compileRaw("constant-shape","function f(x) {return x+3,x-2 }; return f(7)",Library.EMPTY));
    check(has(constants,Op.GETGLOBAL),"global key lookup not fused");
    check(has(constants,Op.ADDK)&&has(constants,Op.SUBK),"dynamic constant arithmetic not fused");
    System.out.println("ELX raw/O1: "+checks+" equivalence/shape checks passed");
  }
  private static boolean has(Prototype image,Op expected) {
    for(int pc=0;pc<image.wordCount();) { Op op=Instr.op(image.word(pc));if(op==expected)return true;pc+=op.format().words(); }
    for(int i=0;i<image.functionCount();i++)if(has(image.function(i),expected))return true;
    return false;
  }
  private static String execute(Prototype p,boolean nativeMemory){try(Bench.Runtime r=new Bench.Runtime(nativeMemory)){
    r.vm.gcInterval(4096);int n=r.vm.execute(p);StringBuilder out=new StringBuilder();
    for(int i=0;i<n;i++){long cell=Slot.address(r.vm.results(),i);Type type=Slot.type(r.memory,cell);out.append(type).append(':').append(Slot.flags(r.memory,cell)).append(':');if(type==Type.STRING)out.append(r.heap.exportString(Slot.bits(r.memory,cell)));else out.append(Slot.bits(r.memory,cell));out.append(';');}return out.toString();
  }}
  private static String failure(Prototype p){try(Bench.Runtime r=new Bench.Runtime(false)){try{r.vm.execute(p);throw new AssertionError("Expected failure");}catch(RuntimeException error){return error.getClass().getName()+":"+error.getMessage();}}}
  private static void same(String a,String b,String message){check(a.equals(b),message+"\nexpected "+a+"\nactual "+b);}
  private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
