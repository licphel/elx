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

package io.viki.elx;

import io.viki.elx.lex.Token;

import java.io.Serial;

/**
 * Unchecked syntax diagnostic with the source span that caused compilation to fail.
 *
 * <p>The span preserves the original UTF-16 offsets as well as one-based line and column
 * coordinates, allowing editors and callers to highlight the source without reconstructing the
 * tokenizer's position state.
 *
 * @see Token.Span
 */
public final class SyntaxException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 2026111000L;
  private final Token.Span span;

  /**
   * Creates a syntax diagnostic for a source span.
   *
   * @param message human-readable description of the syntax error
   * @param span source span associated with the error
   */
  public SyntaxException(String message, Token.Span span) {
    super("Line " + span.line() + ", column " + span.column() + ": " + message);
    this.span = span;
  }

  /**
   * Returns the source span associated with this diagnostic.
   *
   * @return source span for the syntax error
   */
  public Token.Span span() {
    return span;
  }
}
