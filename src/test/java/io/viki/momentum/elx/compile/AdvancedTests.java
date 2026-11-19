package io.viki.elx.compile;

import io.viki.elx.asm.Disassembler;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Symbol;
import io.viki.elx.memory.*;
import io.viki.elx.value.ElxTable;

import io.viki.elx.SyntaxException;
import io.viki.elx.Type;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.lib.DynamicLibrary;
import io.viki.elx.vm.VM;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Executable advanced language contracts; no Java value/container is used for script execution. */
public final class AdvancedTests {
  private static final Compiler COMPILER=new Compiler();
  private static int checks,scripts;
  private AdvancedTests() { }
  private static final class Host extends ExtHeap {
    final ElxHeap heap;
    final StringBuilder printed=new StringBuilder();
    @Nullable VM vm;
    long twice;
    Host(ElxHeap heap){super(heap);this.heap=heap;}
    @Override protected void readField(Object target,long member,long result){throw new UnsupportedOperationException();}
    @Override protected void writeField(Object target,long member,long value){throw new UnsupportedOperationException();}
    @Override protected int invoke(Object target,long member,long args,int count,long result,int capacity){
      return nativeInvoke(memberName(member),args,count,result,capacity);
    }
    int nativeInvoke(String name,long args,int count,long result,int capacity){
      switch(name){
        case "print" -> {for(int i=0;i<count;i++){long at= Slot.address(args,i);printed.append(Slot.type(memory(),at)==Type.STRING?heap.exportString(Slot.bits(memory(),at)):Long.toString(Slot.bits(memory(),at)));}return 0;}
        case "gc" -> {VM machine=vm;if(machine==null)throw new IllegalStateException("Host not initialized");machine.requestGc(true);return 0;}
        case "require" -> {
          VM machine=vm;if(machine==null)throw new IllegalStateException("Host not initialized");
          long table= ElxTable.create(machine.heap()),scratch=heap.arena().allocate(2*Slot.SIZE);
          try{Slot.write(memory(),scratch,Type.STRING,heap.importString("twice"));Slot.write(memory(),scratch+Slot.SIZE,Type.FUNCTION,twice);ElxTable.set(machine.heap(), table,scratch,scratch+Slot.SIZE);}
          finally{heap.arena().free(scratch);}
          Slot.write(memory(),result,Type.TABLE,table);return 1;
        }
        default -> throw new IllegalArgumentException("Unknown host entry");
      }
    }
  }
  public static void run(){exercise(new JavaMemory(32));exercise(new NativeMemory(32));System.out.println("ELX advanced syntax: "+scripts+" scripts, "+checks+" checks passed");}
  public static void main(String[] args){run();}
  private static void exercise(Memory memory){
    Arena arena=new Arena(memory);
    try(memory; ElxHeap heap=new ElxHeap(arena); Host host=new Host(heap); JavaRegs regs=new JavaRegs(arena); VM vm=new VM(heap,host,regs)){
      host.vm=vm;
      expect(vm,"local n=0; for i=0,i<6,i++ { if i==2 { continue } if i==5 { break } n+=i } return n",8);
      expect(vm,"local n=0; for i=0,i<3,i++ { for j=0,j<4,j++ { if j==1 { continue } n+=i+j } } return n",24);
      expect(vm,"local n=0; for i=0,i<4,i++ { if i%2==0 { n+=10 } else { n+=1 } } return n",22);
      expect(vm,"local xs=[1,2,3,]; xs[1]+=10; xs[3]=7; return xs[0]+xs[1]+xs[2]+xs[3],len(xs)",23,4);
      expect(vm,"local t={x=1, ['a.~~!-']=2, 'quoted'=3,}; t.x*=4; return t.x,t['a.~~!-'],t.quoted,t.missing",4,2,3,0);
      expect(vm,"local a=[1,2]; local b=[2,3]; return len(a+b),len(a-b),len(a^b),len(a&b)",4,1,2,1);
      expect(vm,"local a=[1]; a*=5; local b={a=1,b=2}; b+={b=3,c=4}; return len(a),b.a,b.b,b.c",5,1,3,4);
      expect(vm,"local a,b=*[1,2]; a,b=b,a; local p=[a,b,3]; local q=p+[4]; return *q",2,1,3,4);
      expect(vm,"local t=[1]; local empty=[]; return len(t), len(empty), t==[1], empty==[]",1,0,1,1);
      expect(vm,"local t=[1,2,3]; local i=1; return t[i], (t+[4])[3], t!=[1,2,4]",2,4,1);
      expect(vm,"local n=0;local i=0; for v in itr([3,4,5]) { n+=i+v;i++ } return n",15);
      expect(vm,"local n=0;local i=0; for v in itr([3,4,5]) { n+=i+v;i++ } return n",15);
      expect(vm,"local n=0; for k,v in itr({a=3,b=4,c=5}) { n+=v } return n",12);
      expect(vm,"local n=0;local i=0; for k,v in itr({a=3,b=4,c=5}) { n+=i+v;i++ } return n",15);
      expect(vm,"local xs=[3]; local i=0; local old=xs[i++]++; return old,xs[0],i",3,4,1);
      expect(vm,"local xs=[1,2]; local i=0; xs[i++]+=(i++); return xs[0],i",2,2);
      expect(vm,"local x=1; local a=++x+x++; local b=--x; return a,b,x",4,2,2);
      expect(vm,"local x=255b; local old=x++; local f=1.5f; --f; return old,x,int(f)",255,0,0);
      expect(vm,"local x=true; x&&=false; x||=true; return x",1);
      expect(vm,"local xs=[1,2,3];local t={x=10,y=20};t.x=30;return len(xs),xs[2],t.x,t.y",3,3,30,20);
      expect(vm,"local t={x=[1,2],y=[3,4]};local a,b=*t.x;return a,b,t.y[0],t.y[1]",1,2,3,4);
      expect(vm,"return len([]),{}=={}",0,1);
      expect(vm,"local e=[];local one=[1];local p=[2,3];return len(e),one[0],*p",0,1,2,3);
      expect(vm,"local f=function(a,b) { return a+b,a*b }; local a,b=f(*[2,3]);return a,b",5,6);
      expect(vm,"local xs=[[1,2],{t=[3,4]}];local a,b=*xs[0];local c,d=*xs[1].t;return a,b,c,d",1,2,3,4);
      expect(vm,"local p=[1,2];p=[3];return *p",3);
      expect(vm,"local t=[[1,2],3];return *t[0]",1,2);
      expect(vm,"local f=function(a,b,c) { return a+b+c };local p=[2,3];return f(1,*p)",6);
      expect(vm,"local f=function(a,b,c,d) { return a+b+c+d };return f(*[1,2],*[3,4])",10);
      expect(vm,"return 1,*[2,3],4",1,2,3,4);
      expect(vm,"return *[]",new long[0]);
      expect(vm,"local f=function(a,b) { return a*10+b }; local p=[2,3]; return f(*p)",23);
      expect(vm,"function f() { return *[1,2,3] } local a,b,c=f(); return a,b,c",1,2,3);
      expect(vm,"function fib(n) { if n<2 { return n } return fib(n-1)+fib(n-2) } return fib(10)",55);
      expect(vm,"local n=0;local state=[n];local f=function() { return state[0]++ }; local a=f(); local b=f(); return a,b,n",0,1,0);
      expect(vm,"function make(n) {local state=[n];return function(x) { state[0]+=x; return state[0] } } local a=make(1); local b=make(10); return a(2),a(3),b(1)",3,6,11);
      expect(vm,"local p=[7,8]; local f=function() { return p[0]+p[1] }; return f()",15);
      expect(vm,"""
          local T={}
          setmetatable(T,{call=function(self,y) {
            return {class=self,x=y,foo=function(self) { return self.x }}
          }})
          local S={parent=T}
          setmetatable(S,{call=function(self,y) {
            local p=T(y); p.class=S; p.x=y*2
            local parentFoo=p.foo
            p.foo=function(self) { return parentFoo(self)+1 }
            return p
          }})
          local a=T(3);local b=S(3)
          return a:foo(),b:foo(),referenceequal(b.class,S),referenceequal(S.parent,T)
          """,3,7,1,1);
      expect(vm,"""
          local t={x=3,reads=0,writes=0}
          setmetatable(t,{
            get=function(self,k) {
              setrawattr(self,'reads',getrawattr(self,'reads')+1)
              return getrawattr(self,k)
            },
            set=function(self,k,v) {
              setrawattr(self,'writes',getrawattr(self,'writes')+1)
              setrawattr(self,k,v)
            }
          })
          local old=t.x++; t.x+=2
          return old,getrawattr(t,'x'),getrawattr(t,'reads'),getrawattr(t,'writes')
          """,3,6,2,2);
      expect(vm,"""
          local t={x=false,n=0}
          setmetatable(t,{set=function(self,k,v) { setrawattr(self,'n',getrawattr(self,'n')+1);setrawattr(self,k,v) }})
          t.x&&=true; t.x||=true; t.x||=false
          return t.x,getrawattr(t,'n')
          """,1,1);
      expect(vm,"local t={};local m={['add']=function(a,b) {return 1 }};setmetatable(t,m);local a=t+2;m['add']=function(a,b) {return 9 };return a,t+2",1,9);
      expect(vm,"local t={};setmetatable(t,{['eq']=function(a,b) {return true }});return t=={},t!={}",1,0);
      expect(vm,"local t={};setmetatable(t,{['ne']=function(a,b) {return true }});return t=={},t!={}",1,1);
      String[] binary={"+","-","*","/","%","&","|","^","<<",">>",">>>"};
      String[] binaryHooks={"add","sub","mul","div","mod","and","or","xor","shl","shr","ushr"};
      for(int i=0;i<binary.length;i++)expect(vm,"local t={};setmetatable(t,{['"+binaryHooks[i]+"']=function(a,b){return 17 }});return t"+binary[i]+"2",17);
      String[] comparisons={"<","<=",">",">=","==","!="},comparisonHooks={"lt","le","gt","ge","eq","ne"};
      for(int i=0;i<comparisons.length;i++)expect(vm,"local t={};setmetatable(t,{['"+comparisonHooks[i]+"']=function(a,b){return false }});return t"+comparisons[i]+"2",0);
      String[] unary={"+","-","~"},unaryHooks={"pos","neg","inv"};
      for(int i=0;i<unary.length;i++)expect(vm,"local t={};setmetatable(t,{['"+unaryHooks[i]+"']=function(a){return 7 }});return "+unary[i]+"t",7);
      expect(vm,"local t={};setmetatable(t,{['not']=function(a) {return true },bool=function(a) {return false }});return !t,bool(t)",1,0);
      expect(vm,"local t={};setmetatable(t,{['inc']=function(a) {return 12 }});local old=t++;return t,old==getrawattr({},'none')",12,0);
      expect(vm,"local t={};local m={call=function(self,x) {return x+1 }};setmetatable(t,m);return t(3),referenceequal(getmetatable(t),m)",4,1);
      expect(vm,"local t={};local m={['add']=function(a,b) {return 5 }};setmetatable(t,m);setmetatable(t,null);return getmetatable(t)==null,t=={}",1,1);
      ProtoCheck(vm);
      library(vm,heap,host);
      String[] invalid={"return (1,2)*2","return ++1","local x=1; return x++++","return 1--","return (*1)"};
      for(String source:invalid)failure(SyntaxException.class,()->COMPILER.compile(source));
      String[] runtime={"return [1][1]","local p=[1,2];return p[2]","local t={}; return t()","setmetatable([], {})","setmetatable({}, {get=2})","local t={};setmetatable(t,{['eq']=function(a,b) {return 1 }});return t=={}","local t={};setmetatable(t,{get=function(self,k) {return self[k] }});return t.x"};
      for(String source:runtime){vm.budget(2000);failure(RuntimeException.class,()->vm.execute(COMPILER.compile(source)));check(regs.depth()==0);}
      vm.budget(100000000);expect(vm,"return 42",42);
    }
  }
  private static void ProtoCheck(VM vm){
    Prototype list=COMPILER.compile("local total=0;for i=0,i<1000,i++ { local p=[i,2];total+=p[0]+p[1] } return total");
    boolean allocated=false;for(int pc=0;pc<list.wordCount();){
      Op op= Instr.op(list.word(pc));allocated|=op==Op.NEWLIST;pc+=op.format().words();}check(allocated);
    vm.execute(list);check(Slot.bits(vm.memory(),vm.results())==501500);scripts++;
  }
  private static void library(VM vm,ElxHeap heap,Host host){
    SimpleLibrary library=new SimpleLibrary();
    library.function("twice",COMPILER.compileFunction("function(x) { return x*2 }"));
    library.function("print",a->{for(int i=0;i<a.size();i++) host.printed.append(a.operations().format(a,i));});
    library.function("gc",a->{vm.requestGc(true);});
    library.function("require",a->{
      var table=a.scratch(1);var pair=a.scratch(2);a.containers().newTable(table,0,1);
      pair.setString(0,"twice");pair.set(1,Type.FUNCTION,host.twice);
      a.containers().tableSet(table,0,pair,0,pair,1);a.rvalue(table,0);
    });
    library.constant("seed",Type.LONG,7);
    DynamicLibrary loaded=vm.link(library);
    Symbol symbol= Objects.requireNonNull(library.resolve("twice"));
    host.twice=Slot.bits(vm.memory(),loaded.entry(symbol.index()));
    Prototype image=COMPILER.compile("local io=require('io');print(io.twice(seed));return twice(4)",library);
    for(int i=0;i<3;i++){vm.execute(image);check(Slot.bits(vm.memory(),vm.results())==8);}
    check(vm.link(library)==loaded);check(host.printed.toString().equals("141414"));scripts+=3;
    check(Disassembler.dump(image).contains("CALL"));
    Prototype shadow=COMPILER.compile("local function twice(x) {return x+1 } return twice(4)",library);vm.execute(shadow);check(Slot.bits(vm.memory(),vm.results())==5);scripts++;
    Prototype collected=COMPILER.compile("local x=[4];local f=function() {return ++x[0] };gc();local t=[];return f(),f()",library);vm.execute(collected);check(Slot.bits(vm.memory(),vm.results())==5);check(Slot.bits(vm.memory(),vm.results()+Slot.SIZE)==6);scripts++;
  }
  private static void expect(VM vm,String source,long... expected){
    Prototype image=COMPILER.compile(source);int count=vm.execute(image);scripts++;
    if(count!=expected.length)throw new AssertionError("Return count for "+source+": "+count);
    for(int i=0;i<count;i++){long at=Slot.address(vm.results(),i);if(Slot.bits(vm.memory(),at)!=expected[i])throw new AssertionError("Result "+i+" = "+Slot.bits(vm.memory(),at)+", expected "+expected[i]+"\n"+source+"\n"+ Disassembler.dump(image));checks++;}
  }
  private static void failure(Class<? extends Throwable> type,Runnable action){checks++;try{action.run();}catch(Throwable error){if(type.isInstance(error))return;throw new AssertionError("Expected "+type,error);}throw new AssertionError("Expected "+type);}
  private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("Advanced check #"+checks);}
}
