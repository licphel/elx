package io.viki.elx.bench;

import io.viki.elx.SyntaxException;
import io.viki.elx.Type;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.bench.Bench;
import io.viki.elx.compile.Compiler;
import io.viki.elx.compile.IncludeResolver;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Slot;
import io.viki.elx.vm.ScriptTrace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Expansion, include/condition behavior, source locations and len on both memory backends. */
public final class PreprocessorTests {
  private PreprocessorTests() { }

  public static void main(String[] args) throws IOException { run(); }

  public static void run() throws IOException {
    String source;
    try (var input = PreprocessorTests.class.getResourceAsStream("/elx/macros.elx")) {
      if (input == null) throw new IOException("Missing /elx/macros.elx");
      source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    Compiler compiler = new Compiler();
    for (boolean nativeMemory : new boolean[] {false, true}) {
        try (Bench.Runtime runtime = new Bench.Runtime(nativeMemory)) {
          runtime.vm.execute(compiler.compile("macros.elx", source, Library.EMPTY));
          check(Slot.type(runtime.memory, runtime.vm.results()) == Type.BOOL
              && Slot.bits(runtime.memory, runtime.vm.results()) == 1, "macro suite result");
          runtime.vm.execute(compiler.compile("line.elx", "#define LINE LINECOUNT\nlocal n=LINE;\nreturn n,LINECOUNT;", Library.EMPTY));
          check(Slot.bits(runtime.memory, runtime.vm.results()) == 2, "macro LINECOUNT call site");
          check(Slot.bits(runtime.memory, runtime.vm.results() + Slot.SIZE) == 3, "direct LINECOUNT");
          runtime.vm.execute(compiler.compile("#define X 1\nlocal a=X;\n#define X 2\nreturn a,X;", Library.EMPTY));
          check(Slot.bits(runtime.memory, runtime.vm.results()) == 1
              && Slot.bits(runtime.memory, runtime.vm.results() + Slot.SIZE) == 2, "definition order");
          runtime.vm.execute(compiler.compile("local list=[1];local a=len(list);list[1]=2;return a,len(list);", Library.EMPTY));
          check(Slot.bits(runtime.memory, runtime.vm.results()) == 1
              && Slot.bits(runtime.memory, runtime.vm.results() + Slot.SIZE) == 2, "len observes mutation");
          runtime.vm.execute(compiler.compile("underscore.elx", "local _private=8;return _private;", Library.EMPTY));
          check(Slot.bits(runtime.memory, runtime.vm.results()) == 8, "underscore identifier");
          try {
            runtime.vm.execute(compiler.compile("failure.elx", "#define BAD(x) (1 / x)\n\nreturn BAD(0);", Library.EMPTY));
            throw new AssertionError("Expected division failure");
          } catch (ArithmeticException failure) {
            boolean correct = false;
            for (Throwable extra : failure.getSuppressed()) if (extra instanceof ScriptTrace) {
              for (StackTraceElement frame : extra.getStackTrace()) {
                if ("failure.elx".equals(frame.getFileName()) && frame.getLineNumber() == 3) correct = true;
              }
            }
            check(correct, "macro runtime error lost its source line");
          }
        }
    }
    for (String valid : new String[] {
        "local _x=1;return _x;", "_x=1;return _x;", "function _f() {return 1};return _f();",
        "function f(_x) {return _x};return f(3);", "local t={_x=4};return t._x;",
        "#define _M 1\nreturn _M;",
    }) {
      try (Bench.Runtime runtime = new Bench.Runtime(false)) {
        runtime.vm.execute(compiler.compile(valid));
      }
    }
    for (String invalid : new String[] {
        "#define F(a,a) a\nreturn F(1,2);", "#define F(a...,b) a\nreturn 1;",
        "#define F(a,b) a\nreturn F(1);", "#define F(a) a\nreturn F(1,2);",
        "#define F(a) a\nreturn F([1,2);", "#define FILENAME 'x'\nreturn 1;",
        "function f(args...,last) {}", "function f(args...=1) {}", "function f(*args) {}",
        "return len();", "return len(1,2);"
    }) {
      try { compiler.compile(invalid); throw new AssertionError("Expected compilation failure: " + invalid); }
      catch (SyntaxException expected) { }
    }
    String malformed = "#define BROKEN(x) (x + )\n\nreturn BROKEN(3);";
    try {
      compiler.compile(malformed);
      throw new AssertionError("Expected macro syntax failure");
    } catch (SyntaxException failure) {
      check(failure.span().line() == 3, "macro syntax error lost its source line");
      check(failure.span().start() == malformed.lastIndexOf("BROKEN"), "macro syntax error lost its source offset");
    }
    IncludeResolver resolver = (including, requested) -> switch (requested) {
      case "defs.elx" -> new IncludeResolver.Source("virtual/defs.elx", "#define FEATURE 1\n#pragma once\n#include \"nested.elx\"\n");
      case "nested.elx" -> new IncludeResolver.Source("virtual/nested.elx", "#define NESTED 6\n");
      default -> throw new java.io.IOException("No virtual include " + requested);
    };
    Compiler included = new Compiler(resolver);
    try (Bench.Runtime runtime = new Bench.Runtime(false)) {
      runtime.vm.execute(included.compile("virtual/main.elx", """
          #include "defs.elx"
          #include "defs.elx"
          #if defined(FEATURE) && NESTED * 2 == 12
          local result = NESTED
          #elif FEATURE == 0
          local result = -1
          #else
          local result = -2
          #endif
          #ifndef DISABLED
          return result
          #else
          return 0
          #endif
          """, Library.EMPTY));
      check(Slot.getInt(runtime.memory, runtime.vm.results()) == 6, "include guards and conditionals");
    }
    try {
      included.compile("virtual/main.elx", "#include \"missing.elx\"\nreturn 1", Library.EMPTY);
      throw new AssertionError("Expected missing include failure");
    } catch (SyntaxException failure) {
      check(failure.getMessage().contains("virtual/main.elx"), "include resolution error lost source location");
    }
    try {
      compiler.compile("nested.elx", "#if 1\n#if 0\n#endif", Library.EMPTY);
      throw new AssertionError("Expected unmatched conditional failure");
    } catch (SyntaxException failure) {
      check(failure.getMessage().contains("nested.elx"), "conditional error lost source location");
    }
    System.out.println("ELX preprocessing: macro, conditional, include, source-map and underscore checks passed");
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
