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

import io.viki.elx.Type;

/**
 * Scalar library entry storing a runtime type tag and raw 64-bit payload.
 * Only numeric and null values are valid; strings, functions, and external
 * callbacks use their dedicated entry types.
 *
 * @param type scalar runtime type
 * @param bits raw value bits in the representation required by {@code type}
 */
public record LE_Bits(Type type, long bits) implements LibraryEntry {
  /**
   * Validates the type.
   *
   * @param type scalar runtime type
   * @param bits raw value bits in the representation required by {@code type}
   */
  public LE_Bits {
    if (!type.numeric() && type != Type.NULL) {
      throw new IllegalArgumentException("Library scalar must be numeric/null; use text or function definitions");
    }
  }
}
