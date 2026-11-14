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

import java.util.List;

/**
 * Immutable source-level function signature. Parameter names and optionality
 * are retained for call validation and diagnostics; defaults are represented by
 * bytecode prologues rather than Java value objects. A variadic final parameter
 * receives the remaining arguments according to the ELX calling convention.
 *
 * @param names parameter names in declaration order
 * @param optional whether each parameter may be omitted
 * @param variadic whether the final parameter collects remaining arguments
 */
public record Parameters(List<String> names, List<Boolean> optional, boolean variadic) {
  /**
   * Creates a parameter signature.
   *
   * @param names parameter names in declaration order
   * @param optional whether each parameter may be omitted
   * @param variadic whether the final parameter collects remaining arguments
   */
  public Parameters {
    names = List.copyOf(names);
    optional = List.copyOf(optional);
    if (names.stream().anyMatch(String::isEmpty) || new java.util.HashSet<>(names).size() != names.size()) {
      throw new IllegalArgumentException("Empty or duplicate parameter name");
    }
    if (names.size() != optional.size() || variadic && names.isEmpty()) {
      throw new IllegalArgumentException("Invalid parameter signature");
    }
  }

  /**
     * Returns the number of parameters that are not the variadic collector.
     *
     * @return fixed parameter count
   */
  public int fixed() {
    return names.size() - (variadic ? 1 : 0);
  }
}
