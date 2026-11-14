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

import io.viki.elx.Type;
import io.viki.elx.asm.*;
import io.viki.elx.lib.*;
import io.viki.elx.lex.Lexer;
import io.viki.elx.lex.Token;
import io.viki.elx.memory.ElxHeap;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Compiles ELX source into validated register-based {@link Prototype} images.
 * Each invocation creates an independent parser, lexical-scope tree and
 * assembler, so compiler instances are safe to share when calls are made from
 * separate threads. Public entry points choose between optimized O1 images,
 * decomposed O0 images, raw reference images, and host-internal source that may
 * access underscore-prefixed names. Libraries supply the symbols and host
 * functions available to the source; the resulting prototype contains no
 * parser state.
 */
public final class Compiler {
  private static final Symbol BF_LIB = Objects.requireNonNull(Builtins.standard().resolve("len"));

  private static boolean numeric(Token token) {
    return switch (token) {
      case UBYTE_LITERAL, SHORT_LITERAL, USHORT_LITERAL, INT_LITERAL, UINT_LITERAL, LONG_LITERAL, ULONG_LITERAL,
           FLOAT_LITERAL, DOUBLE_LITERAL -> true;
      default -> false;
    };
  }

  private static int precedence(Token token) {
    return switch (token) {
      case OR_OR -> 1;
      case AND_AND -> 2;
      case PIPE -> 3;
      case CARET -> 4;
      case AMP -> 5;
      case EQUAL_EQUAL, BANG_EQUAL -> 6;
      case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> 7;
      case SHIFT_LEFT, SHIFT_RIGHT, SHIFT_UNSIGNED_RIGHT -> 8;
      case PLUS, MINUS -> 9;
      case STAR, SLASH, PERCENT -> 10;
      default -> 0;
    };
  }

  private static boolean assignment(Token token) {
    return switch (token) {
      case EQUAL, PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL, AMP_EQUAL, PIPE_EQUAL, CARET_EQUAL,
           TILDE_EQUAL, SHIFT_LEFT_EQUAL, SHIFT_RIGHT_EQUAL, SHIFT_UNSIGNED_RIGHT_EQUAL, AND_AND_EQUAL, OR_OR_EQUAL ->
          true;
      default -> false;
    };
  }

  private static Token compound(Token token) {
    return switch (token) {
      case PLUS_EQUAL -> Token.PLUS;
      case MINUS_EQUAL -> Token.MINUS;
      case STAR_EQUAL -> Token.STAR;
      case SLASH_EQUAL -> Token.SLASH;
      case PERCENT_EQUAL -> Token.PERCENT;
      case AMP_EQUAL -> Token.AMP;
      case PIPE_EQUAL -> Token.PIPE;
      case CARET_EQUAL -> Token.CARET;
      case SHIFT_LEFT_EQUAL -> Token.SHIFT_LEFT;
      case SHIFT_RIGHT_EQUAL -> Token.SHIFT_RIGHT;
      case SHIFT_UNSIGNED_RIGHT_EQUAL -> Token.SHIFT_UNSIGNED_RIGHT;
      default -> throw new IllegalArgumentException("Not compound assignment: " + token);
    };
  }

  private static Op arithmetic(Token token) {
    return switch (token) {
      case PLUS -> Op.IADD;
      case MINUS -> Op.ISUB;
      case STAR -> Op.IMUL;
      case SLASH -> Op.IDIV;
      case PERCENT -> Op.IREM;
      case AMP -> Op.BAND;
      case PIPE -> Op.BOR;
      case CARET -> Op.BXOR;
      case SHIFT_LEFT -> Op.SHL;
      case SHIFT_RIGHT -> Op.SHR;
      case SHIFT_UNSIGNED_RIGHT -> Op.USHR;
      default -> throw new IllegalArgumentException("Not arithmetic: " + token);
    };
  }

  private static Op integratedArithmetic(Op op) {
    return switch (op) {
      case IADD -> Op.CX_ADD;
      case ISUB -> Op.CX_SUB;
      case IMUL -> Op.CX_MUL;
      case IDIV -> Op.CX_DIV;
      case IREM -> Op.CX_REM;
      case BAND -> Op.CX_AND;
      case BOR -> Op.CX_OR;
      case BXOR -> Op.CX_XOR;
      case SHL -> Op.CX_SHL;
      case SHR -> Op.CX_SHR;
      case USHR -> Op.CX_USHR;
      default -> throw new IllegalArgumentException("Not a dynamic arithmetic operation: " + op);
    };
  }

  private static Instr.Relation relation(Token token) {
    return switch (token) {
      case LESS -> Instr.Relation.LT;
      case LESS_EQUAL -> Instr.Relation.LE;
      case GREATER -> Instr.Relation.GT;
      case GREATER_EQUAL -> Instr.Relation.GE;
      default -> throw new IllegalArgumentException("Not ordering: " + token);
    };
  }

  /**
     * Compiles a script against an empty library using the default O1 optimizer.
     *
     * @param source ELX source text
     * @return optimized script prototype
   */
  public Prototype compile(String source) {
    return compile(source, Library.EMPTY);
  }

  /**
     * Compiles a script against the supplied host library using the default O1
     * optimizer and a synthetic {@code <script>} source name.
     *
     * @param source ELX source text
     * @param library symbols and host functions visible to the script
     * @return optimized script prototype
   */
  public Prototype compile(String source, Library library) {
    return compile("<script>", source, library);
  }

  /**
     * Compiles a script with source metadata and the default O1 optimization.
     *
     * @param sourceName name used in diagnostics and source-line metadata
     * @param source ELX source text
     * @param library symbols and host functions visible to the script
     * @return optimized script prototype
   */
  public Prototype compile(String sourceName, String source, Library library) {
    return Optimizer.optimize(compileRaw(sourceName, source, library));
  }

  /**
     * Compiles a script with an explicit optimization level.
     *
     * @param source ELX source text
     * @param library symbols and host functions visible to the script
     * @param level requested O0 or O1 image form
     * @return prototype emitted at the requested level
   */
  public Prototype compile(String source, Library library, Optimization level) {
    return compile("<script>", source, library, level);
  }

  /**
     * Compiles a script with explicit source metadata and optimization level.
     * O1 returns the fused image; O0 lowers integrated operations through the
     * primitive instruction ABI.
     *
     * @param sourceName name used in diagnostics and source-line metadata
     * @param source ELX source text
     * @param library symbols and host functions visible to the script
     * @param level requested O0 or O1 image form
     * @return prototype emitted at the requested level
   */
  public Prototype compile(String sourceName, String source, Library library, Optimization level) {
    Prototype image = compileRaw(sourceName, source, library);
    return level == Optimization.O1 ? Optimizer.optimize(image) : Decomposer.lower(image);
  }

  /**
   * Compiles a script without optimization or O0 lowering. The image preserves
   * the compiler's direct instruction choices for tooling and differential
   * testing.
   *
   * @param sourceName name used in diagnostics and source-line metadata
   * @param source ELX source text
   * @param library symbols and host functions visible to the script
   * @return unoptimized reference prototype
   */
  public Prototype compileRaw(String sourceName, String source, Library library) {
    return compileRaw(sourceName, source, library, false);
  }

  /**
   * Compiles host-generated glue with access to underscore-prefixed names.
   * This entry point is intended for runtime-generated adapters and is still
   * optimized as an ordinary O1 image.
   *
   * @param sourceName name used in diagnostics and source-line metadata
   * @param source host-generated ELX source text
   * @param library symbols and host functions visible to the glue
   * @return optimized internal prototype
   */
  public Prototype compileInternal(String sourceName, String source, Library library) {
    return Optimizer.optimize(compileRaw(sourceName, source, library, true));
  }

