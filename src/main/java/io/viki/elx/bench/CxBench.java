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

import io.viki.elx.asm.Optimizer;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Slot;

import java.util.Arrays;
import java.util.Locale;

/** Editable focused comparisons for fused dynamic branches and constant-key member access. */
final class CxBench {
  static final int ITERATIONS = 100_000;
  static final String BRANCH = """
      local function sum(limit) {
        local total=0l
        for i=0l,i<limit,i++ { total+=i }
        return total
      }
      return sum($Nl)
      """;
  static final String MEMBER = """
      local t={x=3l}
      local total=0l
      for i=0l,i<$Nl,i++ { total+=t.x }
      return total
      """;
  private static final int WARMUP = 0, ROUNDS = 5;
  private static volatile long blackhole;

  private CxBench() {
  }

  public static void main(String[] args) {
    run("dynamic branch", BRANCH, (long) ITERATIONS * (ITERATIONS - 1) / 2);
    run("constant member", MEMBER, 3L * ITERATIONS);
  }

  private static void run(String name, String source, long expected) {
    Prototype raw = new Compiler().compileRaw(name, source.replace("$N", Integer.toString(ITERATIONS)), Library.EMPTY);
    measure(name + " raw", raw, expected);
    Prototype o1 = Optimizer.optimize(raw);
    measure(name + " O1", o1, expected);
  }

  private static void measure(String name, Prototype code, long expected) {
    try (Bench.Runtime r = new Bench.Runtime(false)) {
      r.vm.load(code);
      long[] elapsed = new long[ROUNDS];
      for (int i = -WARMUP; i < ROUNDS; i++) {
        long start = System.nanoTime();
        int count = r.vm.execute(code);
        long nanos = System.nanoTime() - start;
        blackhole = Slot.bits(r.memory, r.vm.results());
        if (count != 1 || blackhole != expected) {
          throw new AssertionError(name + " result: " + blackhole);
        }
        if (i >= 0) {
          elapsed[i] = nanos;
        }
      }
      Arrays.sort(elapsed);
      System.out.printf(Locale.ROOT, "%s: median %.3fms | %d instructions%n", name, elapsed[ROUNDS / 2] / 1e6, r.vm.instructions());
    }
  }
}
