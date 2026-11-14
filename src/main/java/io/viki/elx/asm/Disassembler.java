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

package io.viki.elx.asm;

import java.util.Locale;

/**
 * Deterministic tooling-only disassembly for an immutable {@link Prototype}.
 *
 * <p>The output uses byte offsets, opcode names, and operand formatting supplied by instruction
 * metadata, so it is suitable for diagnostics and regression comparisons rather than reassembly.
 * The operation does not mutate the prototype and is thread-safe.
 */
public final class Disassembler {
  private Disassembler() {
  }

  /**
   * Formats all instructions and prototype metadata as human-readable assembly.
   *
   * @param prototype prototype to disassemble
   * @return deterministic disassembly text
   */
  public static String dump(Prototype prototype) {
    StringBuilder out = new StringBuilder();
    out.append("registers=").append(prototype.registerCount()).append(" parameters=").append(prototype.parameterCount()).append('\n');
    for (int pc = 0; pc < prototype.wordCount(); ) {
      Op op = Instr.op(prototype.word(pc));
      out.append(String.format(Locale.ROOT, "%06x  %-22s ", pc * Instr.WORD_BYTES, op.name()));
      op.append(out, prototype, pc);
      out.append('\n');
      pc += op.format().words();
    }
    return out.toString();
  }
}
