package io.viki.elx.bench;

import io.viki.elx.compile.Compiler;
import io.viki.elx.memory.Slot;

import java.util.Random;

/** Native state stays private to its VM even when public modules are ELX wrappers. */
public final class StandardModuleTests {
  private StandardModuleTests() {}

  public static void run() {
    Compiler compiler = new Compiler();
    var seed = compiler.compile("require('random').seed(17)");
    var draw = compiler.compile("return require('random').rand(-1000,1000)");
    Random expectedFirst = new Random(17), expectedSecond = new Random(17);
    try (Bench.Runtime first = new Bench.Runtime(false); Bench.Runtime second = new Bench.Runtime(true)) {
      first.vm.execute(seed); second.vm.execute(seed);
      for (int i = 0; i < 20; i++) {
        first.vm.execute(draw);
        if (Slot.bits(first.memory, first.vm.results()) != expectedFirst.nextLong(-1000, 1001))
          throw new AssertionError("First VM random state was shared");
        if (i % 3 == 0) {
          second.vm.execute(draw);
          if (Slot.bits(second.memory, second.vm.results()) != expectedSecond.nextLong(-1000, 1001))
            throw new AssertionError("Second VM random state was shared");
        }
      }
    }
    System.out.println("ELX standard modules: independent seeded state passed across both backends");
  }
}
