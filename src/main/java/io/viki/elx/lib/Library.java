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

package io.viki.elx.lib;

import org.jspecify.annotations.Nullable;

/**
 * Compile-time library of named definitions. The compiler resolves names to
 * {@link Symbol} values before bytecode execution; the VM then uses the linked
 * table and does not perform string lookups on the hot path. Implementations are
 * mutable while being assembled and become shareable after {@link #freeze()}.
 */
public interface Library {
  Library EMPTY = new SimpleLibrary().freeze();

  /**
   * Resolves a source name to a library symbol.
   *
   * @param name source-level definition name
   * @return symbol, or {@code null} when no definition exists
   */
  @Nullable Symbol resolve(String name);

  /**
   * Returns the number of definitions.
   *
   * @return number of definitions
   */
  int size();

  /**
   * Returns the stable definition name at an index.
   *
   * @param index definition index
   * @return registered name
   */
  String name(int index);

  /**
   * Returns the definition payload at an index.
   *
   * @param index definition index
   * @return library entry
   */
  LibraryEntry entry(int index);

  /**
   * Freezes this definition table for linking and cross-runtime sharing.
   *
   * @return immutable/frozen view, usually this instance
   */
  Library freeze();
}