  private Prototype compileRaw(String sourceName, String source, Library library, boolean internal) {
    Parser parser = new Parser(Preprocessor.lexer(sourceName, source, internal), library, null);
    parser.code.source(sourceName, "<script>");
    return parser.script();
  }

  /**
     * Compiles one function expression against an empty library at O1.
     *
     * @param source function declaration, including the {@code function} keyword
     * @return optimized function prototype
   */
  public Prototype compileFunction(String source) {
    return compileFunction(source, Library.EMPTY);
  }

  /**
     * Compiles one function expression against a host library at O1.
     *
     * @param source function declaration, including the {@code function} keyword
     * @param library symbols and host functions visible to the function
     * @return optimized function prototype
   */
  public Prototype compileFunction(String source, Library library) {
    return compileFunction(source, library, Optimization.O1);
  }

  /**
     * Compiles one function expression with an explicit optimization level.
     * The source must contain exactly one function declaration and no trailing
     * tokens after it.
     *
     * @param source function declaration, including the {@code function} keyword
     * @param library symbols and host functions visible to the function
     * @param level requested O0 or O1 image form
     * @return function prototype emitted at the requested level
   */
  public Prototype compileFunction(String source, Library library, Optimization level) {
    Parser parser = new Parser(Preprocessor.lexer("<function>", source, false), library, null);
    parser.lexer.require(Token.FUNCTION);
    @Nullable String name = parser.kind() == Token.IDENTIFIER ? parser.lexer.text(parser.lexer.advance()) : null;
    parser.function(name);
    parser.lexer.require(Token.EOF);
    @Nullable Prototype result = parser.lastFunction;
    if (result == null) {
      throw new IllegalStateException("Function was not compiled");
    }
    return level == Optimization.O1 ? Optimizer.optimize(result) : Decomposer.lower(result);
  }

  private record Binding(int base, int capture, int width, boolean multiple) {
  }

  private record Target(@Nullable Binding binding, int container, int key) {
  }

  private record Expr(int register, Type type, int width, boolean multiple, @Nullable Target target, int available) {
    Expr(int register, Type type) {
      this(register, type, 1, false, null, 1);
    }
  }

  private record Capture(Binding source, int index) {
  }

  private static final class Scope {
    final @Nullable Scope parent;
    final int first;
    final Map<String, Binding> names = new HashMap<>();

    Scope(@Nullable Scope parent, int first) {
      this.parent = parent;
      this.first = first;
    }
  }

  private record Loop(int exit, int step, @Nullable Loop parent) {
  }

  private static final class Parser {
    private static final int MAX_RESULTS = 4;
    private final Lexer lexer;
    private final Library library;
    private final @Nullable Parser parent;
    private final Assembler code = new Assembler();
    private final Map<String, Binding> captured = new HashMap<>();
    private final List<Capture> captures = new ArrayList<>();
    private Scope scope = new Scope(null, 0);
    private @Nullable Loop loop;
    private int locals, next, highWater = 1, parameters, pendingTypeClosers;
    private @Nullable Prototype lastFunction;

    Parser(Lexer lexer, Library library, @Nullable Parser parent) {
      this.lexer = lexer;
      this.library = library;
      this.parent = parent;
    }

    private Token kind() {
      return lexer.current().kind();
    }

    private Token.Span memberName() {
      return kind() == Token.PRINT ? lexer.advance() : lexer.require(Token.IDENTIFIER);
    }

    private int reserve(int count) {
      if (next + count > Instr.MAX_REGISTERS) {
        throw lexer.error("Function requires more than 256 registers");
      }
      int base = next;
      next += count;
      highWater = Math.max(highWater, next);
      return count == 0 ? 0 : base;
    }

    private int temporary() {
      return reserve(1);
    }

    private int local(int count) {
      if (locals + count > Instr.MAX_REGISTERS) {
        throw lexer.error("Function requires more than 256 local registers");
      }
      int base = locals;
      locals += count;
      highWater = Math.max(highWater, locals);
      next = Math.max(next, locals);
      return count == 0 ? 0 : base;
    }

    private void enter() {
      scope = new Scope(scope, locals);
      next = locals;
    }

    private void leave() {
      Scope outer = scope.parent;
      if (outer == null) {
        throw new IllegalStateException("Cannot leave function scope");
      }
      locals = scope.first;
      scope = outer;
      next = locals;
    }

    private @Nullable Binding find(String name) {
      for (@Nullable Scope at = scope; at != null; at = at.parent) {
        @Nullable Binding binding = at.names.get(name);
        if (binding != null) {
          return binding;
        }
      }
      @Nullable Binding binding = captured.get(name);
      if (binding != null) {
        return binding;
      }
      if (parent == null) {
        return null;
      }
      @Nullable Binding source = parent.find(name);
      if (source == null) {
        return null;
      }
      int index = code.capture(source.width());
      binding = new Binding(0, index, source.width(), source.multiple());
      captured.put(name, binding);
      captures.add(new Capture(source, index));
      return binding;
    }

    private Expr literal(Type type, long bits) {
      int at = temporary();
      code.constant(at, code.constant(type, bits));
      return new Expr(at, type);
    }

    private Expr string(String value) {
      int at = temporary();
      code.constant(at, code.string(value));
      return new Expr(at, Type.STRING);
    }

    private Expr variable(Binding binding) {
      return new Expr(-1, Type.INVALID, binding.width(), binding.multiple(), new Target(binding, -1, -1), 1);
    }

    private Expr read(Binding binding) {
      int at = reserve(binding.width());
      if (binding.capture() >= 0) {
        code.immediate(Op.CX_GET_CAPTURE, at, binding.width(), binding.capture());
      } else {
        for (int i = 0; i < binding.width(); i++) {
          code.r(Op.MOVE, at + i, binding.base() + i, 0);
        }
      }
      return new Expr(at, Type.INVALID, binding.width(), binding.multiple(), null, 1);
    }

    private Expr value(Expr expression) {
      if (expression.register() >= 0) {
        return expression;
      }
      Target target = expression.target();
      if (target == null) {
        throw new IllegalStateException("Unbound expression");
      }
      Binding binding = target.binding();
      if (binding != null) {
        Expr read = read(binding);
        return new Expr(read.register(), read.type(), read.width(), read.multiple(), target, 1);
      }
      int at = temporary();
      if (target.container() == -2) {
        code.r(Op.CX_GET_GLOBAL, at, target.key(), 0);
      } else if (target.container() == -3) {
        code.rx(Op.CX_DEREF, at, target.key(), 0, 1);
      } else {
        code.r(Op.CX_GET_INDEX, at, target.container(), target.key());
      }
      return new Expr(at, Type.INVALID, 1, false, target, 1);
    }

    private Expr expand(Expr source, int count) {
      int at = reserve(count);
      code.rx(Op.CX_DEREF, at, source.register(), 0, count);
      return new Expr(at, Type.INVALID, count, count > 1, null, 1);
    }

    private Expr scalar(Expr expression) {
      expression = value(expression);
      if (expression.available() == -1) {
        return expand(expression, 1);
      }
      if (!expression.multiple()) {
        return expression;
      }
      return new Expr(expression.register(), Type.INVALID, 1, false, expression.target(), 1);
    }

    private Expr dereference(Expr source) {
      source = scalar(source);
      if (source.type() == Type.FUNCTION) {
        int at = temporary();
        Target target = source.target();
        if (target != null && target.container() == -4) {
          code.upper(Op.CX_CONST_PTR, at, target.key());
        } else if (target != null && target.binding() != null) {
          return addressOf(source);
        } else {
          code.r(Op.CX_LOCAL_PTR, at, source.register(), 0);
        }
        return new Expr(at, Type.PTR);
      }
      if (source.type() != Type.INVALID && source.type() != Type.LIST && source.type() != Type.PTR) {
        throw lexer.error("Dereference requires a list or managed pointer");
      }
      @Nullable Target target = source.type() == Type.LIST ? null : new Target(null, -3, source.register());
      return new Expr(source.register(), Type.INVALID, 1, false, target, -1);
    }

