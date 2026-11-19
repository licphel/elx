package io.viki.elx.bench;

import io.viki.elx.Type;
import io.viki.elx.asm.Optimization;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.ModuleResolver;
import io.viki.elx.memory.PinMode;
import io.viki.elx.memory.Slot;
import io.viki.elx.vm.ScriptTrace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Integrated language/lifetime checks on both backends, independently runnable. */
public final class CapabilitiesTests {
  private static final Compiler COMPILER=new Compiler();
  private static int checks;
  private static Optimization level;
  private static Prototype compile(String source) { return COMPILER.compile(source,Library.EMPTY,level); }
  private CapabilitiesTests() {}
  public static void main(String[] args) throws IOException { run(); }
  public static void run() throws IOException {
    for(var selected: new Optimization[]{Optimization.O1}) {
    level=selected;
    for(boolean nativeMemory:new boolean[]{false,true}) {
      try(Bench.Runtime r=new Bench.Runtime(nativeMemory)) {
        expect(r,"local x=1; local p=&x; *p=7; return x,*p",7,7);
        r.vm.execute(compile("local function dangling(x) { return &x } return dangling(3)"));
        check(Slot.type(r.memory,r.vm.results())==Type.PTR,"address result type");
        check(r.heap.isAlive(Slot.bits(r.memory,r.vm.results())),"managed local pointer handle was not allocated");
        r.vm.collect(true); // The handle survives as a result; dereferencing it still rejects its returned frame.
        expect(r,"local gc=require('gc');local x={v=42};local p=&x;gc.collect();return (*p).v",42);
        expect(r,"local x=1;local p=&x;local function f() { *p=5;return 1 } return *p+f(),x",2,5);
        expect(r,"local total=0;for v in itr([3,4,5]) { local p=&v;total+=*p } return total",12);
        expect(r,"local function f(x) { local p=&f; if x==0 { return 0 } return (*p)(x-1)+1 } return f(3)",3);
        expect(r,"local x=1; if false { local p=&x } x+=2; return x",3);
        expect(r,"local x=1; for i=0,i<4,i++ { local p=&x; *p+=i } return x",7);
        expect(r,"local function make(x) { local heap=[x];return &heap[0] } local p=make(3); *p+=4; return *p",7);
        expect(r,"local x=3;local state=[x];local function capture() { local p=&state[0]; *p+=1;return *p } return capture(),capture(),x",4,5,3);
        expect(r,"local xs=[1];local p=&xs[0];for i=1,i<100,i++ { xs[i]=i } *p=42;return xs[0],*p",42,42);
        expect(r,"local t={x=1};local p=&t.x;for i=0,i<100,i++ { t[i]=i } *p=42;return t.x,*p",42,42);
        expect(r,"local f=function(x) { return x+1 }; local p=&f; return (*p)(4)",5);
        expect(r,"local x=1;local p=&x;local q=&p;*(*q)=9;return x",9);
        expect(r,"local function f(a,b=3,c=4) { return a+b+c } return f(1),f(1,c=5),f(c=6,a=2)",8,9,11);
        expect(r,"local function f(a,b=a+2) { return b } return f(5)",7);
        expect(r,"local function f(a,b=3,c=4) { return a+b+c } return f(*[1,2],c=5)",8);
        expect(r,"order=0;local function mark(x) { order=order*10+x;return x } local function f(a,b,c) { return a+b+c } local n=f(c=mark(3),a=mark(1),b=mark(2));return n,order",6,312);
        expect(r,"local function f(value={}) { return value } local a=f();local b=f();return referenceequal(a,b)",0);
        expect(r,"local function f(cb=function(a,b) { if a>0 { return a,b } return 0,0 }) { local a,b=cb(2,3);return a+b } return f()",5);
        expect(r,"local t={};setmetatable(t,{call=function(self,a,b=2) { return a+b }});return t(a=3)",5);
        expect(r,"local function f(a=3) { return a==null } return f(null),f()",1,0);
        expect(r,"local function f(a,rest...) { local b,c=*rest;return a+b+c } return f(1,2,3)",6);
        expect(r,"local function f(a,b=3,rest...) { return a+b+len(rest) } return f(1),f(*[2],b=4)",4,6);
        expect(r,"local function f(rest...) { return len(rest) } return f(),f(1,2,3)",0,3);
        fails(r,"local function f(a) { return a } return f()", "Missing argument");
        fails(r,"local function f(a) { return a } return f(1,a=2)", "Duplicate argument");
        fails(r,"local function f(a) { return a } return f(b=2)", "Unknown named argument");
        fails(r,"local function f(a) { return a } return f(a=2,1)", "Positional argument");
        expect(r,"persistent=7; function add_global(x) { persistent+=x;return persistent } return add_global(2)",9);
        expect(r,"return persistent,add_global(3)",9,12);
        expect(r,"local persistent=2;return persistent",2);
        expect(r,"local p=&persistent;*p=20;return persistent",20);
        AtomicInteger loads=new AtomicInteger();
        r.vm.modules().addHandler(name->{
          if(name.equals("test:cached")){loads.incrementAndGet();return new ModuleResolver.Source(name,"return {value=42}");}
          if(name.equals("test:cycle"))return new ModuleResolver.Source(name,"return require('test:cycle')");
          return null;
        });
        expect(r,"local a=require('test:cached');local b=require('test:cached');return a.value,referenceequal(a,b)",42,1);
        check(loads.get()==1,"require did not cache");
        r.vm.modules().unload("test:cached");
        expect(r,"return require('test:cached').value",42);check(loads.get()==2,"require did not reload");
        moduleNameCache(r);
        fails(r,"return require('test:cycle')","Circular require");
        fails(r,"return require('test:cycle')","Circular require");
        expect(r,"local math=require('math');return long(math.pow(2,8)),long(math.floor(2.9))",256,2);
        expect(r,"local sys=require('sys');sys.env.hello=17;return hello,require('table').remove(sys.env,'hello'),sys.env.hello==void",17,1,1);
        expect(r,"env.answer=41;env.answer+=1;return env.answer,answer",42,42);
        expect(r,"local util=require('util');return util.decodeBase64(util.encodeBase64('hello'))=='hello'",1);
        expect(r,"local str=require('str');return len('a中'),str.codeAt('a中',1),str.toUpper('ab')=='AB'",2,20013,1);
        StringBuilder output=new StringBuilder();r.vm.output(output::append);
        expect(r,"require('fmt').printf('%s','hello');print(' world');return 1",1);
        check(output.toString().equals("hello world"+System.lineSeparator()),"print redirection");
        Path folder=Files.createTempDirectory("elx-module-test");
        try {
          Files.writeString(folder.resolve("example.elx"),"return 23");r.vm.modules().directory(folder);
          expect(r,"return require('example')",23);
        } finally {Files.delete(folder.resolve("example.elx"));Files.delete(folder);}
        benchmark(r, PointerBench.CODE,30,PointerBench.javaRun(30));
        benchmark(r, GlobalsLibraryBench.CODE,30,GlobalsLibraryBench.javaRun(30));
        r.vm.gcInterval(512);long before=r.vm.collections();
        benchmark(r, GcPressureBench.CODE,300,GcPressureBench.javaRun(300));
        check(r.vm.collections()>before,"automatic GC did not run");
        int live=r.heap.liveCount();
        benchmark(r,GcPressureBench.CODE,300,GcPressureBench.javaRun(300));
        r.collect();check(r.heap.liveCount()<live+200,"garbage retained without bound");
        try {
          r.vm.execute(COMPILER.compile("broken.elx","local function bad() {\n local z=0\n return 1/z\n}\nreturn bad()",Library.EMPTY,level));
          throw new AssertionError("Expected arithmetic failure");
        } catch(ArithmeticException error) {
          boolean found=false;
          for(Throwable trace:error.getSuppressed())if(trace instanceof ScriptTrace)for(StackTraceElement frame:trace.getStackTrace())if("broken.elx".equals(frame.getFileName())&&frame.getLineNumber()==3)found=true;
          if (!found) throw new AssertionError("script source location missing", error);
          check(found,"script source location missing");
        }
      }
      try(Bench.Runtime r=new Bench.Runtime(nativeMemory)) {
        Prototype code=compile("return function(x) { return x+1 }");
        r.vm.execute(code);long function=Slot.bits(r.memory,r.vm.results());
        try(var pin=r.heap.pin(function, PinMode.STRONG)) {
          r.vm.unload(code);r.collect();check(r.heap.isAlive(function),"pinned function unloaded");
          long arg=r.heap.arena().allocate(Slot.SIZE);
          try{Slot.write(r.memory,arg,Type.INT,4);r.vm.execute(function,arg,1);check(Slot.bits(r.memory,r.vm.results())==5,"unloaded pinned code not executable");}
          finally{r.heap.arena().free(arg);}
        }
        r.collect();check(!r.heap.isAlive(function),"unloaded image not collected");
      }
    }
    }
    System.out.println("ELX capabilities: "+checks+" checks passed across both backends");
  }
  private static void moduleNameCache(Bench.Runtime r) {
    AtomicInteger loads=new AtomicInteger();
    r.vm.modules().addHandler(name -> {
      if(!name.equals("test:hot"))return null;
      loads.incrementAndGet();return new ModuleResolver.Source(name,"return {value=17}");
    });
    long result=r.heap.arena().allocate(Slot.SIZE);
    try {
      long first=r.heap.importString("test:hot");
      r.vm.modules().require(first,result);
      long module=Slot.bits(r.memory,result);
      r.collect();
      check(r.heap.isAlive(first),"hot module name lost its GC root");
      r.vm.modules().require(first,result);
      check(Slot.bits(r.memory,result)==module && loads.get()==1,"hot module cache changed identity");
      long second=r.heap.importString("test:hot");
      r.vm.modules().require(second,result);
      r.collect();
      check(!r.heap.isAlive(first),"hot module cache retains previous temporary strings");
      check(r.heap.isAlive(second),"replacement module name not rooted");
      r.vm.modules().unload("test:hot");
      r.collect();
      check(!r.heap.isAlive(second),"unload retained cached name");
      long reloaded=r.heap.importString("test:hot");
      r.vm.modules().require(reloaded,result);
      check(loads.get()==2,"unload did not invalidate hot module entry");
      Prototype caller=compile("require('math');return 0");
      r.vm.execute(caller);
      long function=r.vm.load(caller);
      r.vm.unload(caller);r.collect();
      check(!r.heap.isAlive(function),"module name cache kept unloaded caller code alive");
    } finally { r.heap.arena().free(result);r.vm.modules().unload("test:hot"); }
  }
  private static void benchmark(Bench.Runtime r,String source,int n,long expected){expect(r,source.replace("$N",Integer.toString(n)),expected);}
  private static void expect(Bench.Runtime r,String source,long...expected){
    int n=r.vm.execute(compile(source));check(n==expected.length,"result count for "+source);
    for(int i=0;i<n;i++)check(Slot.bits(r.memory,Slot.address(r.vm.results(),i))==expected[i],"wrong result "+i+" for "+source);
  }
  private static void fails(Bench.Runtime r,String source,String message){try{r.vm.execute(compile(source));throw new AssertionError("Expected "+message);}catch(RuntimeException error){check(error.getMessage()!=null&&error.getMessage().contains(message),"Unexpected failure: "+error);}}
  private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
