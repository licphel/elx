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
import io.viki.elx.Type;
import io.viki.elx.lex.Lexer;
import io.viki.elx.lex.Token;
import io.viki.elx.lex.Tokenizer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.*;

/**
 * Source ordered ELX preprocessing. Macro definitions are shared by nested
 * includes, conditional state is local to each source file, and every expanded
 * token retains the file and source location from which it originated.
 */
final class Preprocessor {
  private static final int MAX_EXPANSION_DEPTH = 128;
  private static final int MAX_INCLUDE_DEPTH = 128;
  private static final int MAX_EXPANDED_TOKENS = 1_000_000;
  private final IncludeResolver resolver;
  private final Map<String, Macro> macros = new HashMap<>();
  private final Set<String> includeStack = new HashSet<>();
  private final Set<String> onceIncluded = new HashSet<>();
  private int expandedTokenCount;

  private Preprocessor(IncludeResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver);
  }

  /**
   * Tokenizes, preprocesses, expands, and source-maps one compilation unit.
   *
   * @param filename root source name used in diagnostics and built-ins
   * @param source   root source text
   * @param internal retained for source compatibility; underscore names are valid
   * @param resolver include resolver for this compilation
   * @return parser lexer over expanded tokens
   */
  static Lexer lexer(String filename, String source, boolean internal, IncludeResolver resolver) {
    Preprocessor processor = new Preprocessor(resolver);
    List<Piece> output = processor.process(filename, source);
    StringBuilder expanded = new StringBuilder();
    List<Token.Span> tokens = new ArrayList<>(output.size() + 1);
    List<Token.Span> origins = new ArrayList<>(output.size() + 1);
    Deque<Token> delimiters = new ArrayDeque<>();
    Token previous = Token.SEP;
    for (Piece piece : output) {
      if (isNewline(piece) && (!delimiters.isEmpty()
          && (delimiters.peek() == Token.LEFT_PAREN || delimiters.peek() == Token.LEFT_BRACKET)
          || continuesLine(previous))) {
        continue;
      }
      if (piece.kind() == Token.LEFT_PAREN || piece.kind() == Token.LEFT_BRACKET
          || piece.kind() == Token.LEFT_BRACE) {
        delimiters.push(piece.kind());
      } else if (piece.kind() == Token.RIGHT_PAREN || piece.kind() == Token.RIGHT_BRACKET
          || piece.kind() == Token.RIGHT_BRACE) {
        if (!delimiters.isEmpty()) {
          delimiters.pop();
        }
      }
      previous = piece.kind();
      int start = expanded.length();
      expanded.append(piece.text());
      Token.Span token = new Token.Span(piece.kind(), start, expanded.length(),
          piece.origin().line(), piece.origin().column(), piece.origin().sourceName());
      tokens.add(token);
      origins.add(piece.origin());
      expanded.append(' ');
    }
    Token.Span eof = sourceSpan(filename, source, Token.EOF);
    tokens.add(new Token.Span(Token.EOF, expanded.length(), expanded.length(), eof.line(),
        eof.column(), filename));
    origins.add(eof);
    return new Lexer(expanded.toString(), tokens, origins);
  }

  private static boolean continuesLine(Token previous) {
    return switch (previous) {
      case COMMA, EQUAL, PLUS, MINUS, STAR, SLASH, PERCENT, AMP, PIPE, CARET,
           AND_AND, OR_OR, EQUAL_EQUAL, BANG_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
           SHIFT_LEFT, SHIFT_RIGHT, SHIFT_UNSIGNED_RIGHT -> true;
      default -> false;
    };
  }

  private static List<Piece> tokenize(String sourceName, String source) {
    List<Piece> input = new ArrayList<>();
    Tokenizer tokenizer = new Tokenizer(source, true);
    Token.Span span;
    while ((span = tokenizer.next()).kind() != Token.EOF) {
      Token.Span origin = withSource(span, sourceName);
      input.add(new Piece(span.kind(), source.substring(span.start(), span.end()), origin));
    }
    return input;
  }

  private static Token.Span sourceSpan(String sourceName, String source, Token kind) {
    Tokenizer tokenizer = new Tokenizer(source, true);
    Token.Span eof;
    while ((eof = tokenizer.next()).kind() != Token.EOF) {
    }
    return new Token.Span(kind, eof.start(), eof.end(), eof.line(), eof.column(), sourceName);
  }

  private static Token.Span withSource(Token.Span span, String sourceName) {
    return new Token.Span(span.kind(), span.start(), span.end(), span.line(), span.column(), sourceName);
  }

  private static String identity(String sourceName) {
    try {
      return java.nio.file.Path.of(sourceName).toRealPath().toString();
    } catch (IOException | RuntimeException ignored) {
      return sourceName;
    }
  }

  private static @Nullable Piece directive(Piece first) {
    return first.kind() == Token.IDENTIFIER && first.text().startsWith("#") ? first : null;
  }

  private static boolean isNewline(Piece piece) {
    return piece.kind() == Token.SEP && (piece.text().contains("\n") || piece.text().contains("\r"));
  }

  private static boolean active(Deque<Conditional> conditions) {
    return conditions.isEmpty() || conditions.peek().active;
  }

  private static void beginCondition(Deque<Conditional> conditions, boolean result, Piece directive) {
    boolean parentActive = active(conditions);
    boolean selected = parentActive && result;
    conditions.push(new Conditional(parentActive, selected, selected, false, directive));
  }

  private static void otherwise(Deque<Conditional> conditions, List<Piece> arguments, Piece directive) {
    requireEmpty(arguments, directive);
    if (conditions.isEmpty()) {
      throw error("#else without matching #if", directive);
    }
    Conditional condition = conditions.peek();
    if (condition.elseSeen) {
      throw error("Duplicate #else", directive);
    }
    condition.elseSeen = true;
    condition.active = condition.parentActive && !condition.branchTaken;
    condition.branchTaken = true;
  }

  private static void endCondition(Deque<Conditional> conditions, List<Piece> arguments, Piece directive) {
    requireEmpty(arguments, directive);
    if (conditions.isEmpty()) {
      throw error("#endif without matching #if", directive);
    }
    conditions.pop();
  }

  private static void requireSingle(List<Piece> pieces, String expected, Piece directive) {
    List<Piece> clean = withoutSeparators(pieces);
    if (clean.size() != 1 || !clean.get(0).text().equals(expected)) {
      throw error("Expected #pragma " + expected, directive);
    }
  }

  private static void requireEmpty(List<Piece> pieces, Piece directive) {
    if (!withoutSeparators(pieces).isEmpty()) {
      throw error("Unexpected tokens after " + directive.text(), pieces.get(0));
    }
  }

  private static List<Piece> withoutSeparators(List<Piece> pieces) {
    List<Piece> result = new ArrayList<>(pieces.size());
    for (Piece piece : pieces) {
      if (!isNewline(piece)) {
        result.add(piece);
      }
    }
    return result;
  }

  private static Invocation parseArguments(List<Piece> input, int opening, Piece call) {
    List<List<Piece>> arguments = new ArrayList<>();
    List<Piece> argument = new ArrayList<>();
    List<Token> closing = new ArrayList<>();
    for (int at = opening + 1; at < input.size(); at++) {
      Piece piece = input.get(at);
      Token kind = piece.kind();
      if (isNewline(piece)) {
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

  private static SyntaxException error(String message, Token.Span span) {
    return new SyntaxException(message, span);
  }

  private List<Piece> process(String sourceName, String source) {
    String identity = identity(sourceName);
    if (onceIncluded.contains(identity)) {
      return List.of();
    }
    if (includeStack.size() == MAX_INCLUDE_DEPTH) {
      throw new SyntaxException("Include nesting exceeds " + MAX_INCLUDE_DEPTH,
          sourceSpan(sourceName, source, Token.ERROR));
    }
    if (!includeStack.add(identity)) {
      throw new SyntaxException("Recursive include: " + sourceName,
          sourceSpan(sourceName, source, Token.ERROR));
    }
    try {
      return processFile(sourceName, source);
    } finally {
      includeStack.remove(identity);
    }
  }

  private List<Piece> processFile(String sourceName, String source) {
    List<Piece> input = tokenize(sourceName, source);
    List<Piece> output = new ArrayList<>(), pending = new ArrayList<>();
    Deque<Conditional> conditions = new ArrayDeque<>();
    boolean pragmaOnce = false;
    for (int at = 0; at < input.size(); ) {
      int end = at;
      while (end < input.size() && !isNewline(input.get(end))) {
        end++;
      }
      List<Piece> line = input.subList(at, end);
      @Nullable Piece newline = end < input.size() ? input.get(end) : null;
      at = end + (newline == null ? 0 : 1);
      @Nullable Piece directive = line.isEmpty() ? null : directive(line.get(0));
      if (directive == null) {
        if (active(conditions)) {
          pending.addAll(line);
          if (newline != null) {
            pending.add(newline);
          }
        }
        continue;
      }

      flush(output, pending);
      String name = directive.text();
      List<Piece> arguments = line.subList(1, line.size());
      switch (name) {
        case "#if" -> {
          boolean parentActive = active(conditions);
          beginCondition(conditions, parentActive && evaluate(arguments, directive), directive);
        }
        case "#ifdef" -> {
          boolean parentActive = active(conditions);
          beginCondition(conditions, parentActive && defined(arguments, directive), directive);
        }
        case "#ifndef" -> {
          boolean parentActive = active(conditions);
          beginCondition(conditions, parentActive && !defined(arguments, directive), directive);
        }
        case "#elif" -> elif(conditions, arguments, directive);
        case "#else" -> otherwise(conditions, arguments, directive);
        case "#endif" -> endCondition(conditions, arguments, directive);
        default -> {
          if (!active(conditions)) {
            continue;
          }
          switch (name) {
            case "#define" -> define(arguments, directive);
            case "#undef" -> undefine(arguments, directive);
            case "#include" -> {
              String requested = includeName(expand(arguments, new HashSet<>()), directive);
              IncludeResolver.Source included;
              try {
                included = resolver.resolve(sourceName, requested);
              } catch (IOException | RuntimeException failure) {
                throw error("Cannot include '" + requested + "': " + failure.getMessage(), directive);
              }
              output.addAll(process(included.name(), included.text()));
            }
            case "#pragma" -> {
              requireSingle(arguments, "once", directive);
              pragmaOnce = true;
            }
            default -> throw error("Unknown preprocessor directive " + name, directive);
          }
        }
      }
    }
    flush(output, pending);
    if (!conditions.isEmpty()) {
      throw error("Missing #endif for conditional directive", conditions.peek().opening);
    }
    if (pragmaOnce) {
      onceIncluded.add(identity(sourceName));
    }
    return output;
  }

  private void flush(List<Piece> output, List<Piece> pending) {
    if (!pending.isEmpty()) {
      List<Piece> expanded = expand(pending, new HashSet<>());
      expandedTokenCount += expanded.size();
      if (expandedTokenCount > MAX_EXPANDED_TOKENS) {
        throw error("Preprocessed source exceeds the token budget", pending.get(pending.size() - 1));
      }
      output.addAll(expanded);
      pending.clear();
    }
  }

  private void elif(Deque<Conditional> conditions, List<Piece> arguments, Piece directive) {
    if (conditions.isEmpty()) {
      throw error("#elif without matching #if", directive);
    }
    Conditional condition = conditions.peek();
    if (condition.elseSeen) {
      throw error("#elif after #else", directive);
    }
    boolean result = condition.parentActive && !condition.branchTaken && evaluate(arguments, directive);
    condition.active = condition.parentActive && !condition.branchTaken && result;
    condition.branchTaken |= condition.active;
  }

  private boolean defined(List<Piece> arguments, Piece directive) {
    List<Piece> clean = withoutSeparators(arguments);
    if (clean.size() != 1 || clean.get(0).kind() != Token.IDENTIFIER) {
      throw error("Expected one macro name", directive);
    }
    String name = clean.get(0).text();
    return macros.containsKey(name) || name.equals("FILENAME") || name.equals("LINECOUNT");
  }

  private boolean evaluate(List<Piece> expression, Piece directive) {
    List<Piece> clean = withoutSeparators(expression);
    if (clean.isEmpty()) {
      throw error("Expected a preprocessor expression", directive);
    }
    List<Piece> protectedDefined = new ArrayList<>();
    for (int at = 0; at < clean.size(); at++) {
      Piece piece = clean.get(at);
      if (piece.kind() == Token.IDENTIFIER && piece.text().equals("defined")) {
        boolean parenthesized = at + 1 < clean.size() && clean.get(at + 1).kind() == Token.LEFT_PAREN;
        int nameAt = at + (parenthesized ? 2 : 1);
        if (nameAt >= clean.size() || clean.get(nameAt).kind() != Token.IDENTIFIER) {
          throw error("Expected a macro name after defined", piece);
        }
        Piece macroName = clean.get(nameAt);
        boolean known = macros.containsKey(macroName.text())
            || macroName.text().equals("FILENAME") || macroName.text().equals("LINECOUNT");
        protectedDefined.add(new Piece(Token.INT_LITERAL, known ? "1" : "0", piece.origin()));
        at = nameAt;
        if (parenthesized) {
          if (at + 1 >= clean.size() || clean.get(at + 1).kind() != Token.RIGHT_PAREN) {
            throw error("Expected ')' after defined macro name", piece);
          }
          at++;
        }
      } else if (piece.kind() == Token.TRUE || piece.kind() == Token.FALSE) {
        protectedDefined.add(new Piece(Token.INT_LITERAL,
            piece.kind() == Token.TRUE ? "1" : "0", piece.origin()));
      } else {
        protectedDefined.add(piece);
      }
    }
    List<Piece> expanded = expand(protectedDefined, new HashSet<>());
    StringBuilder text = new StringBuilder();
    for (Piece piece : expanded) {
      if (piece.kind() == Token.IDENTIFIER) {
        if (piece.text().equals("true")) {
          text.append("1 ");
        } else if (piece.text().equals("false")) {
          text.append("0 ");
        } else {
          text.append("0 ");
        }
      } else {
        text.append(piece.text()).append(' ');
      }
    }
    try {
      return new IfExpression(text.toString(), directive).parse() != 0;
    } catch (SyntaxException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw error("Invalid #if expression: " + failure.getMessage(), directive);
    }
  }

  private void define(List<Piece> definition, Piece directive) {
    List<Piece> clean = withoutSeparators(definition);
    if (clean.isEmpty() || clean.get(0).kind() != Token.IDENTIFIER) {
      throw error("Expected a macro name after #define", directive);
    }
    Piece name = clean.get(0);
    if (name.text().equals("FILENAME") || name.text().equals("LINECOUNT")) {
      throw error("Cannot redefine built-in macro " + name.text(), name);
    }
    int at = 1;
    @Nullable List<String> parameters = null;
    @Nullable String variadic = null;
    if (at < clean.size() && clean.get(at).kind() == Token.LEFT_PAREN
        && clean.get(at).origin().start() == name.origin().end()) {
      parameters = new ArrayList<>();
      at++;
      while (at < clean.size() && clean.get(at).kind() != Token.RIGHT_PAREN) {
        Piece parameter = clean.get(at++);
        if (parameter.kind() != Token.IDENTIFIER || parameters.contains(parameter.text())) {
          throw error("Expected a unique macro parameter", parameter);
        }
        if (at < clean.size() && clean.get(at).kind() == Token.ELLIPSIS) {
          variadic = parameter.text();
          at++;
          break;
        }
        parameters.add(parameter.text());
        if (at >= clean.size() || clean.get(at).kind() != Token.COMMA) {
          break;
        }
        at++;
        if (at < clean.size() && clean.get(at).kind() == Token.RIGHT_PAREN) {
          throw error("Expected a macro parameter after ','", clean.get(at));
        }
      }
      if (at >= clean.size() || clean.get(at).kind() != Token.RIGHT_PAREN) {
        throw error("Expected ')' after macro parameters; a variadic parameter must be last", name);
      }
      at++;
    }
    macros.put(name.text(), new Macro(parameters == null ? null : List.copyOf(parameters), variadic,
        List.copyOf(clean.subList(at, clean.size()))));
  }

  private void undefine(List<Piece> arguments, Piece directive) {
    List<Piece> clean = withoutSeparators(arguments);
    if (clean.size() != 1 || clean.get(0).kind() != Token.IDENTIFIER) {
      throw error("Expected one macro name after #undef", directive);
    }
    String name = clean.get(0).text();
    if (name.equals("FILENAME") || name.equals("LINECOUNT")) {
      throw error("Cannot undefine built-in macro " + name, clean.get(0));
    }
    macros.remove(name);
  }

  private String includeName(List<Piece> pieces, Piece directive) {
    List<Piece> clean = withoutSeparators(pieces);
    if (clean.size() == 1 && clean.get(0).kind() == Token.STRING) {
      String literal = clean.get(0).text();
      try {
        Lexer lexer = new Lexer(literal);
        String value = lexer.string();
        lexer.require(Token.EOF);
        return value;
      } catch (RuntimeException failure) {
        throw error("Invalid include path literal", clean.get(0));
      }
    }
    if (clean.size() >= 2 && clean.get(0).kind() == Token.LESS
        && clean.get(clean.size() - 1).kind() == Token.GREATER) {
      StringBuilder path = new StringBuilder();
      for (int i = 1; i < clean.size() - 1; i++) {
        path.append(clean.get(i).text());
      }
      if (!path.isEmpty()) {
        return path.toString();
      }
    }
    throw error("Expected #include \"path\" or #include <path>", directive);
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
        output.add(new Piece(Token.STRING, quote(piece.origin().sourceName() == null
            ? "<script>" : piece.origin().sourceName()), piece.origin()));
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
        while (opening < input.size() && isNewline(input.get(opening))) {
          opening++;
        }
        if (opening == input.size() || input.get(opening).kind() != Token.LEFT_PAREN) {
          output.add(piece);
          continue;
        }
        Invocation invocation = parseArguments(input, opening, piece);
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
      while (!expanded.isEmpty()) {
        Piece tail = expanded.get(expanded.size() - 1);
        Macro target = tail.kind() == Token.IDENTIFIER ? macros.get(tail.text()) : null;
        int opening = at;
        while (opening < input.size() && isNewline(input.get(opening))) {
          opening++;
        }
        if (target == null || target.parameters() == null || active.contains(tail.text())
            || opening == input.size() || input.get(opening).kind() != Token.LEFT_PAREN) {
          break;
        }
        Invocation invocation = parseArguments(input, opening, tail);
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

  private static final class Conditional {
    final boolean parentActive;
    final Piece opening;
    boolean active;
    boolean branchTaken;
    boolean elseSeen;

    Conditional(boolean parentActive, boolean active, boolean branchTaken,
                boolean elseSeen, Piece opening) {
      this.parentActive = parentActive;
      this.active = active;
      this.branchTaken = branchTaken;
      this.elseSeen = elseSeen;
      this.opening = opening;
    }
  }

  private final class IfExpression {
    private final Lexer lexer;
    private final Piece directive;

    IfExpression(String expression, Piece directive) {
      lexer = new Lexer(expression);
      this.directive = directive;
    }

    long parse() {
      long result = logicalOr(true);
      if (lexer.current().kind() != Token.EOF) {
        throw error("Unexpected token in #if expression: " + lexer.text(lexer.current()), directive);
      }
      return result;
    }

    private long logicalOr(boolean evaluate) {
      long left = logicalAnd(evaluate);
      while (lexer.match(Token.OR_OR)) {
        boolean rhs = evaluate && left == 0;
        long right = logicalAnd(rhs);
        left = evaluate && (left != 0 || right != 0) ? 1 : 0;
      }
      return left;
    }

    private long logicalAnd(boolean evaluate) {
      long left = bitOr(evaluate);
      while (lexer.match(Token.AND_AND)) {
        boolean rhs = evaluate && left != 0;
        long right = bitOr(rhs);
        left = evaluate && (left != 0 && right != 0) ? 1 : 0;
      }
      return left;
    }

    private long bitOr(boolean evaluate) {
      long left = bitXor(evaluate);
      while (lexer.match(Token.PIPE)) {
        long right = bitXor(evaluate);
        if (evaluate) {
          left |= right;
        }
      }
      return left;
    }

    private long bitXor(boolean evaluate) {
      long left = bitAnd(evaluate);
      while (lexer.match(Token.CARET)) {
        long right = bitAnd(evaluate);
        if (evaluate) {
          left ^= right;
        }
      }
      return left;
    }

    private long bitAnd(boolean evaluate) {
      long left = equality(evaluate);
      while (lexer.match(Token.AMP)) {
        long right = equality(evaluate);
        if (evaluate) {
          left &= right;
        }
      }
      return left;
    }

    private long equality(boolean evaluate) {
      long left = relation(evaluate);
      for (; ; ) {
        if (lexer.match(Token.EQUAL_EQUAL)) {
          long right = relation(evaluate);
          if (evaluate) {
            left = left == right ? 1 : 0;
          }
        } else if (lexer.match(Token.BANG_EQUAL)) {
          long right = relation(evaluate);
          if (evaluate) {
            left = left != right ? 1 : 0;
          }
        } else {
          return left;
        }
      }
    }

    private long relation(boolean evaluate) {
      long left = shift(evaluate);
      for (; ; ) {
        Token token = lexer.current().kind();
        if (token == Token.LESS || token == Token.LESS_EQUAL
            || token == Token.GREATER || token == Token.GREATER_EQUAL) {
          lexer.advance();
          long right = shift(evaluate);
          if (evaluate) {
            left = switch (token) {
              case LESS -> left < right ? 1 : 0;
              case LESS_EQUAL -> left <= right ? 1 : 0;
              case GREATER -> left > right ? 1 : 0;
              case GREATER_EQUAL -> left >= right ? 1 : 0;
              default -> throw new AssertionError(token);
            };
          }
        } else {
          return left;
        }
      }
    }

    private long shift(boolean evaluate) {
      long left = additive(evaluate);
      for (; ; ) {
        Token token = lexer.current().kind();
        if (token == Token.SHIFT_LEFT || token == Token.SHIFT_RIGHT || token == Token.SHIFT_UNSIGNED_RIGHT) {
          lexer.advance();
          long right = additive(evaluate);
          if (evaluate) {
            left = token == Token.SHIFT_LEFT ? left << (right & 63)
                : token == Token.SHIFT_RIGHT ? left >> (right & 63) : left >>> (right & 63);
          }
        } else {
          return left;
        }
      }
    }

    private long additive(boolean evaluate) {
      long left = multiplicative(evaluate);
      for (; ; ) {
        if (lexer.match(Token.PLUS)) {
          long right = multiplicative(evaluate);
          if (evaluate) {
            left += right;
          }
        } else if (lexer.match(Token.MINUS)) {
          long right = multiplicative(evaluate);
          if (evaluate) {
            left -= right;
          }
        } else {
          return left;
        }
      }
    }

    private long multiplicative(boolean evaluate) {
      long left = unary(evaluate);
      for (; ; ) {
        Token token = lexer.current().kind();
        if (token == Token.STAR || token == Token.SLASH || token == Token.PERCENT) {
          lexer.advance();
          long right = unary(evaluate);
          if (evaluate) {
            if ((token == Token.SLASH || token == Token.PERCENT) && right == 0) {
              throw error("Division by zero in #if expression", directive);
            }
            left = token == Token.STAR ? left * right : token == Token.SLASH ? left / right : left % right;
          }
        } else {
          return left;
        }
      }
    }

    private long unary(boolean evaluate) {
      if (lexer.match(Token.BANG)) {
        return unary(evaluate) == 0 ? 1 : 0;
      }
      if (lexer.match(Token.TILDE)) {
        return ~unary(evaluate);
      }
      if (lexer.match(Token.PLUS)) {
        return unary(evaluate);
      }
      if (lexer.match(Token.MINUS)) {
        return -unary(evaluate);
      }
      if (lexer.match(Token.LEFT_PAREN)) {
        long value = logicalOr(evaluate);
        lexer.require(Token.RIGHT_PAREN);
        return value;
      }
      Token kind = lexer.current().kind();
      if (kind == Token.IDENTIFIER) {
        String identifier = lexer.text(lexer.advance());
        if (identifier.equals("true")) {
          return 1;
        }
        if (identifier.equals("false")) {
          return 0;
        }
        return 0;
      }
      if (kind == Token.INT_LITERAL || kind == Token.UBYTE_LITERAL
          || kind == Token.SHORT_LITERAL || kind == Token.USHORT_LITERAL
          || kind == Token.UINT_LITERAL || kind == Token.LONG_LITERAL
          || kind == Token.ULONG_LITERAL || kind == Token.FLOAT_LITERAL
          || kind == Token.DOUBLE_LITERAL) {
        Lexer.Number number = lexer.number(false);
        if (!number.type().integer()) {
          throw error("#if expressions require integer literals", directive);
        }
        return Type.normalize(number.bits(), number.type());
      }
      throw error("Expected an integer in #if expression", directive);
    }
  }
}
