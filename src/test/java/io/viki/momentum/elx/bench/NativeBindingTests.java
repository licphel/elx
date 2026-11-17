package io.viki.elx.bench;

import io.viki.elx.SyntaxException;
import io.viki.elx.Type;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.ModuleResolver;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.memory.Slot;
import io.viki.elx.memory.PinMode;
import io.viki.elx.vm.Slots;

/** Native declarations resolve to linked symbols without runtime namespace tables. */
public final class NativeBindingTests {
  private NativeBindingTests() {}
  public static void main(String[] args) { run(); }

  public static void run() {
    Compiler compiler = new Compiler();
    SimpleLibrary library = new SimpleLibrary()
        .function("host.plus", call -> { call.arity(2); call.rl(call.i(0) + call.i(1)); })
        .constant("answer", Type.INT, 42).text("label", "native");
    String source = """
        native function host.plus(a: int, b: int) -> long;
        native answer: int;
        native label: string;
        local function nested(x) { return host.plus(x, answer); }
        return nested(2), label;
        """;
    var image = compiler.compile("native-bindings.elx", source, library);
    var direct = compiler.compile("native function host.plus(a,b);return host.plus(1,2)", library);
    for (int pc = 0; pc < direct.wordCount();) {
      Op op = Instr.op(direct.word(pc));
      if (op == Op.GETGLOBAL || op == Op.GETENV || op == Op.GETTABLE || op == Op.GETFIELD)
        throw new AssertionError("Native call emitted runtime name/table lookup: " + op);
      pc += op.format().words();
    }
    for (boolean nativeMemory : new boolean[]{false, true}) {
      try (Bench.Runtime runtime = new Bench.Runtime(nativeMemory)) {
        runtime.vm.execute(image);
        if (Slot.bits(runtime.memory, runtime.vm.results()) != 44
            || !runtime.heap.exportString(Slot.bits(runtime.memory, runtime.vm.results() + Slot.SIZE)).equals("native"))
          throw new AssertionError("Native function/value binding failed");
        runtime.vm.collect(true);
        runtime.vm.execute(direct);
        if (Slot.bits(runtime.memory, runtime.vm.results()) != 3) throw new AssertionError("Native binding lost after GC");
        runtime.vm.modules().addHandler(name -> name.equals("host:module")
            ? new ModuleResolver.Source(name, source, library) : null);
        runtime.vm.execute(compiler.compile("return require('host:module')"));
        if (Slot.bits(runtime.memory, runtime.vm.results()) != 44) throw new AssertionError("Resolver library was not injected");
        var borrowed = runtime.vm.link(library);
        runtime.vm.modules().unload("host:module");
        if (runtime.vm.link(library) != borrowed) throw new AssertionError("Module unloaded an application-owned library");
        runtime.vm.execute(compiler.compile("return require('path').name"));
        long function = Slot.bits(runtime.memory, runtime.vm.results());
        var empty = compiler.compile("return");
        try (var pin = runtime.heap.pin(function, PinMode.STRONG)) {
          runtime.vm.modules().unload("path");
          runtime.vm.execute(empty);
          runtime.vm.collect(true);
          if (!pin.isAlive()) throw new AssertionError("Pinned native was destroyed by module unload");
          try (var arguments = runtime.vm.borrowSlots(1)) {
            arguments.setString(0, "a/b");
            runtime.vm.execute(function, Slots.address(arguments, 0), 1);
            if (!runtime.heap.exportString(Slot.bits(runtime.memory, runtime.vm.results())).equals("b"))
              throw new AssertionError("Unloaded native was not callable through its pin");
          }
        }
        runtime.vm.execute(empty);
        runtime.vm.collect(true);
        if (runtime.heap.isAlive(function)) throw new AssertionError("Native module retained its unloaded library root");
        StringBuilder output = new StringBuilder();
        runtime.vm.output(output::append);
        runtime.vm.execute(compiler.compile("require('fmt').printf('%04d',7)"));
        if (!output.toString().equals("0007")) throw new AssertionError("Native printf export bound to global print");
      }
    }
    for (String invalid : new String[]{
        "native function missing();", "native missing: int;", "native function answer();",
        "native host.plus;", "native answer;native answer;", "{native answer;}",
        "native function host.missing();", "function f(){native answer;}"
    }) {
      try { compiler.compile(invalid, library); throw new AssertionError("Accepted invalid native declaration: " + invalid); }
      catch (SyntaxException expected) { }
    }
    System.out.println("ELX compile-time natives: bindings, bytecode, resolver injection, unload/pins and failures passed");
  }
}