    private void write(Target target, Expr source) {
      Expr data = value(source);
      Binding binding = target.binding();
      if (binding != null) {
        if (binding.width() != data.width() || binding.multiple() != data.multiple()) {
          throw lexer.error("Assignment changes value shape");
        }
        if (binding.capture() >= 0) {
          code.immediate(Op.CX_SET_CAPTURE, data.register(), binding.width(), binding.capture());
        } else {
          for (int i = 0; i < binding.width(); i++) {
            code.r(Op.MOVE, binding.base() + i, data.register() + i, 0);
          }
        }
      } else {
        data = scalar(data);
        if (target.container() == -3) {
          code.r(Op.CX_WRITE_PTR, target.key(), data.register(), 0);
        } else if (target.container() == -2) {
          code.r(Op.CX_SET_GLOBAL, target.key(), data.register(), 0);
        } else {
          code.r(Op.CX_SET_INDEX, target.container(), target.key(), data.register());
        }
      }
    }

    private Target target(Expr expression) {
      Target target = expression.target();
      if (target == null) {
        throw lexer.error("Expression is not assignable");
      }
      return target;
    }

    private Expr pack(List<Expr> values) {
      int count = 0;
      for (Expr expression : values) {
        count += expression.width();
      }
      int base = reserve(count), at = base;
      for (Expr expression : values) {
        expression = value(expression);
        for (int i = 0; i < expression.width(); i++) {
          code.r(Op.MOVE, at++, expression.register() + i, 0);
        }
      }
      return new Expr(base, Type.INVALID, count, count > 1, null, 1);
    }

    Prototype script() {
      while (kind() != Token.EOF) {
        statement();
      }
      code.r(Op.CX_RETURN, 0, 0, 0);
      return code.build(highWater, 0);
    }

    private void block() {
      while (kind() != Token.RIGHT_BRACE && kind() != Token.EOF) {
        statement();
      }
    }

    private void bracedBlock() {
      Token.Span opening = lexer.require(Token.LEFT_BRACE);
      block();
      if (kind() != Token.RIGHT_BRACE) {
        throw lexer.error("Expected '}' to close block opened at line " + opening.line());
      }
      lexer.advance();
    }

    private Body controlledBody() {
      boolean braced = kind() == Token.LEFT_BRACE;
      enter();
      boolean terminated = false;
      if (braced) {
        bracedBlock();
      } else {
        terminated = statement();
      }
      leave();
      return new Body(braced, terminated);
    }

    private boolean tableStatement() {
      Token first = lexer.peek();
      if (first != Token.RIGHT_BRACE && first != Token.IDENTIFIER && first != Token.LEFT_BRACKET && first != Token.STRING) {
        return false;
      }
      Lexer lookahead = new Lexer(lexer);
      int nesting = 0;
      while (lookahead.current().kind() != Token.EOF) {
        Token token = lookahead.advance().kind();
        if (token == Token.LEFT_BRACE) {
          nesting++;
        }
        if (token == Token.RIGHT_BRACE && --nesting == 0) {
          Token next = lookahead.current().kind();
          return next == Token.DOT || next == Token.LEFT_BRACKET || next == Token.COLON || next == Token.LEFT_PAREN;
        }
      }
      return false;
    }

    private boolean statement() {
      code.line(lexer.current().line());
      next = locals;
      boolean nestedTerminator = false;
      switch (kind()) {
        case SEP -> {
          while (lexer.match(Token.SEP)) {
          }
          next = locals;
          return true;
        }
        case LOCAL -> {
          lexer.advance();
          if (lexer.match(Token.FUNCTION)) {
            namedFunction(true);
            nestedTerminator = true;
          } else {
            declare(false);
          }
        }
        case NATIVE -> {
          lexer.advance();
          nativeFunction();
        }
        case FUNCTION -> {
          lexer.advance();
          namedFunction(false);
          nestedTerminator = true;
        }
        case LEFT_BRACE -> {
          if (tableStatement()) {
            simple();
          } else {
            enter();
            bracedBlock();
            leave();
            nestedTerminator = true;
          }
        }
        case IF -> {
          conditional();
          nestedTerminator = true;
        }
        case WHILE -> {
          whileLoop();
          nestedTerminator = true;
        }
        case FOR -> {
          forLoop();
          nestedTerminator = true;
        }
        case BREAK -> {
          if (loop == null) {
            throw lexer.error("break requires a loop");
          }
          lexer.advance();
          code.jump(loop.exit());
        }
        case CONTINUE -> {
          if (loop == null) {
            throw lexer.error("continue requires a loop");
          }
          lexer.advance();
          code.jump(loop.step());
        }
        case RETURN -> returns();
        case PRINT -> printStatement();
        case ELSE, RIGHT_BRACE -> throw lexer.error("Unexpected '" + kind().spelling() + "'");
        default -> simple();
      }
      boolean terminated = lexer.match(Token.SEP);
      if (!nestedTerminator && !terminated && kind() != Token.RIGHT_BRACE && kind() != Token.EOF && kind() != Token.ELSE) {
        throw lexer.error("Expected ';' between statements");
      }
      next = locals;
      return nestedTerminator || terminated;
    }

    /** Parse an editor-facing declaration without binding or emitting runtime code. */
    private void nativeFunction() {
      if (parent != null || scope.parent != null) {
        throw lexer.error("Native declarations are only allowed at script scope");
      }
      lexer.require(Token.FUNCTION);
      if (!lexer.match(Token.PRINT)) {
        lexer.require(Token.IDENTIFIER);
      }
      while (lexer.match(Token.DOT)) {
        memberName();
      }
      lexer.require(Token.LEFT_PAREN);
      if (kind() != Token.RIGHT_PAREN) {
        do {
          lexer.require(Token.IDENTIFIER);
          boolean rest = lexer.match(Token.ELLIPSIS);
          if (lexer.match(Token.COLON)) {
            typeAnnotation();
          }
          if (rest && kind() == Token.COMMA) {
            throw lexer.error("Rest parameter must be last");
          }
          if (rest) {
            break;
          }
        } while (lexer.match(Token.COMMA));
      }
      lexer.require(Token.RIGHT_PAREN);
      if (lexer.match(Token.ARROW)) {
        typeAnnotation();
      }
    }

    private void printStatement() {
      lexer.advance();
      Symbol symbol = librarySymbol("print");
      if (symbol == null || !(symbol.definition() instanceof LE_ExtObject)) {
        throw lexer.error("The standard print library function is unavailable");
      }
      int at = temporary();
      code.constant(at, code.symbol(symbol));
      Expr function = new Expr(at, Type.FUNCTION);
      if (kind() == Token.LEFT_PAREN) {
        call(function, null, true);
        return;
      }
      List<Expr> values = new ArrayList<>();
      List<String> names = new ArrayList<>();
      List<CallSite.ArgumentKind> kinds = new ArrayList<>();
      do {
        values.add(scalar(value(expression())));
        names.add("");
        kinds.add(CallSite.ArgumentKind.POSITIONAL);
      } while (lexer.match(Token.COMMA));
      callWithArguments(function, new Arguments(values, names, kinds), true);
    }

    private @Nullable Symbol librarySymbol(String name) {
      Symbol symbol = library.resolve(name);
      return symbol == null ? Builtins.standard().resolve(name) : symbol;
    }

    private Expr libraryFunction(Symbol symbol) {
      int at = temporary();
      int constantIndex = code.symbol(symbol);
      code.constant(at, constantIndex);
      return new Expr(at, Type.FUNCTION, 1, false, new Target(null, -4, constantIndex), 1);
    }

