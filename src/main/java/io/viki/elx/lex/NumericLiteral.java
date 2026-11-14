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

import io.viki.elx.Type;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validated numeric source span, not a boxed runtime number. The body includes
 * separators/exponent but excludes radix prefix and suffix. Range checking and
 * conversion to primitive bits belong to the subsequent constant decoder.
 * Instances and immutable suffix metadata are safe to share between threads.
 *
 * @param kind      token kind emitted for this literal
 * @param radix     numeric radix, like 2 or 10
 * @param bodyStart inclusive start of the digits proper, after any prefix
 * @param bodyEnd   exclusive end of the digits proper, before the suffix
 * @param end       exclusive end of the whole literal, after the suffix
 */
public record NumericLiteral(Token kind, int radix, int bodyStart, int bodyEnd, int end) {
  /**
   * Maps a token-kind ordinal to the runtime {@link Type} selected by the
   * corresponding suffix. Populated once from {@link Suffix}; indexed by
   * {@link Token#ordinal()}.
   */
  private static final Type[] LITERAL_TYPES = new Type[Token.values().length];

  static {
    for (Suffix suffix : Suffix.values()) {
      LITERAL_TYPES[suffix.kind().ordinal()] = suffix.type();
    }
  }

  /**
   * Validates the source span and supported radix.
   * <p>
   * Rejects unsupported radices, negative starts, empty bodies
   * ({@code bodyEnd <= bodyStart}) and spans where the suffix would overlap or
   * precede the body ({@code end < bodyEnd}). It does not validate that the
   * characters in the span are actually digits; that is done by
   * {@link #scan(CharSequence, int)} before construction.
   *
   * @param kind      token kind emitted for this literal
   * @param radix     numeric radix, like 2 or 10
   * @param bodyStart inclusive start of the digits proper, after any prefix
   * @param bodyEnd   exclusive end of the digits proper, before the suffix
   * @param end       exclusive end of the whole literal, after the suffix
   * @throws IllegalArgumentException if the radix or span is invalid
   */
  public NumericLiteral {
    if ((radix != 2 && radix != 10 && radix != 16) || bodyStart < 0 || bodyEnd <= bodyStart || end < bodyEnd) {
      throw new IllegalArgumentException("Invalid numeric literal span");
    }
  }

  /**
   * Scans a literal starting at a decimal digit, b' or h'. A leading sign remains
   * a separate unary token. Suffixes/prefixes are lowercase. Apostrophes must be
   * between digits in the same digit sequence. Hexadecimal b/d/f are digits,
   * so hexadecimal literals only admit the unambiguous s/us/u/l/ul suffixes.
   * <p>
   * The scan proceeds in three stages: radix prefix and integer digits, an
   * optional decimal fraction and exponent (decimal radix only), then the
   * suffix. On return, {@code bodyStart} points at the first digit,
   * {@code bodyEnd} at the first suffix character and {@code end} just past the
   * suffix. A floating literal with no explicit suffix is emitted as
   * {@link Token#DOUBLE_LITERAL}; all other kinds come from {@link Suffix}.
   *
   * @param source the character sequence being scanned
   * @param start  index of the first character of the literal
   * @return a validated literal spanning {@code [start, end)}
   * @throws IndexOutOfBoundsException if {@code start} is outside {@code source}
   * @throws IllegalArgumentException  if the literal is malformed or its suffix
   *                                   is unknown or inconsistent with the radix
   *                                   or floating status
   */
  public static NumericLiteral scan(CharSequence source, int start) {
    if (start < 0 || start >= source.length()) {
      throw new IndexOutOfBoundsException(start);
    }
    int radix = 10, body = start;
    if (start + 1 < source.length() && source.charAt(start + 1) == '\'') {
      if (source.charAt(start) == 'b') {
        radix = 2;
        body += 2;
      } else if (source.charAt(start) == 'h') {
        radix = 16;
        body += 2;
      }
    }
    int position = digits(source, body, radix);
    boolean floating = false;
    if (radix == 10 && position < source.length() && source.charAt(position) == '.'
        && position + 1 < source.length() && digit(source.charAt(position + 1), 10)) {
      floating = true;
      position = digits(source, position + 1, 10);
    }
    if (radix == 10 && position < source.length() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
      floating = true;
      position++;
      if (position < source.length() && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
        position++;
      }
      position = digits(source, position, 10);
    }
    int bodyEnd = position;
    while (position < source.length() && Character.isLetterOrDigit(source.charAt(position))) {
      position++;
    }
    String text = source.subSequence(bodyEnd, position).toString();
    Suffix suffix = Suffix.BY_TEXT.get(text);
    if (suffix == null) {
      throw invalid(bodyEnd, "unknown numeric suffix '" + text + "'");
    }
    if (floating && !text.isEmpty() && !suffix.floating()) {
      throw invalid(bodyEnd, "integer suffix on floating literal");
    }
    if (radix != 10 && suffix.floating()) {
      throw invalid(bodyEnd, "floating suffix on radix literal");
    }
    Token kind = floating && text.isEmpty() ? Token.DOUBLE_LITERAL : suffix.kind();
    return new NumericLiteral(kind, radix, body, bodyEnd, position);
  }

