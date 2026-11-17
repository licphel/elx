package io.viki.elx.bench;

import io.viki.elx.Type;
import io.viki.elx.asm.Op;
import io.viki.elx.SyntaxException;
import io.viki.elx.compile.Compiler;
import io.viki.elx.memory.Slot;

/** Void requires explicit conversion at script and host entry points. */
public final class VoidConversionTests {
  private VoidConversionTests() {}
  public static void main(String[] args) { run(); }
  public static void run() {
    Compiler compiler = new Compiler();
    for (boolean nativeMemory : new boolean[]{false, true}) {
      try (Bench.Runtime runtime = new Bench.Runtime(nativeMemory)) {
        runtime.vm.execute(compiler.compile("return int(void),double(void),bool(void),void==void,void==null"));
        Type[] types = {Type.INT, Type.DOUBLE, Type.BOOL, Type.BOOL, Type.BOOL};
        long[] bits = {0, 0, 0, 1, 0};
        for (int i = 0; i < types.length; i++) {
          long slot = Slot.address(runtime.vm.results(), i);
          if (Slot.type(runtime.memory, slot) != types[i] || Slot.bits(runtime.memory, slot) != bits[i])
            throw new AssertionError("Explicit void conversion or identity failed at " + i);
        }
        for (String expression : new String[]{"void+1", "1+void", "-void", "+void", "!void", "~void",
            "void+'x'", "'x'+void", "assert(void)", "require('math').abs(void)"}) {
          rejected(() -> runtime.vm.execute(compiler.compile("return " + expression)));
        }
        rejected(() -> runtime.vm.execute(compiler.compile("if(void){return 1;}return 0")));
        try (var input = runtime.vm.borrowSlots(2); var output = runtime.vm.borrowSlots(1)) {
          input.setVoid(0);
          input.setInt(1, 1);
          rejected(() -> input.i(0));
          rejected(() -> input.d(0));
          rejected(() -> input.b(0));
          rejected(() -> runtime.vm.operations().binary(Op.ADD, input, 0, input, 1, output, 0));
          runtime.vm.operations().convert(Type.INT, input, 0, output, 0);
          if (output.i(0) != 0) throw new AssertionError("Host explicit conversion failed");
        }
      }
    }
    System.out.println("ELX explicit void conversions passed across both backends");
  }
  private static void rejected(Runnable operation) {
    try { operation.run(); }
    catch (IllegalArgumentException | SyntaxException expected) { return; }
    throw new AssertionError("Implicit void conversion was accepted");
  }
}
