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

import io.viki.elx.SyntaxException;
import io.viki.elx.asm.Optimization;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Builtins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs the single resource-based ELX language correctness suite under every VM optimization level. */
final class Correctness {
  private static final String RESOURCE = "/elx/correctness.elx";
  private static final String ERROR_DIRECTIVE = "// @compile-error ";

  private Correctness() {
  }

  public static void main(String[] args) throws IOException {
    String resource = readSuite();
    StringBuilder program = new StringBuilder();
    int syntaxChecks = 0;
    for (String line : resource.lines().toList()) {
      if (line.startsWith(ERROR_DIRECTIVE)) {
        expectSyntaxError(line.substring(ERROR_DIRECTIVE.length()));
        syntaxChecks++;
        program.append('\n');
      } else {
        program.append(line).append('\n');
      }
    }

    for (Optimization optimization : new Optimization[] {Optimization.O1}) {
      Prototype code = new Compiler().compile(RESOURCE, program.toString(), Builtins.standard(), optimization);
      for (boolean nativeMemory : new boolean[] {false, true}) {
        try (Bench.Runtime runtime = new Bench.Runtime(nativeMemory)) {
          runtime.vm.link(Builtins.standard());
          runtime.vm.output(System.out::print);
          runtime.vm.execute(code);
        }
        System.out.println("ELX correctness passed: " + optimization + ", "
            + (nativeMemory ? "native" : "Java") + " memory, " + syntaxChecks + " syntax-error cases");
      }
    }
  }

  private static String readSuite() throws IOException {
    try (InputStream input = Correctness.class.getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IOException("Missing ELX correctness resource " + RESOURCE);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void expectSyntaxError(String source) {
    try {
      new Compiler().compile(source, Builtins.standard(), Optimization.O1);
    } catch (SyntaxException expected) {
      return;
    }
    throw new AssertionError("Expected syntax error for: " + source);
  }
}