    private List<Token.Span> names() {
      return names(false);
    }

    private List<Token.Span> names(boolean annotations) {
      List<Token.Span> result = new ArrayList<>();
      do {
        Token.Span name = lexer.require(Token.IDENTIFIER);
        String text = lexer.text(name);
        if (scope.names.containsKey(text) || result.stream().anyMatch(old -> lexer.text(old).equals(text))) {
          throw lexer.error("Duplicate local '" + text + "'", name);
        }
        result.add(name);
        if (annotations && lexer.match(Token.COLON)) {
          typeAnnotation();
        }
      } while (lexer.match(Token.COMMA));
      return result;
    }

    /** Consume a static type expression. Types are editor metadata and emit no VM instructions. */
    private void typeAnnotation() {
      typeAtom();
    }

    private void typeAtom() {
      Token type = kind();
      if (!lexer.match(Token.IDENTIFIER) && !lexer.match(Token.NULL) && !lexer.match(Token.FUNCTION)) {
        throw lexer.error("Expected a type name");
      }
      if (type == Token.IDENTIFIER) {
        while (lexer.match(Token.DOT)) {
          lexer.require(Token.IDENTIFIER);
        }
      }
      if (lexer.match(Token.LESS)) {
        do {
          typeAnnotation();
        } while (lexer.match(Token.COMMA));
        typeGreater();
      }
      lexer.match(Token.QUESTION);
    }

    private void typeGreater() {
      if (pendingTypeClosers > 0) {
        pendingTypeClosers--;
      } else if (lexer.match(Token.GREATER)) {
      } else if (lexer.match(Token.SHIFT_RIGHT)) {
        pendingTypeClosers = 1;
      } else if (lexer.match(Token.SHIFT_UNSIGNED_RIGHT)) {
        pendingTypeClosers = 2;
      } else {
        throw lexer.error("Expected '>' to close type arguments");
      }
    }

    private Expr rhs(int wanted) {
      List<Expr> parts = new ArrayList<>();
      do {
        parts.add(value(expression()));
      } while (lexer.match(Token.COMMA));
      int ordinary = 0, spreads = 0;
      for (Expr part : parts) {
        if (part.available() == -1) {
          spreads++;
        } else {
          ordinary += part.width();
        }
      }
      if (spreads > 1) {
        throw lexer.error("Assignment needs an unambiguous unpack width");
      }
      if (spreads == 1) {
        int needed = wanted - ordinary;
        if (needed < 0) {
          throw lexer.error("Too many assignment values");
        }
        for (int i = 0; i < parts.size(); i++) {
          if (parts.get(i).available() == -1) {
            parts.set(i, expand(parts.get(i), needed));
          }
        }
      }
      if (parts.size() == 1) {
        Expr single = parts.get(0);
        if (wanted > 1 && !single.multiple() && single.available() >= wanted) {
          return new Expr(single.register(), Type.INVALID, wanted, true, null, 1);
        }
        return wanted == 1 ? scalar(single) : single;
      }
      return pack(parts);
    }

    private void declare(boolean header) {
      List<Token.Span> names = names(true);
      Expr initial;
      if (lexer.match(Token.EQUAL)) {
        if (!header && names.size() == 1 && kind() == Token.FUNCTION) {
          String text = lexer.text(names.get(0));
          int at = local(1);
          Binding binding = new Binding(at, -1, 1, false);
          scope.names.put(text, binding);
          lexer.advance();
          Expr function = function(text);
          write(new Target(binding, -1, -1), function);
          next = locals;
          return;
        }
        initial = header ? value(expression()) : rhs(names.size());
      } else {
        List<Expr> defaults = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
          defaults.add(literal(Type.NULL, 0));
        }
        initial = names.size() == 1 ? defaults.get(0) : pack(defaults);
      }
      initial = value(initial);
      if (names.size() == 1 && initial.multiple()) {
        initial = scalar(initial);
      }
      if (names.size() > 1 && initial.width() != names.size()) {
        throw lexer.error("Declaration value count mismatch");
      }
      for (int i = 0; i < names.size(); i++) {
        int count = names.size() == 1 ? initial.width() : 1;
        boolean multiple = names.size() == 1 && initial.multiple();
        int base = local(count);
        Binding binding = new Binding(base, -1, count, multiple);
        code.r(Op.MOVE, binding.base(), initial.register() + (names.size() == 1 ? 0 : i), 0);
        scope.names.put(lexer.text(names.get(i)), binding);
      }
      next = locals;
    }

    private void namedFunction(boolean local) {
      Token.Span name = lexer.require(Token.IDENTIFIER);
      String text = lexer.text(name);
      if (kind() == Token.DOT || kind() == Token.COLON) {
        if (local) {
          throw lexer.error("Qualified function declarations cannot use local", name);
        }
        boolean method = lexer.advance().kind() == Token.COLON;
        Token.Span member = memberName();
        Expr table = nameValue(name), key = string(lexer.text(member));
        Expr function = function(lexer.text(member), method);
        write(new Target(null, table.register(), key.register()), function);
        return;
      }
      @Nullable Binding binding = null;
      if (local) {
        if (scope.names.containsKey(text)) {
          throw lexer.error("Duplicate local '" + text + "'", name);
        }
        int at = local(1);
        binding = new Binding(at, -1, 1, false);
        scope.names.put(text, binding);
      }
      Expr function = function(text);
      if (binding != null) {
        write(new Target(binding, -1, -1), function);
      } else {
        write(new Target(null, -2, string(text).register()), function);
      }
      next = locals;
    }

    private Expr nameValue(Token.Span name) {
      String text = lexer.text(name);
      Binding binding = find(text);
      if (binding != null) {
        return value(variable(binding));
      }
      Expr key = string(text);
      return value(new Expr(-1, Type.INVALID, 1, false, new Target(null, -2, key.register()), 1));
    }

    private Expr function(@Nullable String self) {
      return function(self, false);
    }

