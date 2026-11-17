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

package io.viki.elx.lex;

import io.viki.elx.SyntaxException;

/**
 * Thread-confined source scanner. It skips comments and continuation escapes,
 * emits separators for statement boundaries, and produces source spans without
 * decoding literal values. {@link Lexer} performs numeric and string decoding
 * after tokenization.
 */
public final class Tokenizer {
  private final String source;
  private final boolean preserveNewlines;
  private boolean inBlockComment;
  private int position, line = 1, column = 1;

  /**
   * Creates a scanner positioned at the beginning of {@code source}.
   *
   * @param source source text to scan
   */
  public Tokenizer(String source) {
    this.source = source;
    preserveNewlines = false;
  }

  /**
   * Creates a scanner with optional physical-line separator tokens.
   *
   * @param source           source text to scan
   * @param preserveNewlines whether each physical line break is returned as {@link Token#SEP}
   */
  public Tokenizer(String source, boolean preserveNewlines) {
    this.source = source;
    this.preserveNewlines = preserveNewlines;
  }

  Tokenizer(String source, Token.Span start) {
    this.source = source;
    preserveNewlines = false;
    position = start.start();
    line = start.line();
    column = start.column();
  }

  private static boolean identifierStart(char c) {
    return Character.isUnicodeIdentifierStart(c) || c == '_' || c == '$' || c == '#';
  }

  private static boolean identifierPart(char c) {
    return Character.isUnicodeIdentifierPart(c) || c == '$' || c == '#';
  }

  /**
   * Scans and consumes the next token, skipping comments and insignificant
   * whitespace. Newline and semicolon boundaries are returned as {@link Token#SEP}
   * so the parser can distinguish statement termination.
   *
   * @return next token span, including {@link Token#EOF} at end of input
   * @throws SyntaxException for malformed literals, strings, comments, or characters
   */
  public Token.Span next() {
    skip();
    int start = position, startLine = line, startColumn = column;
    if (position == source.length()) {
      return span(Token.EOF, start, startLine, startColumn);
    }
    char c = source.charAt(position);
    if (c == ';' || preserveNewlines && (c == '\n' || c == '\r')) {
      advance();
      if (c == '\r' && position < source.length() && source.charAt(position) == '\n') {
        advance();
      }
      return span(Token.SEP, start, startLine, startColumn);
    }
    if (Character.isDigit(c) || ((c == 'b' || c == 'h') && peek(1) == '\'')) {
      NumericLiteral number;
      try {
        number = NumericLiteral.scan(source, position);
      } catch (IllegalArgumentException error) {
        throw new SyntaxException(error.getMessage(), span(Token.ERROR, start, startLine, startColumn));
      }
      while (position < number.end()) {
        advance();
      }
      return span(number.kind(), start, startLine, startColumn);
    }
    if (c == '\'' || c == '"') {
      advance();
      boolean closed = false;
      while (position < source.length()) {
        char value = advance();
        if (value == c) {
          closed = true;
          break;
        }
        if (value == '\n' || value == '\r') {
          throw new SyntaxException("Unescaped newline in string", span(Token.ERROR, start, startLine, startColumn));
        }
        if (value == '\\' && position < source.length()) {
          char escaped = advance();
          if (escaped == '\n' || escaped == '\r') {
            throw new SyntaxException("Unescaped newline in string", span(Token.ERROR, start, startLine, startColumn));
          }
        }
      }
      if (!closed) {
        throw new SyntaxException("Unterminated string", span(Token.ERROR, start, startLine, startColumn));
      }
      return span(Token.STRING, start, startLine, startColumn);
    }
    if (identifierStart(c)) {
      do {
        advance();
      } while (position < source.length() && identifierPart(source.charAt(position)));
      Token kind = Token.FIXED.getOrDefault(source.substring(start, position), Token.IDENTIFIER);
      return span(kind, start, startLine, startColumn);
    }
    Token kind = Token.matchFixed(source, position);
    if (kind == Token.NONE) {
      advance();
      throw new SyntaxException("Unexpected character '" + c + "'", span(Token.ERROR, start, startLine, startColumn));
    }
    for (int i = 0; i < kind.spelling().length(); i++) {
      advance();
    }
    return span(kind, start, startLine, startColumn);
  }

  private void skip() {
    for (; ; ) {
      while (position < source.length() && Character.isWhitespace(source.charAt(position))
          && !(preserveNewlines && (peek(0) == '\n' || peek(0) == '\r'))) {
        advance();
      }
      if (inBlockComment) {
        while (position < source.length() && !(preserveNewlines && (peek(0) == '\n' || peek(0) == '\r'))
            && !(peek(0) == '*' && peek(1) == '/')) {
          advance();
        }
        if (position < source.length() && peek(0) == '*' && peek(1) == '/') {
          advance();
          advance();
          inBlockComment = false;
          continue;
        }
        if (position == source.length()) {
          throw new SyntaxException("Unterminated block comment", span(Token.ERROR, position, line, column));
        }
        return;
      }
      if (peek(0) == '\\' && (peek(1) == '\n' || peek(1) == '\r')) {
        advance();
        if (advance() == '\r' && peek(0) == '\n') {
          advance();
        }
        continue;
      }
      if (peek(0) == '/' && peek(1) == '/') {
        while (position < source.length() && peek(0) != '\n' && peek(0) != '\r') {
          advance();
        }
      } else if (peek(0) == '/' && peek(1) == '*') {
        int start = position, startLine = line, startColumn = column;
        advance();
        advance();
        inBlockComment = true;
        while (position < source.length() && !(peek(0) == '*' && peek(1) == '/')) {
          if (preserveNewlines && (peek(0) == '\n' || peek(0) == '\r')) {
            return;
          }
          advance();
        }
        if (position == source.length()) {
          throw new SyntaxException("Unterminated block comment", span(Token.ERROR, start, startLine, startColumn));
        }
        advance();
        advance();
        inBlockComment = false;
      } else {
        return;
      }
    }
  }

  private Token.Span span(Token kind, int start, int line, int column) {
    return new Token.Span(kind, start, position, line, column);
  }

  private char peek(int offset) {
    int at = position + offset;
    return at < source.length() ? source.charAt(at) : '\0';
  }

  private char advance() {
    char c = source.charAt(position++);
    if (c == '\r') {
      line++;
      column = 1;
    } else if (c == '\n') {
      if (position < 2 || source.charAt(position - 2) != '\r') {
        line++;
      }
      column = 1;
    } else {
      column++;
    }
    return c;
  }
}
