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

import io.viki.elx.asm.Assembler;
import io.viki.elx.asm.Op;
import io.viki.elx.asm.Optimization;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.*;
import io.viki.elx.memory.*;
import io.viki.elx.vm.VM;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Small timing helper. Edit each benchmark's source and constants, then run its main. */
final class Bench {
  // Change this one constant to compare the FFM storage backend.
  private static final boolean NATIVE_MEMORY = false;
  private static final Optimization OPTIMIZATION = Optimization.O1;
  private static volatile long blackhole;

  private Bench() {
  }

  static void run(String name, String source, LongSupplier javaVersion, int warmup, int rounds) {
    run(name, source, javaVersion, warmup, rounds, runtime -> {
    });
  }

  static void run(String name, String source, LongSupplier javaVersion, int warmup, int rounds, Consumer<Runtime> setup) {
    long before = System.nanoTime();
    Prototype code = new Compiler().compile(source, Library.EMPTY, OPTIMIZATION);
    double compileMs = (System.nanoTime() - before) / 1e6;
    long expected = javaVersion.getAsLong();
    try (Runtime runtime = new Runtime(NATIVE_MEMORY)) {
      setup.accept(runtime);
      before = System.nanoTime();
      runtime.vm.load(code);
      double loadMs = (System.nanoTime() - before) / 1e6;
      System.out.printf(Locale.ROOT, "%n%s | compile %.3fms | load %.3fms | expected %d%n", name, compileMs, loadMs, expected);
      for (int i = 0; i < warmup; i++) {
        execute(runtime, code, expected);
        blackhole = javaVersion.getAsLong();
        runtime.collect();
      }
      for (int i = 0; i < rounds; i++) {
        long collections = runtime.vm.collections(), allocated = runtime.heap.allocatedBytes(), gcNanos = runtime.vm.gcNanos();
        before = System.nanoTime();
        int count = runtime.vm.execute(code);
        long elxNs = System.nanoTime() - before;
        verify(runtime, count, expected);
        long instructions = runtime.vm.instructions();
        before = System.nanoTime();
        long result = javaVersion.getAsLong();
        long javaNs = System.nanoTime() - before;
        if (result != expected) {
          throw new AssertionError("Java result changed: " + result + " != " + expected);
        }
        blackhole = result;
        System.out.printf(Locale.ROOT, "%d: ELX %.3fms | Java %.3fms | %.2fx | %d instructions%n", i + 1, elxNs / 1e6, javaNs / 1e6, (double) elxNs / Math.max(1, javaNs), instructions);
        System.out.printf(Locale.ROOT, "   GC %d (%.3fms) | allocated %.1f KiB | live regions %d%n", runtime.vm.collections() - collections, (runtime.vm.gcNanos() - gcNanos) / 1e6, (runtime.heap.allocatedBytes() - allocated) / 1024.0, runtime.heap.liveCount());
        runtime.collect(); // Outside timing; allocation-heavy cases must not retain previous rounds.
      }
    }
  }

  private static void execute(Runtime runtime, Prototype code, long expected) {
    verify(runtime, runtime.vm.execute(code), expected);
  }

  private static void verify(Runtime runtime, int count, long expected) {
    if (count != 1) {
      throw new AssertionError("Expected one result, got " + count);
    }
    long result = Slot.bits(runtime.memory, runtime.vm.results());
    if (result != expected) {
      throw new AssertionError("ELX result " + result + " != Java result " + expected);
    }
    blackhole = result;
  }

  static final class Runtime implements AutoCloseable {
    final Memory memory;
    final ElxHeap heap;
    final ExtHeap external;
    final JavaRegs registers;
    final VM vm;
    final Prototype collection = new Assembler().r(Op.CX_SAFEPOINT, 0, 0, 0).r(Op.CX_RETURN, 0, 0, 0).build(1, 0);

    Runtime(boolean nativeMemory) {
      memory = nativeMemory ? new NativeMemory(4096) : new JavaMemory(4096);
      Arena arena = new Arena(memory);
      heap = new ElxHeap(arena);
      external = new ExtHeap(heap) {
        @Override
        protected void readField(Object target, long member, long result) {
          throw new UnsupportedOperationException();
        }

        @Override
        protected void writeField(Object target, long member, long value) {
          throw new UnsupportedOperationException();
        }

        @Override
        protected int invoke(Object target, long member, long args, int count, long result, int capacity) {
          throw new UnsupportedOperationException();
        }
      };
      registers = new JavaRegs(arena);
      vm = new VM(heap, external, registers);
      vm.budget(Long.MAX_VALUE);
      vm.load(collection);
    }

    void collect() {
      vm.requestGc(true);
      vm.execute(collection);
    }

    @Override
    public void close() {
      vm.close();
      registers.close();
      external.close();
      heap.close();
      memory.close();
    }
  }
}
