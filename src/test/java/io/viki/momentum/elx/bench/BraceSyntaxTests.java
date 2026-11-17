package io.viki.elx.bench;

import io.viki.elx.SyntaxException;
import io.viki.elx.asm.Optimization;
import io.viki.elx.asm.Prototype;
import io.viki.elx.bench.Bench;
import io.viki.elx.bench.OptimizationBench;
import io.viki.elx.lex.Lexer;
import io.viki.elx.lex.Token;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Slot;

/** Braced bodies, table/block ambiguity, default functions and the edited OptimizationBench source. */
public final class BraceSyntaxTests {
  private static final Compiler COMPILER=new Compiler();
  private static int checks;
  private BraceSyntaxTests() {}
  public static void run() {
    // Compile the actual user-edited benchmark, then execute a small version hitting both branches.
    COMPILER.compileRaw("OptimizationBench", OptimizationBench.CODE.replace("$N",Integer.toString(OptimizationBench.ITERATIONS)),Library.EMPTY);
    for(boolean nativeMemory:new boolean[]{false,true})for(Optimization mode:new Optimization[]{Optimization.O1})try(Bench.Runtime r=new Bench.Runtime(nativeMemory)) {
      expect(r,mode,OptimizationBench.CODE.replace("$N","40").replace("10000","20"),780);
      expect(r,mode,"local f=function(a={x=[1,2]},b=function(x,y=3){return x+y},c=4){return b(a.x[1])+c};return f()",9);
      expect(r,mode,"function make(x){return {x=x,f=function(self){return self.x}}} return make(7):f()",7);
      expect(r,mode,"local x=1;{local x=2;{local y=3;}} return x",1);
      expect(r,mode,"if false {} else if true {local x={};} else {} while false {} for i=0,i<2,i++ {} for v in itr([]) {} return 3",3);
      expect(r,mode,"local f=function(){value=7;};f();return value",7);
      expect(r,mode,"local f=function(){return};local x=f();return x==void",1);
      expect(r,mode,"local x=0;{x=1}.x+=2;{}.x=3;{x=4;}return x",4);
      expect(r,mode,"local f=function(){return {x={value=8}}};return f().x.value",8);
      expect(r,mode,"return 'do end {} function(x)'=='do end {} function(x)'",1);
    }
    for(String source:new String[]{"function f() return 1", "function f(){ return {x=1}","if true { return 1", "if true do return 1 end","while true do break end","if true {} else {", "local f=function() {{return 1}"}) {
      try {COMPILER.compile(source);throw new AssertionError("Expected invalid block: "+source);}
      catch(SyntaxException expected) {checks++;}
    }
    try {COMPILER.compile("local x=1\n{ x=2\n  @\n}");throw new AssertionError("Expected invalid character");}
    catch(SyntaxException expected) {check(expected.span().line()==3,"lookahead lost the original line");}
    Lexer lexer=new Lexer("local x=1\n local y=2");
    while(lexer.current().line()==1)lexer.advance();
    Lexer copy=new Lexer(lexer);
    check(copy.current().line()==2&&copy.current().start()==lexer.current().start(),"fork location");
    copy.advance();check(lexer.current().kind()== Token.LOCAL,"lookahead changed parent state");
    System.out.println("ELX brace syntax: "+checks+" checks passed");
  }
  private static void expect(Bench.Runtime r,Optimization mode,String source,long expected) {
    Prototype code=COMPILER.compile("braces.elx",source,Library.EMPTY,mode);
    int count=r.vm.execute(code);
    check(count==1&&Slot.bits(r.memory,r.vm.results())==expected,"wrong braced-program result: "+source);
  }
  private static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
}
