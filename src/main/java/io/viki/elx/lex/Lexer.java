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
import io.viki.elx.Type;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Parser-facing lexer with two-token lookahead and primitive literal decoding.
 * A lexer is owned by one parser invocation and is mutable/thread-confined;
 * copying it creates an independent lookahead cursor over the same source and
 * diagnostic spans. Macro-expanded input may provide a separate token/origin
 * table so errors map back to the invocation site.
 */
public final class Lexer {
  private final String source;
  private final @Nullable Tokenizer tokenizer;
  private final @Nullable List<Token.Span> tokens;
  private final @Nullable List<Token.Span> origins;
  private int index;
  private Token.Span current, next;

  /**
   * Creates a lexer from a source code.
   *
   * @param source the source code
   */
  public Lexer(String source) {
    this.source = source;
    tokenizer = new Tokenizer(source);
    tokens = null;
    origins = null;
    current = tokenizer.next();
    next = tokenizer.next();
  }

  /**
   * Creates a lexer over pre-tokenized input and its original diagnostic spans.
   * The two lists must have matching positions.
   *
   * @param source  expanded source buffer used for text extraction
   * @param tokens  expanded token spans
   * @param origins original source spans corresponding to {@code tokens}
   */
  public Lexer(String source, List<Token.Span> tokens, List<Token.Span> origins) {
    this.source = source;
    this.tokens = List.copyOf(tokens);
    this.origins = List.copyOf(origins);
    tokenizer = null;
    current = read();
    next = read();
  }

  /**
   * Copies the current cursor without consuming the original lexer.
   *
   * @param other lexer whose current position and source mappings are copied
   */
  public Lexer(Lexer other) {
    source = other.source;
    tokens = other.tokens;
    origins = other.origins;
    tokenizer = tokens == null ? new Tokenizer(source, other.current) : null;
    index = Math.max(0, other.index - 2);
    current = read();
    next = read();
  }

  private Token.Span read() {
    if (tokens != null) {
      return tokens.get(Math.min(index++, tokens.size() - 1));
    }
    return Objects.requireNonNull(tokenizer).next();
  }

  /**
   * Returns the source buffer used by this lexer.
   *
   * @return source text
   */
  public String source() {
    return source;
  }

  /**
   * Returns the span currently presented to the parser.
   *
   * @return current token span
   */
  public Token.Span current() {
    return current;
  }

  /**
   * Returns the kind of the one-token lookahead without consuming it.
   *
   * @return next token kind
   */
  public Token peek() {
    return next.kind();
  }

  /**
   * Extracts the source substring covered by a token span.
   *
   * @param token span in this lexer's source buffer
   * @return token text
   */
  public String text(Token.Span token) {
    return source.substring(token.start(), token.end());
  }

  /**
   * Consumes the current token and advances the lookahead window.
   *
   * @return the token span that was current before advancing
   */
  public Token.Span advance() {
    Token.Span old = current;
    current = next;
    next = read();
    return old;
  }

  /**
   * Consumes the current token when its kind matches {@code token}.
   *
   * @param token expected token kind
   * @return {@code true} when a token was consumed
   */
  public boolean match(Token token) {
    if (current.kind() != token) {
      return false;
    }
    advance();
    return true;
  }

  /**
   * Requires and consumes a token of the requested kind.
   *
   * @param token expected token kind
   * @return consumed token span
   * @throws SyntaxException if the current token has another kind
   */
  public Token.Span require(Token token) {
    if (current.kind() != token) {
      throw error("Expected '" + token.spelling() + "', got '" + text(current) + "'");
    }
    return advance();
  }

  /**
   * Creates a syntax exception associated with the current token.
   *
   * @param message diagnostic message
   * @return exception ready to throw
   */
  public SyntaxException error(String message) {
    return error(message, current);
  }

