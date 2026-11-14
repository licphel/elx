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
 * Immutable call-site argument layout used to expand variadic and named arguments.
 *
 * <p>Each entry in {@link #kinds()} corresponds to the same-position entry in {@link #names()}.
 * Positional and list-spread entries leave the name empty; named and table-spread entries carry
 * the parameter name used by the call binder.
 *
 * @param names parameter names for named arguments, or empty strings for positional arguments
 * @param kinds source kind for each argument position
 */
public record CallSite(List<String> names, List<ArgumentKind> kinds) {
  /**
   * Validates and copies a call-site layout.
   *
   * @param names parameter names aligned with {@code kinds}
   * @param kinds argument source kinds aligned with {@code names}
   * @throws IllegalArgumentException if the lists differ in size or a name does not match its kind
   */
  public CallSite {
    names = List.copyOf(names);
    kinds = List.copyOf(kinds);
    if (names.size() != kinds.size()) {
      throw new IllegalArgumentException("Invalid call site");
    }
    for (int i = 0; i < names.size(); i++) {
      boolean requiresName = kinds.get(i) == ArgumentKind.NAMED;
      if (requiresName == names.get(i).isEmpty()) {
        throw new IllegalArgumentException("Call argument name does not match its kind");
      }
    }
  }

  /**
   * Sources from which a call argument is populated.
   */
  public enum ArgumentKind {
    /** One positional value supplied directly by the caller. */
    POSITIONAL,
    /** One value assigned to a named parameter. */
    NAMED,
    /** Values expanded from a list into positional arguments. */
    LIST_SPREAD,
    /** Entries expanded from a table and matched by parameter name. */
    TABLE_SPREAD
  }
}
