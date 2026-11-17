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
import java.util.List;

/** Explicit class factories, captured parent method and virtual method calls; no prototype chain. */
final class ObjectBench {
  static final int ITERATIONS = 5_000;
  static final String CODE = """
      local T={}
      setmetatable(T,{call=function(self,x) {
        return {class=self,x=x,foo=function(self) { return self.x }}
      }})
      local S={parent=T}
      setmetatable(S,{call=function(self,x) {
        local p=T(x)
        p.class=self
        p.x=x*2l
        local parentFoo=p.foo
        p.foo=function(self) { return parentFoo(self)+1l }
        return p
      }})
      local instances=[]
      for i=0l, i<$Nl, i++ { instances[i]=S(i) }
      local total=0l
      for p in itr(instances) { total+=p:foo() }
      return total
      """;
  private static final int WARMUP = 0, ROUNDS = 5;

  private ObjectBench() {
  }

  public static void main(String[] args) {
    Bench.run("metatable objects " + ITERATIONS, CODE.replace("$N", Integer.toString(ITERATIONS)), () -> javaRun(ITERATIONS), WARMUP, ROUNDS);
  }

  static long javaRun(int n) {
    List<Base> objects = new ArrayList<>();
    for (long i = 0; i < n; i++) {
      objects.add(new Derived(i));
    }
    long total = 0;
    for (Base object : objects) {
      total += object.foo();
    }
    return total;
  }

  private static class Base {
    final long x;

    Base(long x) {
      this.x = x;
    }

    long foo() {
      return x;
    }
  }

  private static final class Derived extends Base {
    Derived(long x) {
      super(x * 2);
    }

    @Override
    long foo() {
      return super.foo() + 1;
    }
  }
}