  /**
   * Creates a syntax exception associated with an explicit token span, mapping
   * expanded spans back to their original macro call site when available.
   *
   * @param message diagnostic message
   * @param token   span at which the error occurred
   * @return exception ready to throw
   */
  public SyntaxException error(String message, Token.Span token) {
    if (tokens != null && origins != null) {
      int low = 0, high = tokens.size() - 1;
      while (low <= high) {
        int middle = (low + high) >>> 1;
        int offset = tokens.get(middle).start();
        if (offset < token.start()) {
          low = middle + 1;
        } else if (offset > token.start()) {
          high = middle - 1;
        } else {
          return new SyntaxException(message, origins.get(middle));
        }
      }
    }
    return new SyntaxException(message, token);
  }

  /**
   * Decodes the current numeric token into a primitive runtime type and raw
   * bits, then consumes it. The sign is supplied separately because the
   * tokenizer treats {@code -} as an operator.
   *
   * @param negative whether to negate the decoded magnitude
   * @return decoded number type and bits
   * @throws SyntaxException if the literal is malformed or out of range
   */
  public Number number(boolean negative) {
    Token.Span token = current;
    NumericLiteral literal = NumericLiteral.scan(source, token.start());
    Type type = literal.type();
    String text = source.substring(literal.bodyStart(), literal.bodyEnd()).replace("'", "");
    long bits;
    if (type.integer()) {
      BigInteger magnitude = new BigInteger(text, literal.radix());
      int width = type.width();
      BigInteger limit = BigInteger.ONE.shiftLeft(width - (type.unsigned() ? 0 : 1));
      if (type.unsigned() || !negative) {
        limit = limit.subtract(BigInteger.ONE);
      }
      if (magnitude.compareTo(limit) > 0) {
        throw error("Integer literal is outside " + type.displayName() + " range");
      }
      bits = Type.normalize(negative ? -magnitude.longValue() : magnitude.longValue(), type);
    } else if (type == Type.FLOAT) {
      float value = Float.parseFloat(text);
      if (!Float.isFinite(value)) {
        throw error("Float literal overflow");
      }
      bits = Integer.toUnsignedLong(Float.floatToRawIntBits(negative ? -value : value));
    } else {
      double value = Double.parseDouble(text);
      if (!Double.isFinite(value)) {
        throw error("Double literal overflow");
      }
      bits = Double.doubleToRawLongBits(negative ? -value : value);
    }
    advance();
    return new Number(type, bits);
  }

  /**
   * Decodes and consumes the current quoted string, including supported
   * escapes and four-digit Unicode escapes.
   *
   * @return decoded Java string
   * @throws SyntaxException if an escape is invalid or incomplete
   */
  public String string() {
    Token.Span token = require(Token.STRING);
    StringBuilder result = new StringBuilder();
    for (int i = token.start() + 1; i < token.end() - 1; i++) {
      char c = source.charAt(i);
      if (c != '\\') {
        result.append(c);
        continue;
      }
      c = source.charAt(++i);
      switch (c) {
        case 'n' -> result.append('\n');
        case 'r' -> result.append('\r');
        case 't' -> result.append('\t');
        case '0' -> result.append('\0');
        case '\\', '\'', '"' -> result.append(c);
        case 'u' -> {
          if (i + 4 >= token.end() - 1) {
            throw error("Incomplete Unicode escape", token);
          }
          int value = 0;
          for (int j = 0; j < 4; j++) {
            int digit = Character.digit(source.charAt(++i), 16);
            if (digit < 0) {
              throw error("Invalid Unicode escape", token);
            }
            value = (value << 4) | digit;
          }
          result.append((char) value);
        }
        default -> throw error("Unknown string escape: \\" + c, token);
      }
    }
    return result.toString();
  }

  /**
   * Decoded primitive number represented by its runtime type and raw bits.
   *
   * @param type numeric type
   * @param bits raw bits data
   */
  public record Number(Type type, long bits) {
  }
}
