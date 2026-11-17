package io.viki.elx.lib.std;

import io.viki.elx.Type;
import io.viki.elx.lib.Call;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.lib.Span;
import io.viki.elx.value.ContOp;
import io.viki.elx.vm.VM;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict JSON conversion preserving lists, tables and explicit null. VM-thread confined. */
public final class JsonStd {
  /** Bound recursive input/output nesting before exhausting the Java stack. */
  private static final int MAX_DEPTH = 128;

  private JsonStd() {
  }

  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.function("parse", args -> {
      args.arity(1);
      Span result = args.scratch(1);
      Parser parser = new Parser(args, args.s(0));
      parser.value(result, 0, 0);
      parser.space();
      if (parser.at != parser.text.length()) {
        throw parser.error("Trailing input");
      }
      args.rvalue(result, 0);
    });
    library.function("stringify", args -> {
      args.arity(1);
      Writer writer = new Writer(args);
      writer.value(args, 0, 0);
      args.rs(writer.text.toString());
    });
    return library.freeze();
  }

  private static final class Parser {
    private final Call call;
    private final String text;
    private final List<Span> levels = new ArrayList<>();
    private int at;

    private Parser(Call call, String text) {
      this.call = call;
      this.text = text;
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException("JSON at character " + at + ": " + message);
    }

    private Span level(int depth) {
      while (levels.size() <= depth) {
        levels.add(call.scratch(2));
      }
      return levels.get(depth);
    }

    private void space() {
      while (at < text.length()) {
        char c = text.charAt(at);
        if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
          break;
        }
        at++;
      }
    }

    private boolean take(char c) {
      if (at < text.length() && text.charAt(at) == c) {
        at++;
        return true;
      }
      return false;
    }

    private void require(char c) {
      space();
      if (!take(c)) {
        throw error("Expected '" + c + "'");
      }
    }

    private void value(Span out, int index, int depth) {
      space();
      if (at == text.length()) {
        throw error("Expected a value");
      }
      char c = text.charAt(at);
      if ((c == '[' || c == '{') && depth >= MAX_DEPTH) {
        throw error("Nesting exceeds " + MAX_DEPTH);
      }
      if (c == '"') {
        out.setString(index, string());
        return;
      }
      if (c == '[') {
        at++;
        call.containers().newList(out, index, 0);
        Span item = level(depth);
        space();
        if (take(']')) {
          return;
        }
        do {
          value(item, 1, depth + 1);
          call.containers().listAppend(out, index, item, 1);
          item.setVoid(1);
          space();
          if (take(']')) {
            return;
          }
          require(',');
        } while (true);
      }
      if (c == '{') {
        at++;
        call.containers().newTable(out, index, 0);
        Span pair = level(depth);
        space();
        if (take('}')) {
          return;
        }
        do {
          space();
          if (at == text.length() || text.charAt(at) != '"') {
            throw error("Expected an object key");
          }
          pair.setString(0, string());
          require(':');
          value(pair, 1, depth + 1);
          call.containers().tableSet(out, index, pair, 0, pair, 1);
          pair.setVoid(0);
          pair.setVoid(1);
          space();
          if (take('}')) {
            return;
          }
          require(',');
        } while (true);
      }
      if (text.startsWith("true", at)) {
        at += 4;
        out.setBoolean(index, true);
        return;
      }
      if (text.startsWith("false", at)) {
        at += 5;
        out.setBoolean(index, false);
        return;
      }
      if (text.startsWith("null", at)) {
        at += 4;
        out.setNull(index);
        return;
      }
      number(out, index);
    }

    private void digits() {
      int start = at;
      while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
        at++;
      }
      if (at == start) {
        throw error("Expected a digit");
      }
    }

    private void number(Span out, int index) {
      int start = at;
      take('-');
      if (!take('0')) {
        digits();
      }
      boolean floating = false;
      if (take('.')) {
        floating = true;
        digits();
      }
      if (take('e') || take('E')) {
        floating = true;
        if (!take('+')) {
          take('-');
        }
        digits();
      }
      String number = text.substring(start, at);
      try {
        if (floating) {
          double value = Double.parseDouble(number);
          if (!Double.isFinite(value)) {
            throw error("Number outside finite double range");
          }
          out.setDouble(index, value);
        } else {
          long value;
          try {
            value = Long.parseLong(number);
          } catch (NumberFormatException signedOverflow) {
            if (number.startsWith("-")) {
              throw signedOverflow;
            }
            out.set(index, Type.ULONG, Long.parseUnsignedLong(number));
            return;
          }
          if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            out.setInt(index, (int) value);
          } else {
            out.setLong(index, value);
          }
        }
      } catch (NumberFormatException failure) {
        throw error("Invalid or out-of-range number '" + number + "'");
      }
    }

    private String string() {
      require('"');
      StringBuilder result = new StringBuilder();
      while (at < text.length()) {
        char c = text.charAt(at++);
        if (c == '"') {
          return result.toString();
        }
        if (c < ' ') {
          throw error("Unescaped control character");
        }
        if (c == '\\') {
          if (at == text.length()) {
            throw error("Unfinished escape");
          }
          c = text.charAt(at++);
          c = switch (c) {
            case '"', '\\', '/' -> c;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicode();
            default -> throw error("Unknown escape '" + c + "'");
          };
        }
        if (Character.isHighSurrogate(c)) {
          char low;
          if (at < text.length() && Character.isLowSurrogate(text.charAt(at))) {
            low = text.charAt(at++);
          } else {
            if (!take('\\') || !take('u')) {
              throw error("Missing low surrogate");
            }
            low = unicode();
          }
          if (!Character.isLowSurrogate(low)) {
            throw error("Invalid low surrogate");
          }
          result.append(c).append(low);
        } else {
          if (Character.isLowSurrogate(c)) {
            throw error("Unpaired low surrogate");
          }
          result.append(c);
        }
      }
      throw error("Unterminated string");
    }

    private char unicode() {
      if (at + 4 > text.length()) {
        throw error("Incomplete Unicode escape");
      }
      int value = 0;
      for (int i = 0; i < 4; i++) {
        char c = text.charAt(at++);
        int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10
            : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        if (digit < 0) {
          throw error("Invalid Unicode escape");
        }
        value = value * 16 + digit;
      }
      return (char) value;
    }
  }

  private static final class Writer {
    private final Call call;
    private final StringBuilder text = new StringBuilder();
    private final Set<Long> ancestors = new HashSet<>();
    private final List<Span> levels = new ArrayList<>();

    private Writer(Call call) {
      this.call = call;
    }

    private Span level(int depth) {
      while (levels.size() <= depth) {
        levels.add(call.scratch(2));
      }
      return levels.get(depth);
    }

    private void value(Span source, int index, int depth) {
      Type type = source.type(index);
      switch (type) {
        case NULL -> text.append("null");
        case BOOL -> text.append(source.b(index));
        case STRING -> quote(source.s(index));
        case FLOAT, DOUBLE -> {
          double value = source.d(index);
          if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON cannot encode non-finite numbers");
          }
          text.append(type == Type.FLOAT ? Float.toString((float) value) : Double.toString(value));
        }
        case BYTE, UBYTE, SHORT, USHORT, INT, UINT, LONG, ULONG -> text.append(
            type == Type.ULONG ? Long.toUnsignedString(source.bits(index)) : Long.toString(source.i(index)));
        case LIST, TABLE -> container(source, index, depth);
        default -> throw new IllegalArgumentException("JSON cannot encode " + type);
      }
    }

    private void container(Span source, int index, int depth) {
      if (depth >= MAX_DEPTH) {
        throw new IllegalArgumentException("JSON nesting exceeds " + MAX_DEPTH);
      }
      long handle = source.bits(index);
      if (!ancestors.add(handle)) {
        throw new IllegalArgumentException("JSON cannot encode cyclic containers");
      }
      Span item = level(depth);
      if (source.type(index) == Type.LIST) {
        text.append('[');
        int count = call.containers().listSize(source, index);
        for (int i = 0; i < count; i++) {
          if (i > 0) {
            text.append(',');
          }
          call.containers().listGet(source, index, i, item, 1);
          value(item, 1, depth + 1);
          item.setVoid(1);
        }
        text.append(']');
      } else {
        text.append('{');
        boolean first = true;
        try (ContOp.TableCursor cursor = call.containers().tableCursor(source, index)) {
          while (cursor.next()) {
            cursor.key(item, 0);
            cursor.value(item, 1);
            if (item.type(0) != Type.STRING) {
              throw new IllegalArgumentException("JSON object keys must be strings");
            }
            if (!first) {
              text.append(',');
            }
            first = false;
            quote(item.s(0));
            text.append(':');
            value(item, 1, depth + 1);
            item.setVoid(0);
            item.setVoid(1);
          }
        }
        text.append('}');
      }
      ancestors.remove(handle);
    }

    private void quote(String value) {
      text.append('"');
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        switch (c) {
          case '"' -> text.append("\\\"");
          case '\\' -> text.append("\\\\");
          case '\b' -> text.append("\\b");
          case '\f' -> text.append("\\f");
          case '\n' -> text.append("\\n");
          case '\r' -> text.append("\\r");
          case '\t' -> text.append("\\t");
          default -> {
            if (c < ' ') {
              text.append("\\u00");
              text.append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
            } else {
              text.append(c);
            }
          }
        }
      }
      text.append('"');
    }
  }
}