    private Expr function(@Nullable String self, boolean receiver) {
      Parser child = new Parser(lexer, library, this);
      child.code.source(code.sourceName(), self == null ? "<function>" : self);
      // Count header entries first so default-expression temporaries cannot overlap later arguments.
      Lexer header = new Lexer(lexer);
      header.require(Token.LEFT_PAREN);
      int count = header.current().kind() == Token.RIGHT_PAREN ? 0 : 1, nesting = 0;
      int typeArguments = 0;
      boolean inType = false;
      while (header.current().kind() != Token.EOF) {
        Token token = header.advance().kind();
        if (token == Token.RIGHT_PAREN && nesting == 0) {
          break;
        }
        if (token == Token.LEFT_PAREN || token == Token.LEFT_BRACKET || token == Token.LEFT_BRACE) {
          nesting++;
        }
        if (token == Token.RIGHT_PAREN || token == Token.RIGHT_BRACKET || token == Token.RIGHT_BRACE) {
          nesting--;
        }
        if (nesting == 0) {
          if (token == Token.COLON) {
            inType = true;
          } else if (inType) {
            if (token == Token.LESS) {
              typeArguments++;
            } else if (token == Token.GREATER) {
              typeArguments--;
            } else if (token == Token.SHIFT_RIGHT) {
              typeArguments -= 2;
            } else if (token == Token.SHIFT_UNSIGNED_RIGHT) {
              typeArguments -= 3;
            } else if (token == Token.EQUAL && typeArguments == 0) {
              inType = false;
            } else if (token == Token.COMMA && typeArguments == 0) {
              count++;
              inType = false;
            }
          } else if (token == Token.COMMA) {
            count++;
          }
        }
      }
      child.local(count + (receiver ? 1 : 0));
      if (self != null) {
        int at = child.local(1);
        Binding selfBinding = new Binding(at, -1, 1, false);
        child.scope.names.put(self, selfBinding);
        child.code.r(Op.CX_SELF, at, 0, 0);
      }
      lexer.require(Token.LEFT_PAREN);
      List<String> parameterNames = new ArrayList<>();
      List<Boolean> optional = new ArrayList<>();
      boolean variadic = false;
      if (receiver) {
        int at = child.parameters++;
        child.scope.names.put("self", new Binding(at, -1, 1, false));
        parameterNames.add("self");
        optional.add(false);
      }
      if (kind() != Token.RIGHT_PAREN) {
        do {
          Token.Span token = lexer.require(Token.IDENTIFIER);
          String name = lexer.text(token);
          boolean rest = lexer.match(Token.ELLIPSIS);
          if (parameterNames.contains(name)) {
            throw lexer.error("Duplicate parameter '" + name + "'", token);
          }
          int at = child.parameters++;
          Binding parameter = new Binding(at, -1, 1, false);
          child.scope.names.put(name, parameter);
          parameterNames.add(name);
          if (lexer.match(Token.COLON)) {
            child.typeAnnotation();
          }
          boolean defaulted = lexer.match(Token.EQUAL);
          optional.add(defaulted);
          if (rest && defaulted) {
            throw lexer.error("Rest parameter cannot have a default");
          }
          if (defaulted) {
            int done = child.code.label();
            child.code.branch(Op.CX_BR_PRESENT, at, done);
            child.next = child.locals;
            Expr value = child.scalar(child.expression());
            child.code.r(Op.MOVE, at, value.register(), 0);
            child.code.bind(done);
          }
          if (rest) {
            variadic = true;
            break;
          }
        } while (lexer.match(Token.COMMA));
      }
      lexer.require(Token.RIGHT_PAREN);
      if (lexer.match(Token.ARROW)) {
        child.typeAnnotation();
      }
      child.code.parameters(new Parameters(parameterNames, optional, variadic));
      child.next = child.locals;
      child.bracedBlock();
      child.code.r(Op.CX_RETURN, 0, 0, 0);
      Prototype image = child.code.build(child.highWater, child.parameters);
      lastFunction = image;
      int result = temporary();
      code.constant(result, code.function(image));
      if (image.captureCount() > 0) {
        List<Expr> values = new ArrayList<>();
        for (Capture capture : child.captures) {
          values.add(read(capture.source()));
        }
        Expr captures = pack(values);
        code.rx(Op.CX_CLOSURE, result, result, captures.register(), image.captureCount());
      }
      return new Expr(result, Type.FUNCTION);
    }

    private boolean conditional() {
      lexer.require(Token.IF);
      int done = code.label();
      boolean terminated;
      for (; ; ) {
        int otherwise = code.label();
        Expr condition = scalar(expression());
        booleanType(condition);
        code.branch(Op.CX_BR_FALSE, condition.register(), otherwise);
        Body body = controlledBody();
        terminated = body.terminated();
        code.jump(done);
        code.bind(otherwise);
        if (!lexer.match(Token.ELSE)) {
          break;
        }
        if (!body.braced()) {
          throw lexer.error("Ambiguous else after an unbraced if body; use braces");
        }
        if (lexer.match(Token.IF)) {
          continue;
        }
        terminated = controlledBody().terminated();
        break;
      }
      code.bind(done);
      return terminated;
    }

    private boolean whileLoop() {
      lexer.require(Token.WHILE);
      int start = code.label(), exit = code.label();
      code.bind(start);
      Expr condition = scalar(expression());
      booleanType(condition);
      code.branch(Op.CX_BR_FALSE, condition.register(), exit);
      @Nullable Loop previous = loop;
      loop = new Loop(exit, start, previous);
      boolean terminated = controlledBody().terminated();
      loop = previous;
      code.jump(start);
      code.bind(exit);
      return terminated;
    }

    private boolean forLoop() {
      lexer.advance();
      boolean parens = lexer.match(Token.LEFT_PAREN);
      enter();
      if (kind() == Token.IDENTIFIER && (lexer.peek() == Token.IN || lexer.peek() == Token.COMMA)) {
        boolean terminated = iterate(parens);
        leave();
        return terminated;
      }
      if (lexer.match(Token.LOCAL)) {
        declare(true);
      } else if (kind() == Token.IDENTIFIER && lexer.peek() == Token.EQUAL) {
        declare(true);
      } else {
        simple();
      }
      lexer.require(Token.COMMA);
      next = locals;
      int start = code.label(), step = code.label(), body = code.label(), exit = code.label();
      code.bind(start);
      Expr condition = scalar(expression());
      booleanType(condition);
      code.branch(Op.CX_BR_FALSE, condition.register(), exit);
      lexer.require(Token.COMMA);
      int entryJump = code.position();
      code.jump(body);
      code.bind(step);
      int stepStart = code.position();
      next = locals;
      simple();
      code.jump(start);
      if (parens) {
        lexer.require(Token.RIGHT_PAREN);
      }
      code.bind(body);
      int bodyStart = code.position();
      @Nullable Loop previous = loop;
      loop = new Loop(exit, step, previous);
      boolean terminated = controlledBody().terminated();
      loop = previous;
      code.finishLoop(entryJump, stepStart, bodyStart);
      code.bind(exit);
      leave();
      return terminated;
    }

    private boolean iterate(boolean parens) {
      List<Token.Span> names = names();
      if (names.size() > 3) {
        throw lexer.error("Iteration supports at most three bindings");
      }
      lexer.require(Token.IN);
      Expr source = value(expression());
      int container = local(source.width());
      for (int i = 0; i < source.width(); i++) {
        code.r(Op.MOVE, container + i, source.register() + i, 0);
      }
      int cursor = local(1), out = local(4), check = local(1), minus = local(1);
      code.constant(cursor, code.constant(Type.LONG, 0));
      code.constant(minus, code.constant(Type.LONG, -1));
      List<Binding> bindings = new ArrayList<>();
      for (Token.Span name : names) {
        int at = local(1);
        Binding binding = new Binding(at, -1, 1, false);
        scope.names.put(lexer.text(name), binding);
        bindings.add(binding);
      }
      if (parens) {
        lexer.require(Token.RIGHT_PAREN);
      }
      int start = code.label(), exit = code.label();
      code.bind(start);
      code.r(Op.CX_NEXT, out, container, cursor);
      code.rx(Op.ICMP, check, out + 3, minus, Instr.control(Type.LONG, Instr.Relation.EQ));
      code.branch(Op.CX_BR_TRUE, check, exit);
      code.r(Op.MOVE, cursor, out + 3, 0);
      for (int i = 0; i < bindings.size(); i++) {
        code.r(Op.MOVE, bindings.get(i).base(), out + (bindings.size() == 1 ? 2 : bindings.size() == 2 ? i + 1 : i), 0);
      }
      @Nullable Loop previous = loop;
      loop = new Loop(exit, start, previous);
      boolean terminated = controlledBody().terminated();
      loop = previous;
      code.jump(start);
      code.bind(exit);
      return terminated;
    }

    private Expr dynamicArguments(List<Expr> parts) {
      return dynamicArguments(parts, Collections.nCopies(parts.size(), CallSite.ArgumentKind.POSITIONAL));
    }

    private Expr dynamicArguments(List<Expr> parts, List<CallSite.ArgumentKind> kinds) {
      Expr list = container(Type.LIST);
      for (int i = 0; i < parts.size(); i++) {
        Expr part = parts.get(i);
        if (kinds.get(i) == CallSite.ArgumentKind.LIST_SPREAD || part.available() == -1) {
          code.r(Op.CX_APPEND_DEREF, list.register(), part.register(), 0);
        } else {
          part = scalar(part);
          code.r(Op.CX_LIST_APPEND, list.register(), part.register(), 0);
        }
      }
      return list;
    }

