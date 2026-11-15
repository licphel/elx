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

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ELX token kinds and their fixed spellings. The enum also publishes immutable
 * lookup maps used by the tokenizer and a prefix matcher for punctuation and
 * operators. Lookup tables are constructed once during class initialization.
 */
public enum Token {
  NONE("<none>", false),
  IDENTIFIER("<identifier>", false),
  UBYTE_LITERAL("<ubyte>", false),
  INT_LITERAL("<int>", false),
  UINT_LITERAL("<uint>", false),
  SHORT_LITERAL("<short>", false),
  USHORT_LITERAL("<ushort>", false),
  LONG_LITERAL("<long>", false),
  ULONG_LITERAL("<ulong>", false),
  FLOAT_LITERAL("<float>", false),
  DOUBLE_LITERAL("<double>", false),
  STRING("<string>", false),
  LEFT_PAREN("(", true),
  RIGHT_PAREN(")", true),
  LEFT_BRACKET("[", true),
  RIGHT_BRACKET("]", true),
  LEFT_BRACE("{", true),
  RIGHT_BRACE("}", true),
  COMMA(",", true),
  DOT(".", true),
  ELLIPSIS("...", true),
  COLON(":", true),
  ARROW("->", true),
  QUESTION("?", true),
  SEP("<separator>", false),
  PLUS("+", true),
  INCREMENT("++", true),
  DECREMENT("--", true),
  MINUS("-", true),
  STAR("*", true),
  STAR_STAR("**", true),
  SLASH("/", true),
  PERCENT("%", true),
  AMP("&", true),
  PIPE("|", true),
  CARET("^", true),
  TILDE("~", true),
  BANG("!", true),
  SHIFT_LEFT("<<", true),
  SHIFT_RIGHT(">>", true),
  SHIFT_UNSIGNED_RIGHT(">>>", true),
  EQUAL("=", true),
  EQUAL_EQUAL("==", true),
  BANG_EQUAL("!=", true),
  LESS("<", true),
  LESS_EQUAL("<=", true),
  GREATER(">", true),
  GREATER_EQUAL(">=", true),
  AND_AND("&&", true),
  OR_OR("||", true),
  AND_AND_EQUAL("&&=", true),
  OR_OR_EQUAL("||=", true),
  PLUS_EQUAL("+=", true),
  MINUS_EQUAL("-=", true),
  STAR_EQUAL("*=", true),
  SLASH_EQUAL("/=", true),
  PERCENT_EQUAL("%=", true),
  AMP_EQUAL("&=", true),
  PIPE_EQUAL("|=", true),
  CARET_EQUAL("^=", true),
  TILDE_EQUAL("~=", true),
  SHIFT_LEFT_EQUAL("<<=", true),
  SHIFT_RIGHT_EQUAL(">>=", true),
  SHIFT_UNSIGNED_RIGHT_EQUAL(">>>=", true),
  LOCAL("local", true, true),
  NATIVE("native", true, true),
  FUNCTION("function", true, true),
  RETURN("return", true, true),
  IF("if", true, true),
  ELSE("else", true, true),
  WHILE("while", true, true),
  FOR("for", true, true),
  IN("in", true, true),
  BREAK("break", true, true),
  CONTINUE("continue", true, true),
  TRUE("true", true, true),
  FALSE("false", true, true),
  NULL("null", true, true),
  ENV("env", true, true),
  PRINT("print", true, true),
  EOF("<eof>", false),
  ERROR("<error>", false);

  public static final Map<String, Token> BY_SPELLING;
  public static final Map<Token, String> SPELLINGS;
  public static final Map<String, Token> FIXED;
  private static final FixedTokenMatcher MATCHER;

  static {
    var names = new LinkedHashMap<String, Token>();
    var reverse = new EnumMap<Token, String>(Token.class);
    var fixed = new LinkedHashMap<String, Token>();
    for (Token token : values()) {
      if (names.put(token.spelling, token) != null) {
        throw new ExceptionInInitializerError("Duplicate spelling: " + token.spelling);
      }
      reverse.put(token, token.spelling);
      if (token.fixed) {
        fixed.put(token.spelling, token);
      }
    }
    BY_SPELLING = Map.copyOf(names);
    SPELLINGS = Map.copyOf(reverse);
    FIXED = Map.copyOf(fixed);
    MATCHER = new FixedTokenMatcher(FIXED);
  }

  private final String spelling;
  private final boolean fixed;
  private final boolean keyword;

  Token(String spelling, boolean fixed) {
    this(spelling, fixed, false);
  }

  Token(String spelling, boolean fixed, boolean keyword) {
    this.spelling = spelling;
    this.fixed = fixed;
    this.keyword = keyword;
  }

  /**
   * Matches the longest fixed token beginning at {@code start}.
   *
   * @param source source sequence to inspect
   * @param start  UTF-16 offset at which matching begins
   * @return matched token kind, or {@link #NONE} when the position starts a
   * variable token
   * @throws IndexOutOfBoundsException if {@code start} is outside the source
   */
  public static Token matchFixed(CharSequence source, int start) {
    return MATCHER.match(source, start);
  }

  /**
   * Returns the canonical source spelling or diagnostic placeholder.
   *
   * @return token spelling
   */
  public String spelling() {
    return spelling;
  }

  /**
   * Tests whether the token has a fixed spelling recognized by the scanner.
   *
   * @return {@code true} for punctuation, operators, and keywords
   */
  public boolean fixed() {
    return fixed;
  }

  /**
   * Tests whether this fixed token is a language keyword rather than punctuation
   * or an operator.
   *
   * @return {@code true} for reserved language words
   */
  public boolean keyword() {
    return keyword;
  }

  /**
   * Source span with both UTF-16 offsets and one-based line/column coordinates.
   *
   * @param kind   token kind
   * @param start  start index
   * @param end    end index
   * @param line   line index
   * @param column column index
   */
  public record Span(Token kind, int start, int end, int line, int column) {
  }
}
