package io.viki.elx.bench;

import io.viki.elx.SyntaxException;
import io.viki.elx.compile.Compiler;
import io.viki.elx.memory.Slot;

/** Captured slots, escaped pointers, and dereference/update evaluation order. */
public final class PointerClosureTests {
  private static final Compiler COMPILER = new Compiler();
  private static int checks;

  private PointerClosureTests() {}

  public static void main(String[] args) { run(); }

  public static void run() {
    for (boolean nativeMemory : new boolean[]{false, true}) {
      try (Bench.Runtime runtime = new Bench.Runtime(nativeMemory)) {
        escapedLocalPointer(runtime, false);
        escapedLocalPointer(runtime, true);
        expect(runtime, "local function make(){local v=0;return function(){local vptr=&v;return (*vptr)++;}};"
            + "local f=make();return f(),f(),f()", 0, 1, 2);
        expect(runtime, "local function make(){local v=0;return function(){local p=&v;"
            + "require('gc').collect();return ++(*p);}};local f=make();return f(),f()", 1, 2);
        expect(runtime, "local function make(){local v=41;return function(){return &v;}};"
            + "local f=make();local p=f();f=void;require('gc').collect();return (*p)++,*p", 41, 42);
        expect(runtime, "local function make(){local v=7;return function(){return &v;}};"
            + "local a=make();local b=make();local p=a();local q=a();local r=b();"
            + "*p=11;return *q,*r", 11, 7);
        expect(runtime, "local v=0;local f=function(){local p=&v;return (*p)++;};"
            + "v=10;return f(),f(),v", 0, 1, 10);
        expect(runtime, "local v=1;local p=&v;local f=function(){return (*p)++;};"
            + "return f(),f(),v", 1, 2, 3);
        expect(runtime, "local function make(){local v=0;return function(){return function(){"
            + "local p=&v;return (*p)++;};};};local outer=make();local inner=outer();"
            + "outer=void;require('gc').collect();return inner(),inner()", 0, 1);
        expect(runtime, "local v=5;local p=&v;return (*p)++,++(*p),(*p)--,--(*p),v", 5, 7, 7, 5, 5);
        expect(runtime, "local box={v=3};local calls={n=0};local function ptr(){"
            + "calls.n++;return &box.v;};local old=(*ptr())++;return old,box.v,calls.n", 3, 4, 1);
        expect(runtime, "local function make(){local box={v=2};return &box.v;};"
            + "local p=make();require('gc').collect();return (*p)++,*p", 2, 3);
        failure(runtime, IllegalStateException.class, "local function make(){local v=0;return &v;};"
            + "local p=make();require('gc').collect();return *p", "returned local frame");
        failure(runtime, IllegalStateException.class, "local function make(){local v=0;local p=&v;"
            + "return function(){return (*p)++;};};local f=make();return f()", "returned local frame");
        failure(runtime, IllegalArgumentException.class, "local v=0;local f=function(){"
            + "local vptr=&v;return (*v)++;};return f()", "dereference");
        failure(runtime, SyntaxException.class, "local v=0;return &(v+1)", "assign");
        failure(runtime, SyntaxException.class, "return &1", "assign");
        checks++;
        if (runtime.registers.depth() != 0) throw new AssertionError("Pointer failure leaked a register frame");
      }
    }
    System.out.println("ELX pointer/closure boundaries: " + checks + " checks passed across both backends");
  }

  private static void expect(Bench.Runtime runtime, String source, long... expected) {
    int count = runtime.vm.execute(COMPILER.compile(source));
    checks++;
    if (count != expected.length) throw new AssertionError("Result count: " + source);
    for (int i = 0; i < count; i++) {
      checks++;
      long actual = Slot.bits(runtime.memory, Slot.address(runtime.vm.results(), i));
      if (actual != expected[i]) throw new AssertionError(actual + " != " + expected[i] + ": " + source);
    }
  }

  private static void escapedLocalPointer(Bench.Runtime runtime, boolean collect) {
    int count = runtime.vm.execute(COMPILER.compile(
        "local v=0;local vptr=&v;return function(){return (*vptr)++;}"));
    checks++;
    if (count != 1) throw new AssertionError("Expected the escaped closure");
    long closure = Slot.bits(runtime.memory, runtime.vm.results());
    if (collect) runtime.vm.collect(true);
    checks++;
    try {
      runtime.vm.execute(closure, 0, 0);
    } catch (IllegalStateException failure) {
      if (failure.getMessage().contains("returned local frame")) return;
      throw new AssertionError("Unexpected escaped-pointer failure", failure);
    }
    throw new AssertionError("Capturing a pointer must not extend its local frame's lifetime");
  }

  private static void failure(Bench.Runtime runtime, Class<? extends Throwable> type, String source, String message) {
    checks++;
    try {
      runtime.vm.execute(COMPILER.compile(source));
    } catch (RuntimeException failure) {
      if (type.isInstance(failure) && failure.getMessage().toLowerCase().contains(message.toLowerCase())) return;
      throw new AssertionError("Unexpected failure: " + source, failure);
    }
    throw new AssertionError("Expected " + type.getSimpleName() + ": " + source);
  }
}
