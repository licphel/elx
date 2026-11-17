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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Nested tables/lists, numeric and string keys, updates, merge, iteration and clearing. */
final class TableBench {
  static final int ITERATIONS = 10_000;
  static final String CODE = """
      local rows={}
      for i=0l, i<$Nl, i++ {
        rows[i]={x=i,y=i*3l,data=[i,i+1l]}
      }
      local total=0l
      for k,row in itr(rows) {
        row.x+=row.data[1]
        row.data[0]++
        local merged=row+{bonus=k%7l}
        total+=merged.x+merged.y+merged.data[0]+merged.bonus
      }
      for i=0l, i<$Nl, i+=3l { rows[i]=null }
      return total
      """;
  private static final int WARMUP = 0, ROUNDS = 5;

  private TableBench() {
  }

  public static void main(String[] args) {
    Bench.run("complex table " + ITERATIONS, CODE.replace("$N", Integer.toString(ITERATIONS)), () -> javaRun(ITERATIONS), WARMUP, ROUNDS);
  }

  static long javaRun(int n) {
    Map<Long, Map<String, Object>> rows = new HashMap<>();
    for (long i = 0; i < n; i++) {
      Map<String, Object> row = new HashMap<>();
      row.put("x", i);
      row.put("y", i * 3);
      row.put("data", new ArrayList<>(List.of(i, i + 1)));
      rows.put(i, row);
    }
    long total = 0;
    for (var entry : rows.entrySet()) {
      Map<String, Object> row = entry.getValue();
      @SuppressWarnings("unchecked") List<Long> data = (List<Long>) row.get("data");
      row.put("x", (Long) row.get("x") + data.get(1));
      data.set(0, data.get(0) + 1);
      Map<String, Object> merged = new HashMap<>(row);
      merged.put("bonus", entry.getKey() % 7);
      total += (Long) merged.get("x") + (Long) merged.get("y") + data.get(0) + (Long) merged.get("bonus");
    }
    for (long i = 0; i < n; i += 3) {
      rows.put(i, null);
    }
    return total;
  }
}