    private void returns() {
      lexer.require(Token.RETURN);
      if (kind() == Token.RIGHT_BRACE || kind() == Token.EOF || kind() == Token.SEP) {
        code.r(Op.CX_RETURN, 0, 0, 0);
        return;
      }
      List<Expr> parts = new ArrayList<>();
      do {
        parts.add(value(expression()));
      } while (lexer.match(Token.COMMA));
      boolean spread = parts.stream().anyMatch(part -> part.available() == -1);
      if (spread) {
        Expr source = parts.size() == 1 ? parts.get(0) : dynamicArguments(parts);
        code.r(Op.CX_RETURN_DEREF, source.register(), parts.size() == 1 ? 0 : 1, 0);
        return;
      }
      Expr result = parts.size() == 1 ? parts.get(0) : pack(parts);
      if (result.width() > MAX_RESULTS) {
        throw lexer.error("At most four return values are supported");
      }
      code.r(Op.CX_RETURN, result.register(), result.width(), 0);
    }

    private void simple() {
      Expr first = expression();
      if (kind() == Token.COMMA) {
        List<Target> targets = new ArrayList<>();
        targets.add(target(first));
        while (lexer.match(Token.COMMA)) {
          targets.add(target(expression()));
        }
        lexer.require(Token.EQUAL);
        Expr values = rhs(targets.size());
        if (values.width() != targets.size()) {
          throw lexer.error("Assignment value count mismatch");
        }
        for (int i = 0; i < targets.size(); i++) {
          write(targets.get(i), new Expr(values.register() + i, Type.INVALID));
        }
        return;
      }
      if (!assignment(kind())) {
        first = value(first);
        if (first.available() == -1) {
          expand(first, 0);
        }
        return;
      }
      Target destination = target(first);
      Token operator = lexer.advance().kind();
      if (operator == Token.EQUAL) {
        write(destination, rhs(1));
        return;
      }
      Expr old = scalar(first);
      if (operator == Token.TILDE_EQUAL) {
        write(destination, unary(Token.TILDE, old));
        return;
      }
      if (operator == Token.AND_AND_EQUAL || operator == Token.OR_OR_EQUAL) {
        old = scalar(old);
        booleanType(old);
        int done = code.label();
        code.branch(operator == Token.AND_AND_EQUAL ? Op.CX_BR_FALSE : Op.CX_BR_TRUE, old.register(), done);
        Expr right = scalar(expression());
        booleanType(right);
        if (right.type() == Type.INVALID) {
          code.immediate(Op.CX_GUARD, right.register(), 0, Type.BOOL.ordinal());
        }
        write(destination, right);
        code.bind(done);
        return;
      }
      write(destination, binary(compound(operator), old, value(expression())));
    }

    private Expr expression() {
      return expression(1);
    }

    private Expr expression(int minimum) {
      return expression(prefix(), minimum);
    }

    private Expr expression(Expr left, int minimum) {
      while (precedence(kind()) >= minimum) {
        Token operator = lexer.advance().kind();
        left = scalar(left);
        if (operator == Token.AND_AND || operator == Token.OR_OR) {
          left = logical(operator, left, precedence(operator) + 1);
        } else {
          left = binary(operator, left, value(expression(precedence(operator) + 1)));
        }
      }
      return left;
    }

    private Expr logical(Token token, Expr left, int precedence) {
      left = scalar(left);
      booleanType(left);
      int result = temporary(), done = code.label();
      code.r(Op.MOVE, result, left.register(), 0);
      code.branch(token == Token.AND_AND ? Op.CX_BR_FALSE : Op.CX_BR_TRUE, left.register(), done);
      Expr right = scalar(expression(precedence));
      booleanType(right);
      if (right.type() == Type.INVALID) {
        code.immediate(Op.CX_GUARD, right.register(), 0, Type.BOOL.ordinal());
      }
      code.r(Op.MOVE, result, right.register(), 0);
      code.bind(done);
      return new Expr(result, Type.BOOL);
    }

    private Expr addressOf(Expr operand) {
      Target target = target(operand);
      int at = temporary();
      Binding binding = target.binding();
      if (binding != null) {
        if (binding.capture() >= 0) {
          code.immediate(Op.CX_CAPTURE_PTR, at, 0, binding.capture());
        } else {
          code.r(Op.CX_LOCAL_PTR, at, binding.base(), 0);
        }
      } else if (target.container() == -3) {
        code.r(Op.MOVE, at, target.key(), 0);
      } else if (target.container() == -4) {
        code.upper(Op.CX_CONST_PTR, at, target.key());
      } else if (target.container() == -2) {
        code.r(Op.CX_GLOBAL_PTR, at, target.key(), 0);
      } else {
        code.r(Op.CX_INDEX_PTR, at, target.container(), target.key());
      }
      return new Expr(at, Type.PTR);
    }

    private Expr prefix() {
      Token token = kind();
      if (token == Token.AMP) {
        lexer.advance();
        return addressOf(postfix(prefix()));
      }
      if (token == Token.STAR) {
        lexer.advance();
        return dereference(prefix());
      }
      if (token == Token.INCREMENT || token == Token.DECREMENT) {
        lexer.advance();
        return change(prefix(), token, false);
      }
      if (token == Token.MINUS || token == Token.PLUS || token == Token.BANG || token == Token.TILDE) {
        lexer.advance();
        if (token == Token.MINUS && numeric(kind())) {
          Lexer.Number n = lexer.number(true);
          return new Expr(literal(n.type(), n.bits()).register(), n.type());
        }
        return unary(token, scalar(prefix()));
      }
      return postfix(primary());
    }

    private Expr primary() {
      if (numeric(kind())) {
        Lexer.Number n = lexer.number(false);
        return literal(n.type(), n.bits());
      }
      switch (kind()) {
        case TRUE -> {
          lexer.advance();
          return literal(Type.BOOL, 1);
        }
        case FALSE -> {
          lexer.advance();
          return literal(Type.BOOL, 0);
        }
        case NULL -> {
          lexer.advance();
          return literal(Type.NULL, 0);
        }
        case STRING -> {
          return string(lexer.string());
        }
        case PRINT -> {
          lexer.advance();
          Symbol symbol = librarySymbol("print");
          if (symbol == null) {
            throw lexer.error("The standard print library function is unavailable");
          }
          Expr function = libraryFunction(symbol);
          if (symbol.definition() instanceof LE_ExtObject && kind() == Token.LEFT_PAREN) {
            return call(function, null, true);
          }
          return function;
        }
        case FUNCTION -> {
          lexer.advance();
          return function(null);
        }
        case LEFT_BRACKET -> {
          return list();
        }
        case LEFT_BRACE -> {
          return table();
        }
        case LEFT_PAREN -> {
          lexer.advance();
          if (lexer.match(Token.RIGHT_PAREN)) {
            throw lexer.error("Empty parentheses are not an expression");
          }
          Expr first = expression();
          if (kind() == Token.COMMA) {
            throw lexer.error("Tuple expressions were removed; use a list literal '[...]'");
          }
          lexer.require(Token.RIGHT_PAREN);
          return first;
        }
        case IDENTIFIER -> {
          Token.Span name = lexer.advance();
          String text = lexer.text(name);
          @Nullable Binding binding = find(text);
          if (binding != null) {
            return variable(binding);
          }
          @Nullable Symbol symbol = library.resolve(text);
          if (text.equals("len") && kind() == Token.LEFT_PAREN
              && (symbol == null || symbol.definition() == BF_LIB.definition())) {
            lexer.require(Token.LEFT_PAREN);
            Expr argument = scalar(value(expression()));
            lexer.require(Token.RIGHT_PAREN);
            int result = temporary();
            code.r(Op.CX_LEN, result, argument.register(), 0);
            return new Expr(result, Type.INT);
          }
          if (symbol == null && kind() == Token.LEFT_PAREN) {
            Type target = Builtins.conversion(text);
            if (target != null) {
              return inlineConversion(target);
            }
            if (text.equals("str")) {
              return inlineString();
            }
          }
          if (symbol == null) {
            symbol = Builtins.standard().resolve(text);
          }
          if (symbol != null) {
            int at = temporary();
            int constantIndex = code.symbol(symbol);
            code.constant(at, constantIndex);
            LibraryEntry entry = symbol.definition();
            Type type = entry instanceof LE_Bits constant ? constant.type() : entry instanceof LE_String ? Type.STRING : Type.FUNCTION;
            if (entry instanceof LE_ExtObject && kind() == Token.LEFT_PAREN) {
              return call(new Expr(at, Type.FUNCTION), null, true);
            }
            return type == Type.FUNCTION
                ? new Expr(at, type, 1, false, new Target(null, -4, constantIndex), 1)
                : new Expr(at, type);
          }
          Expr key = string(text);
          return new Expr(-1, Type.INVALID, 1, false, new Target(null, -2, key.register()), 1);
        }
        default -> throw lexer.error("Expected expression, got '" + lexer.text(lexer.current()) + "'");
      }
    }

