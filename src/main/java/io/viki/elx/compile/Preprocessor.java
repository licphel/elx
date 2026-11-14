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

package io.viki.elx.compile;

import io.viki.elx.SyntaxException;
import io.viki.elx.lex.Lexer;
import io.viki.elx.lex.Token;
import io.viki.elx.lex.Tokenizer;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Per-compilation token expansion for ELX's source-level macros. Definitions
 * are local to one lexer invocation and are applied in source order; expansion
 * preserves the original token kind and call-site span so parser diagnostics
 * point to the source that invoked a macro. The implementation also enforces
 * bounded nesting and output size to prevent recursive definitions from
 * exhausting the compiler.
 */
final class Preprocessor {
  private static final int MAX_EXPANSION_DEPTH = 128;
  private static final int MAX_EXPANDED_TOKENS = 1_000_000;
  private final String filename;
  private final Map<String, Macro> macros = new HashMap<>();

  private Preprocessor(String filename) {
    this.filename = filename;
  }

  /**
     * Tokenizes and expands one source unit before parser consumption.
     * Built-in {@code FILENAME} and {@code LINECOUNT} macros are synthesized
     * from the original spans. Unless {@code internal} is enabled, names that
     * begin with an underscore are rejected because they belong to host glue.
     *
     * @param filename source name used by {@code FILENAME} and diagnostics
     * @param source source text
     * @param internal whether host-private names are permitted
     * @return lexer over the expanded token stream
   */
  static Lexer lexer(String filename, String source, boolean internal) {
    List<Piece> input = new ArrayList<>();
    Tokenizer tokenizer = new Tokenizer(source);
    Token.Span span;
    while ((span = tokenizer.next()).kind() != Token.EOF) {
      String text = source.substring(span.start(), span.end());
      if (!internal && span.kind() == Token.IDENTIFIER && text.startsWith("_")) {
        throw new SyntaxException("Names starting with '_' are reserved for the host: " + text, span);
      }
      input.add(new Piece(span.kind(), text, span));
    }
    Piece eof = new Piece(Token.EOF, "", span);
    Preprocessor processor = new Preprocessor(filename);
    List<Piece> output = new ArrayList<>(), pending = new ArrayList<>();
    for (int i = 0; i < input.size(); ) {
      Piece piece = input.get(i);
      if (piece.text().equals("#define") && lineStart(source, piece.origin().start())) {
        output.addAll(processor.expand(pending, new HashSet<>()));
        pending.clear();
        int end = i + 1;
        while (end < input.size() && !newline(input.get(end))) {
          end++;
        }
        processor.define(input.subList(i + 1, end), piece);
        if (end < input.size()) {
          output.add(input.get(end++));
        }
        i = end;
      } else {
        pending.add(piece);
        i++;
      }
    }
    output.addAll(processor.expand(pending, new HashSet<>()));
    StringBuilder expanded = new StringBuilder();
    List<Token.Span> tokens = new ArrayList<>(output.size() + 1);
    List<Token.Span> origins = new ArrayList<>(output.size() + 1);
    for (Piece piece : output) {
      int start = expanded.length();
      expanded.append(piece.text());
      tokens.add(new Token.Span(piece.kind(), start, expanded.length(), piece.origin().line(), piece.origin().column()));
      origins.add(piece.origin());
      expanded.append(' ');
    }
    tokens.add(new Token.Span(Token.EOF, expanded.length(), expanded.length(), eof.origin().line(), eof.origin().column()));
    origins.add(eof.origin());
    return new Lexer(expanded.toString(), tokens, origins);
  }

