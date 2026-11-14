/*
 * MIT License
 *
 * Copyright (c) 2026 Licphel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.viki.elx.bench;

import io.viki.elx.asm.Decomposer;
import io.viki.elx.asm.Optimizer;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Slot;

import java.util.Locale;

/** Same editable source through raw/O0/O1; measures actual execution, not only instruction counts. */
final class OptimizationBench {
  static final int ITERATIONS = 100_000;
  static final String CODE = """
      local total=0l
      local abs = require("math").abs
      T = {}
      local t = {}
      for i=0l,i<$Nl,i++ {
      total+=i
      {}.x += abs(total<<i)+"STR:Name1"
      if i < 10000 {
        T[str(i)]=total
      } else {
        t[str(i)]=total
      }
      }
      return total
      """;
  private static final int WARMUP = 0, ROUNDS = 5;
  private static volatile long blackhole;

  private OptimizationBench() {
  }

  public static void main(String[] args) {
    try {
      Prototype raw = new Compiler().compileRaw("optimization-bench", CODE.replace("$N", Integer.toString(ITERATIONS)), Library.EMPTY);
      measure("raw", raw);
      measure("O1", Optimizer.optimize(raw));
      measure("O0", Decomposer.lower(raw));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void measure(String name, Prototype code) {
    long expected = (long) ITERATIONS * (ITERATIONS - 1) / 2;
    try (Bench.Runtime r = new Bench.Runtime(false)) {
      r.vm.load(code);
      for (int i = -WARMUP; i < ROUNDS; i++) {
        long before = System.nanoTime();
        int count = r.vm.execute(code);
        long elapsed = System.nanoTime() - before;
        blackhole = Slot.bits(r.memory, r.vm.results());
        if (count != 1 || blackhole != expected) {
          throw new AssertionError("Wrong " + name + " result");
        }
        if (i >= 0) {
          System.out.printf(Locale.ROOT, "%s %d: %.3fms | %d instructions | %d words%n", name, i + 1, elapsed / 1e6, r.vm.instructions(), code.wordCount());
        }
      }
    }
  }
}
