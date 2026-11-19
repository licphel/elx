package io.viki.elx.bench;

import io.viki.elx.SyntaxException;
import io.viki.elx.Type;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.vm.Slots;
import io.viki.elx.asm.Optimizer;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Slot;

/** Decorators share ordinary argument binding and declaration/expression semantics. */
public final class DecoratorTests {
  private DecoratorTests() {}
  public static void main(String[] args) { run(); }
  public static void run() {
    String wrap="local function wrap(fn,a=1,b=2,rest...){assert(type(fn)=='function');local n=a+b;"
        +"for v in itr(rest){n+=v;}return function(x){return fn(x)+n;};}";
    String repeat = """
        local state={calls=0};
        function decora(fn,times) {
            return function(args...) {
                for i=0,i<times,i++ { fn(*args); }
            }
        }
        @decora(3)
        @decora(2)
        function printme(me) {
            assert(me=="I've been printed 6 times");
            state.calls++;
            print(me);
        }
        printme("I've been printed 6 times");
        return state.calls;
        """;
    String[] programs={
        repeat,
        wrap+"@wrap local function f(x){return x;}return f(1);",
        wrap+"@wrap() local function f(x){return x;}return f(1);",
        wrap+"@wrap(2,b=4) function f(x){return x;}return f(1);",
        wrap+"@wrap(b=4,a=2) local function f(x){return x;}return f(1);",
        wrap+"@wrap(**{b=4,a=2}) local function f(x){return x;}return f(1);",
        wrap+"@wrap(*[2,4],*[6,8]) local function f(x){return x;}return f(1);",
        wrap+"local f=@wrap(2,4) function(x){return x;};return f(1);",
        wrap+"local t={};@wrap(2,4) function t.f(x){return x;}return t.f(1);",
        "local function method(fn){return function(self,x){return fn(self,x)+1;};}local t={n=4};"
            +"@method function t:f(x){return self.n+x;}return t:f(2);",
        "local function d(fn){return function(){return fn()+1;};}local answer=0;"
            +"{@d local function d(){return 5;}answer=d();}return answer;",
        "local state={log=''};local function arg(n){state.log+=str(n);return n;}"
            +"local function d(fn,n){state.log+=str(n);return fn;}"
            +"@d(arg(1)) @d(arg(2)) local function f(){return 7;}assert(state.log=='1221');return f();",
        "local function d(fn,n){return function(){return fn()*10+n;};}"
            +"@d(1) @d(2) local function f(){return 0;}return f();",
        wrap+"local ns={wrap=wrap};@ns.wrap(2,4) local function f(x){return x;}return f(1);",
        "local function d(fn){return fn;}@d local function fact(n){if n<=1{return 1;}return n*fact(n-1);}return fact(5);",
        "local function d(fn){return fn;}local fact=@d function(n){if n<=1{return 1;}return n*fact(n-1);};return fact(5);",
        "local function d(fn){return function(){return fn()+1;};}local answer=0;{local d=@d function(){return 5;};answer=d();}return answer;",
        "local function d(fn){return fn;}@d local function outer(n){@d local function inner(x){return x+n;}return inner(2);}return outer(5);"
    };
    long[] expected={6,4,4,7,7,7,21,7,7,7,6,7,21,7,120,120,6,7};
    Compiler compiler=new Compiler();
    for(boolean nativeMemory:new boolean[]{false,true}) {
      for(int i=0;i<programs.length;i++) {
        var raw=compiler.compileRaw("decorators.elx",programs[i],Library.EMPTY);
        for(var image:new io.viki.elx.asm.Prototype[]{raw,Optimizer.optimize(raw)}) {
          try(Bench.Runtime runtime=new Bench.Runtime(nativeMemory)) {
            int count=runtime.vm.execute(image);
            if(count!=1 || Slot.bits(runtime.memory,runtime.vm.results())!=expected[i])
              throw new AssertionError("Decorator case "+i+": "+programs[i]);
          }
        }
      }
    }
    SimpleLibrary nativeLibrary = new SimpleLibrary().function("nativeWrap", call -> {
      call.arity(3);
      if (call.type(0) != Type.FUNCTION || call.i(1) != 7 || call.i(2) != 9)
        throw new AssertionError("Native decorator arguments");
      Slots.vm(call).collect(true);
      call.rarg(0);
    });
    for (boolean nativeMemory : new boolean[]{false,true}) {
      try (Bench.Runtime runtime = new Bench.Runtime(nativeMemory)) {
        var image = compiler.compile("native function nativeWrap(fn, rest...);"
            + "@nativeWrap(*[7,9]) local function f(){return 6;}return f();",nativeLibrary);
        runtime.vm.execute(image);
        if (Slot.bits(runtime.memory,runtime.vm.results()) != 6) throw new AssertionError("Native decorator/GC");
      }
    }
    for(String bad:new String[]{"@", "@1 function f(){}", "@d local x=1;", "@d return 1;",
        "@d(a=1,2) function f(){}", "@d(**{},*[1]) function f(){}"}) {
      try {compiler.compile(bad);throw new AssertionError("Accepted invalid decorator: "+bad);}
      catch(SyntaxException expectedFailure) { }
    }
    System.out.println("ELX decorators: declarations, expressions, nesting, order, named/spread/variadic parameters passed across both backends");
  }
}
