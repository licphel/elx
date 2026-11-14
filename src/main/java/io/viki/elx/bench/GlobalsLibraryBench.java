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

/** Persistent global reads/writes, linked library calls and cached require lookups. */
final class GlobalsLibraryBench {
  static final int ITERATIONS = 100_000;
  static final String CODE = """
      bench_total=0.0
      local math=require('math')
      local function square(x) { return math.pow(x,2.0) }
      for i=0l,i<$Nl,i++ {
        local cached=require('math')
        bench_total+=cached.floor(square(double(i%17l)))
      }
      return long(bench_total)
      """;
  private static final int WARMUP = 0, ROUNDS = 5;

  private GlobalsLibraryBench() {
  }

  public static void main(String[] args) {
    Bench.run("globals/library " + ITERATIONS, CODE.replace("$N", Integer.toString(ITERATIONS)), () -> javaRun(ITERATIONS), WARMUP, ROUNDS);
  }

  static long javaRun(int n) {
    double total = 0;
    for (long i = 0; i < n; i++) {
      total += Math.floor(Math.pow((double) (i % 17), 2));
    }
    return (long) total;
  }
}