  private static boolean lineStart(String source, int offset) {
    for (int i = offset - 1; i >= 0 && source.charAt(i) != '\n' && source.charAt(i) != '\r'; i--) {
      if (!Character.isWhitespace(source.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean newline(Piece piece) {
    return piece.kind() == Token.SEP && (piece.text().contains("\n") || piece.text().contains("\r"));
  }

  private static Invocation arguments(List<Piece> input, int opening, Piece call) {
    List<List<Piece>> arguments = new ArrayList<>();
    List<Piece> argument = new ArrayList<>();
    List<Token> closing = new ArrayList<>();
    for (int at = opening + 1; at < input.size(); at++) {
      Piece piece = input.get(at);
      Token kind = piece.kind();
      if (newline(piece)) {
        continue;
      }
      if (closing.isEmpty() && kind == Token.RIGHT_PAREN) {
        if (!argument.isEmpty() || !arguments.isEmpty()) {
          arguments.add(List.copyOf(argument));
        }
        return new Invocation(List.copyOf(arguments), at + 1);
      }
      if (closing.isEmpty() && kind == Token.COMMA) {
        if (argument.isEmpty()) {
          throw error("Empty macro argument", piece);
        }
        arguments.add(List.copyOf(argument));
        argument.clear();
        continue;
      }
      switch (kind) {
        case LEFT_PAREN -> closing.add(Token.RIGHT_PAREN);
        case LEFT_BRACKET -> closing.add(Token.RIGHT_BRACKET);
        case LEFT_BRACE -> closing.add(Token.RIGHT_BRACE);
        case RIGHT_PAREN, RIGHT_BRACKET, RIGHT_BRACE -> {
          if (closing.isEmpty() || closing.remove(closing.size() - 1) != kind) {
            throw error("Mismatched macro argument delimiter", piece);
          }
        }
        default -> {
        }
      }
      argument.add(piece);
    }
    throw error("Expected ')' after macro arguments", call);
  }

  private static String quote(String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
  }

  private static SyntaxException error(String message, Piece piece) {
    return new SyntaxException(message, piece.origin());
  }

  private void define(List<Piece> definition, Piece directive) {
    if (definition.isEmpty() || definition.get(0).kind() != Token.IDENTIFIER) {
      throw error("Expected a macro name after #define", directive);
    }
    Piece name = definition.get(0);
    if (name.text().equals("FILENAME") || name.text().equals("LINECOUNT")) {
      throw error("Cannot redefine built-in macro " + name.text(), name);
    }
    int at = 1;
    @Nullable List<String> parameters = null;
    @Nullable String variadic = null;
    // Like C, a function macro's '(' must immediately follow its name in the definition.
    if (at < definition.size() && definition.get(at).kind() == Token.LEFT_PAREN
        && definition.get(at).origin().start() == name.origin().end()) {
      parameters = new ArrayList<>();
      at++;
      while (at < definition.size() && definition.get(at).kind() != Token.RIGHT_PAREN) {
        Piece parameter = definition.get(at++);
        if (parameter.kind() != Token.IDENTIFIER || parameters.contains(parameter.text())) {
          throw error("Expected a unique macro parameter", parameter);
        }
        if (at < definition.size() && definition.get(at).kind() == Token.ELLIPSIS) {
          variadic = parameter.text();
          at++;
          break;
        }
        parameters.add(parameter.text());
        if (at >= definition.size() || definition.get(at).kind() != Token.COMMA) {
          break;
        }
        at++;
        if (at < definition.size() && definition.get(at).kind() == Token.RIGHT_PAREN) {
          throw error("Expected a macro parameter after ','", definition.get(at));
        }
      }
      if (at >= definition.size() || definition.get(at).kind() != Token.RIGHT_PAREN) {
        throw error("Expected ')' after macro parameters; a variadic parameter must be last", name);
      }
      at++;
    }
    macros.put(name.text(), new Macro(parameters == null ? null : List.copyOf(parameters), variadic,
        List.copyOf(definition.subList(at, definition.size()))));
  }

  private List<Piece> expand(List<Piece> input, Set<String> active) {
    List<Piece> output = new ArrayList<>();
    for (int at = 0; at < input.size(); ) {
      Piece piece = input.get(at++);
      if (piece.kind() != Token.IDENTIFIER) {
        output.add(piece);
        continue;
      }
      if (piece.text().equals("FILENAME")) {
        output.add(new Piece(Token.STRING, quote(filename), piece.origin()));
        continue;
      }
      if (piece.text().equals("LINECOUNT")) {
        output.add(new Piece(Token.INT_LITERAL, Integer.toString(piece.origin().line()), piece.origin()));
        continue;
      }
      Macro macro = macros.get(piece.text());
      if (macro == null || active.contains(piece.text())) {
        output.add(piece);
        continue;
      }
      Map<String, List<Piece>> substitutions = new HashMap<>();
      if (macro.parameters() != null) {
        int opening = at;
        while (opening < input.size() && newline(input.get(opening))) {
          opening++;
        }
        if (opening == input.size() || input.get(opening).kind() != Token.LEFT_PAREN) {
          output.add(piece);
          continue;
        }
        Invocation invocation = arguments(input, opening, piece);
        at = invocation.end();
        int fixed = macro.parameters().size(), count = invocation.arguments().size();
        if (count < fixed || macro.variadic() == null && count != fixed) {
          throw error("Macro " + piece.text() + " expects " + fixed
              + (macro.variadic() == null ? "" : " or more") + " arguments, got " + count, piece);
        }
        for (int i = 0; i < fixed; i++) {
          substitutions.put(macro.parameters().get(i), invocation.arguments().get(i));
        }
        if (macro.variadic() != null) {
          List<Piece> rest = new ArrayList<>();
          rest.add(new Piece(Token.LEFT_BRACKET, "[", piece.origin()));
          for (int i = fixed; i < count; i++) {
            if (i > fixed) {
              rest.add(new Piece(Token.COMMA, ",", piece.origin()));
            }
            rest.addAll(invocation.arguments().get(i));
          }
          rest.add(new Piece(Token.RIGHT_BRACKET, "]", piece.origin()));
          substitutions.put(macro.variadic(), rest);
        }
      }
      if (active.size() == MAX_EXPANSION_DEPTH) {
        throw error("Macro expansion nesting is too deep", piece);
      }
      List<Piece> replacement = new ArrayList<>();
      for (Piece body : macro.body()) {
        List<Piece> argument = body.kind() == Token.IDENTIFIER ? substitutions.get(body.text()) : null;
        if (argument == null) {
          replacement.add(new Piece(body.kind(), body.text(), piece.origin()));
        } else {
          replacement.addAll(argument);
        }
      }
      active.add(piece.text());
      List<Piece> expanded = expand(replacement, active);
      // Rescan the replacement's last token with the caller's following arguments: ALIAS(...).
      while (!expanded.isEmpty()) {
        Piece tail = expanded.get(expanded.size() - 1);
        Macro target = tail.kind() == Token.IDENTIFIER ? macros.get(tail.text()) : null;
        int opening = at;
        while (opening < input.size() && newline(input.get(opening))) {
          opening++;
        }
        if (target == null || target.parameters() == null || active.contains(tail.text())
            || opening == input.size() || input.get(opening).kind() != Token.LEFT_PAREN) {
          break;
        }
        Invocation invocation = arguments(input, opening, tail);
        List<Piece> resumed = new ArrayList<>();
        resumed.add(tail);
        resumed.addAll(input.subList(opening, invocation.end()));
        expanded.remove(expanded.size() - 1);
        expanded.addAll(expand(resumed, active));
        at = invocation.end();
      }
      output.addAll(expanded);
      active.remove(piece.text());
      if (output.size() > MAX_EXPANDED_TOKENS) {
        throw error("Macro expansion exceeds the token budget", piece);
      }
    }
    return output;
  }

  private record Piece(Token kind, String text, Token.Span origin) {
  }

  private record Macro(@Nullable List<String> parameters, @Nullable String variadic, List<Piece> body) {
  }

  private record Invocation(List<List<Piece>> arguments, int end) {
  }
}