    private Expr inlineConversion(Type target) {
      lexer.require(Token.LEFT_PAREN);
      Expr argument = scalar(expression());
      if (kind() == Token.COMMA) {
        throw lexer.error("A conversion takes exactly one argument");
      }
      lexer.require(Token.RIGHT_PAREN);
      return convert(argument, target);
    }

    private Expr inlineString() {
      lexer.require(Token.LEFT_PAREN);
      Expr argument = scalar(expression());
      if (kind() == Token.COMMA) {
        throw lexer.error("str takes exactly one argument");
      }
      lexer.require(Token.RIGHT_PAREN);
      int result = temporary();
      code.r(Op.CX_TO_STRING, result, argument.register(), 0);
      return new Expr(result, Type.STRING);
    }

    private Expr postfix(Expr base) {
      for (; ; ) {
        if (lexer.match(Token.LEFT_BRACKET)) {
          base = scalar(base);
          Expr key = scalar(expression());
          lexer.require(Token.RIGHT_BRACKET);
          base = new Expr(-1, Type.INVALID, 1, false, new Target(null, base.register(), key.register()), 1);
        } else if (lexer.match(Token.DOT)) {
          Token.Span field = memberName();
          base = scalar(base);
          Expr key = string(lexer.text(field));
          base = new Expr(-1, Type.INVALID, 1, false, new Target(null, base.register(), key.register()), 1);
        } else if (lexer.match(Token.COLON)) {
          base = scalar(base);
          Token.Span field = memberName();
          Expr key = string(lexer.text(field));
          int method = temporary();
          code.r(Op.CX_GET_INDEX, method, base.register(), key.register());
          base = call(new Expr(method, Type.INVALID), base, false);
        } else if (kind() == Token.LEFT_PAREN) {
          base = call(scalar(base), null, false);
        } else if (kind() == Token.INCREMENT || kind() == Token.DECREMENT) {
          base = change(base, lexer.advance().kind(), true);
        } else {
          return base;
        }
      }
    }

    private Expr change(Expr operand, Token token, boolean postfix) {
      Target destination = target(operand);
      Expr old = scalar(operand);
      int result = temporary();
      code.r(token == Token.INCREMENT ? Op.CX_INCR : Op.CX_DECR, result, old.register(), 0);
      Expr updated = new Expr(result, old.type());
      write(destination, updated);
      return postfix ? new Expr(old.register(), old.type()) : updated;
    }

    private Expr container(Type type) {
      Expr size = literal(Type.INT, 0), layout = literal(Type.INT, type == Type.LIST ? ElxHeap.Layout.LIST.ordinal() : ElxHeap.Layout.TABLE.ordinal());
      int out = temporary();
      code.r(Op.CX_ALLOC, out, size.register(), layout.register());
      Expr tag = literal(Type.UBYTE, Byte.toUnsignedInt(type.tag()));
      code.r(Op.CX_PACK, out, tag.register(), out);
      return new Expr(out, type);
    }

    private Expr list() {
      lexer.require(Token.LEFT_BRACKET);
      Expr list = container(Type.LIST);
      int mark = next;
      while (kind() != Token.RIGHT_BRACKET) {
        Expr item = scalar(expression());
        code.r(Op.CX_LIST_APPEND, list.register(), item.register(), 0);
        next = mark;
        if (!lexer.match(Token.COMMA)) {
          break;
        }
      }
      lexer.require(Token.RIGHT_BRACKET);
      return list;
    }

    private Expr table() {
      lexer.require(Token.LEFT_BRACE);
      Expr table = container(Type.TABLE);
      int mark = next;
      while (lexer.match(Token.SEP)) {
      }
      while (kind() != Token.RIGHT_BRACE) {
        Expr key;
        if (lexer.match(Token.LEFT_BRACKET)) {
          key = scalar(expression());
          lexer.require(Token.RIGHT_BRACKET);
        } else if (kind() == Token.IDENTIFIER || kind() == Token.PRINT) {
          key = string(lexer.text(lexer.advance()));
        } else if (kind() == Token.STRING) {
          key = string(lexer.string());
        } else {
          throw lexer.error("Expected table key or [expression]");
        }
        lexer.require(Token.EQUAL);
        Expr item = scalar(expression());
        code.r(Op.CX_SET_TABLE, table.register(), key.register(), item.register());
        next = mark;
        if (!lexer.match(Token.COMMA)) {
          break;
        }
        while (lexer.match(Token.SEP)) {
        }
      }
      while (lexer.match(Token.SEP)) {
      }
      lexer.require(Token.RIGHT_BRACE);
      return table;
    }

    @SuppressWarnings("all")
    private Arguments arguments(@Nullable Expr receiver) {
      lexer.require(Token.LEFT_PAREN);
      List<Expr> args = new ArrayList<>();
      List<String> names = new ArrayList<>();
      List<CallSite.ArgumentKind> kinds = new ArrayList<>();
      if (receiver != null) {
        args.add(receiver);
        names.add("");
        kinds.add(CallSite.ArgumentKind.POSITIONAL);
      }
      boolean named = false;
      while (kind() != Token.RIGHT_PAREN) {
        String name = "";
        if (kind() == Token.IDENTIFIER && lexer.peek() == Token.EQUAL) {
          name = lexer.text(lexer.advance());
          lexer.advance();
        }
        CallSite.ArgumentKind argumentKind;
        if (lexer.match(Token.STAR_STAR)) {
          if (!name.isEmpty()) {
            throw lexer.error("A table spread cannot also have a name");
          }
          argumentKind = CallSite.ArgumentKind.TABLE_SPREAD;
          named = true;
        } else if (kind() == Token.STAR) {
          if (!name.isEmpty()) {
            throw lexer.error("A list spread cannot also have a name");
          }
          if (named) {
            throw lexer.error("Positional spread follows a named argument");
          }
          lexer.advance();
          Expr starred = dereference(prefix());
          if (kind() == Token.COMMA || kind() == Token.RIGHT_PAREN) {
            argumentKind = CallSite.ArgumentKind.LIST_SPREAD;
          } else {
            argumentKind = CallSite.ArgumentKind.POSITIONAL;
            starred = expression(starred, 1);
          }
          Expr arg = value(starred);
          args.add(arg);
          names.add(name);
          kinds.add(argumentKind);
          if (!lexer.match(Token.COMMA)) {
            break;
          }
          continue;
        } else if (!name.isEmpty()) {
          argumentKind = CallSite.ArgumentKind.NAMED;
          named = true;
        } else {
          if (named) {
            throw lexer.error("Positional argument follows a named argument");
          }
          argumentKind = CallSite.ArgumentKind.POSITIONAL;
        }
        Expr arg = value(expression());
        if (argumentKind == CallSite.ArgumentKind.LIST_SPREAD) {
          if (arg.type() != Type.INVALID && arg.type() != Type.LIST) {
            throw lexer.error("A positional spread requires a list");
          }
        } else if (argumentKind == CallSite.ArgumentKind.TABLE_SPREAD) {
          if (arg.type() != Type.INVALID && arg.type() != Type.TABLE) {
            throw lexer.error("A named spread requires a table");
          }
        } else {
          arg = scalar(arg);
        }
        args.add(arg);
        names.add(name);
        kinds.add(argumentKind);
        if (!lexer.match(Token.COMMA)) {
          break;
        }
      }
      lexer.require(Token.RIGHT_PAREN);
      if (args.size() > 255) {
        throw lexer.error("Call argument count exceeds 255");
      }
      return new Arguments(args, names, kinds);
    }