  /**
   * Consumes one or more digits of the given radix, allowing single apostrophes
   * as separators only when they sit strictly between two digits. Returns the
   * index of the first character that is neither a digit nor a valid separator.
   *
   * @param source the character sequence being scanned
   * @param start  index of the first expected digit
   * @param radix  accepted digit radix
   * @return index just past the last consumed digit
   * @throws IllegalArgumentException if no digit is present at {@code start} or
   *                                  a separator is misplaced
   */
  private static int digits(CharSequence source, int start, int radix) {
    int position = start;
    if (position >= source.length() || !digit(source.charAt(position), radix)) {
      throw invalid(position, "expected base-" + radix + " digit");
    }
    while (position < source.length()) {
      char c = source.charAt(position);
      if (digit(c, radix)) {
        position++;
      } else if (c == '\'') {
        if (position + 1 >= source.length() || !digit(source.charAt(position + 1), radix)) {
          throw invalid(position, "separator must occur between digits");
        }
        position += 2;
      } else {
        break;
      }
    }
    return position;
  }

  /**
   * Tests whether {@code c} is a valid digit for the given radix. Accepts both
   * lower- and upper-case hexadecimal letters.
   *
   * @param c     character to test
   * @param radix digit radix
   * @return {@code true} if {@code c} denotes a digit below {@code radix}
   */
  private static boolean digit(char c, int radix) {
    int value = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    return value >= 0 && value < radix;
  }

  /**
   * Builds the exception thrown for malformed literals, tagging the offset at
   * which scanning failed.
   *
   * @param position offset of the offending character
   * @param reason   human-readable description
   * @return an {@link IllegalArgumentException} ready to throw
   */
  private static IllegalArgumentException invalid(int position, String reason) {
    return new IllegalArgumentException("Invalid numeric literal at offset " + position + ": " + reason);
  }

  /**
   * Returns the runtime type associated with this literal's suffix.
   *
   * @return literal type
   */
  public Type type() {
    return LITERAL_TYPES[kind.ordinal()];
  }

  /** Literal suffix definitions; scanner and constant decoding share this table. */
  public enum Suffix {
    /** No suffix: a plain signed {@code int}. */
    INT("", Token.INT_LITERAL, Type.INT),
    /** {@code b}: unsigned byte. */
    UBYTE("b", Token.UBYTE_LITERAL, Type.UBYTE),
    /** {@code s}: signed short. */
    SHORT("s", Token.SHORT_LITERAL, Type.SHORT),
    /** {@code us}: unsigned short. */
    USHORT("us", Token.USHORT_LITERAL, Type.USHORT),
    /** {@code u}: unsigned int. */
    UINT("u", Token.UINT_LITERAL, Type.UINT),
    /** {@code l}: signed long. */
    LONG("l", Token.LONG_LITERAL, Type.LONG),
    /** {@code ul}: unsigned long. */
    ULONG("ul", Token.ULONG_LITERAL, Type.ULONG),
    /** {@code f}: single-precision float. */
    FLOAT("f", Token.FLOAT_LITERAL, Type.FLOAT),
    /** {@code d}: double-precision float. */
    DOUBLE("d", Token.DOUBLE_LITERAL, Type.DOUBLE);

    /**
     * Suffix text to definition lookup, including the empty key for
     * {@link #INT}. Immutable and shared between the scanner and the constant
     * decoder.
     */
    public static final Map<String, Suffix> BY_TEXT;

    static {
      Map<String, Suffix> entries = new LinkedHashMap<>();
      for (Suffix suffix : values()) {
        entries.put(suffix.text, suffix);
      }
      BY_TEXT = Map.copyOf(entries);
    }

    private final String text;
    private final Token kind;
    private final Type type;

    Suffix(String text, Token kind, Type type) {
      this.text = text;
      this.kind = kind;
      this.type = type;
    }

    /**
     * Returns the source suffix, including the empty suffix for a signed int.
     *
     * @return suffix text
     */
    public String text() {
      return text;
    }

    /**
     * Returns the token kind emitted for this suffix.
     *
     * @return token kind
     */
    public Token kind() {
      return kind;
    }

    /**
     * Returns the runtime type selected by this suffix.
     *
     * @return runtime type
     */
    public Type type() {
      return type;
    }

    /**
     * Tests whether the suffix denotes a floating-point literal.
     *
     * @return {@code true} for {@code f} and {@code d}
     */
    public boolean floating() {
      return !type.integer();
    }
  }
}