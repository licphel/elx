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

/** Recursive Fibonacci. Edit N/CODE and run main directly. */
final class Fib28Bench {
  static final int N = 28;
  static final String CODE = """
      function fib(n) {
        if n <= 2 { return 1 }
        return fib(n-2) + fib(n-1)
      }
      local values = [$N]
      return fib(*values)
      """;
  private static final int WARMUP = 0, ROUNDS = 5;

  private Fib28Bench() {
  }

  public static void main(String[] args) {
    Bench.run("fib(" + N + ")", CODE.replace("$N", Integer.toString(N)), () -> javaRun(N), WARMUP, ROUNDS);
  }

  static long javaRun(int n) {
    return n <= 2 ? 1 : javaRun(n - 2) + javaRun(n - 1);
  }
}
