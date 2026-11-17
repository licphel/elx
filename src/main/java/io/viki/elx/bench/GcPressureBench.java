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

import java.util.HashMap;
import java.util.Map;

/** Garbage-heavy allocation with a bounded live ring, heap pointers and library roots. */
final class GcPressureBench {
  static final int ITERATIONS = 100_000;
  static final long GC_INTERVAL = 64 * 1024;
  static final String CODE = """
      local live={}
      local total=0l
      local function make(x) {
        local cell=[x]
        return {pointer=&cell[0], data=[x,x+1l], pair=[x,x+2l]}
      }
      for i=0l,i<$Nl,i++ {
        local row=make(i)
        live[i%32l]=row
        total+=*row.pointer+row.data[1]+row.pair[1]
      }
      local gc=require('gc')
      gc.collect()
      local check=0l
      for k,row in itr(live) { check+=*row.pointer }
      return total+check
      """;
  private static final int WARMUP = 0, ROUNDS = 5;

  private GcPressureBench() {
  }

  public static void main(String[] args) {
    Bench.run("GC pressure " + ITERATIONS, CODE.replace("$N", Integer.toString(ITERATIONS)), () -> javaRun(ITERATIONS), WARMUP, ROUNDS, runtime -> runtime.vm.gcInterval(GC_INTERVAL));
  }

  static long javaRun(int n) {
    long total = 0;
    Object[] live = new Object[32];
    for (long i = 0; i < n; i++) {
      var row = new HashMap<String, Object>();
      row.put("pointer", new long[] {i});
      row.put("data", new long[] {i, i + 1});
      row.put("pair", new long[] {i, i + 2});
      live[(int) (i % 32)] = row;
      total += ((long[]) row.get("pointer"))[0] + ((long[]) row.get("data"))[1] + ((long[]) row.get("pair"))[1];
    }
    for (Object entry : live) {
      if (entry instanceof Map<?, ?> row) {
        total += ((long[]) row.get("pointer"))[0];
      }
    }
    return total;
  }
}