    private Expr call(Expr function, @Nullable Expr receiver, boolean external) {
      Arguments arguments = arguments(receiver);
      return callWithArguments(function, arguments, external);
    }

    private Expr callWithArguments(Expr function, Arguments arguments, boolean external) {
      List<Expr> parts = arguments.values();
      boolean named = arguments.kinds().stream().anyMatch(kind -> kind == CallSite.ArgumentKind.NAMED || kind == CallSite.ArgumentKind.TABLE_SPREAD);
      if (named) {
        Expr args = pack(parts);
        int out = reserve(MAX_RESULTS);
        int site = code.callSite(new CallSite(arguments.names(), arguments.kinds()));
        code.rx(Op.CX_CALL_NAMED, out, function.register(), args.register(), site);
        return new Expr(out, Type.INVALID, 1, false, null, MAX_RESULTS);
      }
      boolean dynamic = arguments.kinds().stream().anyMatch(kind -> kind == CallSite.ArgumentKind.LIST_SPREAD) || parts.stream().anyMatch(part -> part.available() == -1);
      if (dynamic) {
        Expr args = dynamicArguments(parts, arguments.kinds());
        int out = reserve(MAX_RESULTS);
        code.rx(Op.CX_CALL_PACKED, out, function.register(), args.register(), MAX_RESULTS | (parts.size() == 1 ? 0 : 256));
        return new Expr(out, Type.INVALID, 1, false, null, MAX_RESULTS);
      }
      Expr args = pack(parts);
      int out = reserve(MAX_RESULTS);
      if (external) {
        Expr member = literal(Type.NULL, 0);
        code.rx(Op.CX_EX_CALL, out, function.register(), member.register(), Instr.externalWindow(args.register(), args.width(), MAX_RESULTS));
      } else {
        code.rx(Op.CX_CALL, out, function.register(), args.register(), Instr.callWindow(args.width(), MAX_RESULTS));
      }
      return new Expr(out, Type.INVALID, 1, false, null, MAX_RESULTS);
    }

    private Expr unary(Token token, Expr operand) {
      Expr value = scalar(operand);
      Type type = value.type();
      int out = temporary();
      if (token == Token.BANG) {
        if (type != Type.TABLE) {
          booleanType(value);
        }
        code.r(Op.CX_BOOL_NOT, out, value.register(), 0);
        return new Expr(out, Type.BOOL);
      }
      if (token == Token.PLUS) {
        code.r(Op.CX_POS, out, value.register(), 0);
        return new Expr(out, type);
      }
      if (type != Type.INVALID && type != Type.TABLE && !type.numeric()) {
        throw lexer.error("Unary operator requires a number or table hook");
      }
      if (token == Token.TILDE && type.numeric() && !type.integer()) {
        throw lexer.error("Bitwise complement requires an integer");
      }
      Op op = token == Token.TILDE ? Op.BNOT : type.numeric() && !type.integer() ? Op.FNEG : Op.INEG;
      if (type.numeric()) {
        code.rx(op, out, value.register(), 0, Instr.control(type));
      } else {
        code.rx(Op.CX_OPERATOR, out, value.register(), value.register(), op.ordinal());
      }
      return new Expr(out, type);
    }

    private Expr convert(Expr value, Type target) {
      value = scalar(value);
      if (value.type() == target) {
        return value;
      }
      Type source = value.type();
      if (source != Type.INVALID && !source.numeric() && !(target == Type.BOOL && source == Type.TABLE)) {
        throw lexer.error("Invalid numeric conversion");
      }
      int out = temporary();
      code.immediate(Op.CX_CONVERT, out, value.register(), target.ordinal());
      return new Expr(out, target);
    }

    private void booleanType(Expr value) {
      if (value.multiple() || (value.type() != Type.INVALID && value.type() != Type.BOOL)) {
        throw lexer.error("Condition requires bool");
      }
    }

    private Expr binary(Token token, Expr left, Expr right) {
      left = scalar(left);
      right = scalar(right);
      int out = temporary();
      Type a = left.type(), b = right.type();
      if (token == Token.EQUAL_EQUAL) {
        code.r(Op.CX_EQUAL, out, left.register(), right.register());
        return new Expr(out, Type.BOOL);
      }
      if (token == Token.BANG_EQUAL) {
        code.rx(Op.CX_CMP, out, left.register(), right.register(), Instr.control(Type.INVALID, Instr.Relation.NE));
        return new Expr(out, Type.BOOL);
      }
      boolean compare = token == Token.LESS || token == Token.LESS_EQUAL || token == Token.GREATER || token == Token.GREATER_EQUAL;
      Op op = compare ? Op.ICMP : arithmetic(token);
      Type type = Type.INVALID;
      if (a.numeric() && b.numeric()) {
        type = Type.binary(a, b);
        left = convert(left, type);
        right = convert(right, type);
        if (compare) {
          code.rx(type.integer() ? (type.unsigned() ? Op.UCMP : Op.ICMP) : Op.FCMP, out, left.register(), right.register(), Instr.control(type, relation(token)));
        } else {
          if (!type.integer()) {
            op = switch (op) {
              case IADD -> Op.FADD;
              case ISUB -> Op.FSUB;
              case IMUL -> Op.FMUL;
              case IDIV -> Op.FDIV;
              case IREM -> Op.FREM;
              default -> throw lexer.error("Bitwise operator requires integers");
            };
          } else if (type.unsigned()) {
            if (op == Op.IDIV) {
              op = Op.UDIV;
            } else if (op == Op.IREM) {
              op = Op.UREM;
            }
          }
          code.rx(op, out, left.register(), right.register(), Instr.control(type));
        }
      } else {
        if (a != Type.INVALID && b != Type.INVALID && !(token == Token.PLUS && (a == Type.STRING || b == Type.STRING)) && !(a == b && (a == Type.TABLE || a == Type.LIST || a == Type.STRING)) && !(a == Type.LIST && b.integer() && token == Token.STAR)) {
          throw lexer.error("Invalid operand types: " + a + " / " + b);
        }
        if (compare) {
          code.rx(Op.CX_CMP, out, left.register(), right.register(), Instr.control(Type.INVALID, relation(token)));
        } else {
          code.r(integratedArithmetic(op), out, left.register(), right.register());
        }
        if (a == b && (a == Type.STRING || a == Type.LIST || a == Type.TABLE)) {
          type = a;
        }
      }
      return new Expr(out, compare ? Type.BOOL : type);
    }

    private record Body(boolean braced, boolean terminated) {
    }

    private record Arguments(List<Expr> values, List<String> names, List<CallSite.ArgumentKind> kinds) {
    }
  }
}
