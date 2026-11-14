package io.viki.elx.bench;

import io.viki.elx.SyntaxException;
import io.viki.elx.Type;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.asm.Optimization;
import io.viki.elx.bench.Bench;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Slot;
import io.viki.elx.vm.ScriptTrace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Expansion, source locations, privacy and len under both ISA levels and memory backends. */
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
    for (Optimization mode : Optimization.values()) {
      for (boolean nativeMemory : new boolean[] {false, true}) {
        try (Bench.Runtime runtime = new Bench.Runtime(nativeMemory)) {
          runtime.vm.execute(compiler.compile("macros.elx", source, Library.EMPTY, mode));
          check(Slot.type(runtime.memory, runtime.vm.results()) == Type.BOOL
              && Slot.bits(runtime.memory, runtime.vm.results()) == 1, "macro suite result");
          runtime.vm.execute(compiler.compile("line.elx", "#define LINE LINECOUNT\nlocal n=LINE;\nreturn n,LINECOUNT;", Library.EMPTY, mode));
          check(Slot.bits(runtime.memory, runtime.vm.results()) == 2, "macro LINECOUNT call site");
          check(Slot.bits(runtime.memory, runtime.vm.results() + Slot.SIZE) == 3, "direct LINECOUNT");
          runtime.vm.execute(compiler.compile("#define X 1\nlocal a=X;\n#define X 2\nreturn a,X;", Library.EMPTY, mode));
          check(Slot.bits(runtime.memory, runtime.vm.results()) == 1
              && Slot.bits(runtime.memory, runtime.vm.results() + Slot.SIZE) == 2, "definition order");
          runtime.vm.execute(compiler.compile("local list=[1];local a=len(list);list[1]=2;return a,len(list);", Library.EMPTY, mode));
          check(Slot.bits(runtime.memory, runtime.vm.results()) == 1
              && Slot.bits(runtime.memory, runtime.vm.results() + Slot.SIZE) == 2, "len observes mutation");
          var internal = compiler.compileInternal("generated", "_private=8;return _private;", Library.EMPTY);
          runtime.vm.execute(internal);
          runtime.vm.execute(compiler.compile("return sys.env['_private'];", Library.EMPTY, mode));
          check(Slot.bits(runtime.memory, runtime.vm.results()) == 8, "no runtime name restriction");
          try {
            runtime.vm.execute(compiler.compile("failure.elx", "#define BAD(x) (1 / x)\n\nreturn BAD(0);", Library.EMPTY, mode));
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
    }
    for (String invalid : new String[] {
        "local _x=1;", "_x=1;", "return _x;", "function _f() {}", "function f(_x) {}",
        "local t={};return t._x;", "#define _M 1\nreturn 1;",
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
    var raw = compiler.compileRaw("len.elx", "local t={};return len(t);", Library.EMPTY);
    boolean inline = false;
    for (int pc = 0; pc < raw.wordCount();) {
      Op op = Instr.op(raw.word(pc));
      if (op == Op.CX_LEN) inline = true;
      pc += op.format().words();
    }
    check(inline, "len was not emitted as an instruction");
    System.out.println("ELX macros/variadics/len/privacy: both ISA levels and memory backends passed");
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
