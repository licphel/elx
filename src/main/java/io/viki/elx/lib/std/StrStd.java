package io.viki.elx.lib.std;

import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.lib.Span;
import io.viki.elx.vm.VM;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The {@code str} module: code-point-aware string operations.
 * <p>
 * All indices and lengths are measured in Unicode code points, matching the
 * manual's {@code len} semantics. {@code bytelen} is the only operation that
 * measures encoded UTF-8 bytes. Functions that search return {@code -1} when no
 * match is found. Functions that require an in-range index throw
 * {@link IllegalArgumentException} otherwise.
 * <p>
 * Regular-expression operations live in the separate {@code re} module so this
 * module stays free of {@link Pattern} state.
 */
public final class StrStd {
  private StrStd() {
  }

  /**
   * Builds the {@code str} module.
   *
   * @param vm owning VM (unused by current operations, kept for signature symmetry)
   * @return frozen module library
   */
  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();

    library.function("charAt", args -> {
      args.arity(2);
      String s = args.s(0);
      int index = codePointIndex(s, Math.toIntExact(args.i(1)));
      int cp = s.codePointAt(index);
      args.rs(new String(Character.toChars(cp)));
    });
    library.function("codeAt", args -> {
      args.arity(2);
      String s = args.s(0);
      int index = codePointIndex(s, Math.toIntExact(args.i(1)));
      args.ri(s.codePointAt(index));
    });
    library.function("fromCode", args -> {
      args.arity(1);
      int cp = Math.toIntExact(args.i(0));
      if (!Character.isValidCodePoint(cp)) {
        throw new IllegalArgumentException("invalid Unicode code point: " + cp);
      }
      args.rs(new String(Character.toChars(cp)));
    });

    library.function("toUpper", args -> {
      args.arity(1);
      args.rs(args.s(0).toUpperCase(Locale.ROOT));
    });
    library.function("toLower", args -> {
      args.arity(1);
      args.rs(args.s(0).toLowerCase(Locale.ROOT));
    });

    library.function("sub", args -> {
      args.arity(2, 3);
      String s = args.s(0);
      int from = Math.toIntExact(args.i(1));
      int end = args.size() == 3
          ? Math.toIntExact(args.i(2))
          : s.codePointCount(0, s.length());
      if (from < 0 || end < from || end > s.codePointCount(0, s.length())) {
        throw new IllegalArgumentException("sub range out of bounds");
      }
      args.rs(s.substring(s.offsetByCodePoints(0, from), s.offsetByCodePoints(0, end)));
    });
    library.function("trim", args -> {
      args.arity(1);
      args.rs(args.s(0).trim());
    });
    library.function("trimStart", args -> {
      args.arity(1);
      String s = args.s(0);
      int i = 0;
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
      args.rs(s.substring(i));
    });
    library.function("trimEnd", args -> {
      args.arity(1);
      String s = args.s(0);
      int i = s.length();
      while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
        i--;
      }
      args.rs(s.substring(0, i));
    });

    library.function("contains", args -> {
      args.arity(2);
      args.rb(args.s(0).contains(args.s(1)));
    });
    library.function("containsIgnoreCase", args -> {
      args.arity(2);
      args.rb(args.s(0)
          .toLowerCase(Locale.ROOT).contains(args.s(1).toLowerCase(Locale.ROOT)));
    });
    library.function("startsWith", args -> {
      args.arity(2);
      args.rb(args.s(0).startsWith(args.s(1)));
    });
    library.function("endsWith", args -> {
      args.arity(2);
      args.rb(args.s(0).endsWith(args.s(1)));
    });
    library.function("index", args -> {
      args.arity(2, 3);
      String s = args.s(0), part = args.s(1);
      int from = args.size() == 3 ? Math.toIntExact(args.i(2)) : 0;
      int byteIndex = s.indexOf(part, s.offsetByCodePoints(0, Math.min(from, s.codePointCount(0, s.length()))));
      args.ri(byteIndex < 0 ? -1 : s.codePointCount(0, byteIndex));
    });
    library.function("lastIndex", args -> {
      args.arity(2);
      String s = args.s(0), part = args.s(1);
      int byteIndex = s.lastIndexOf(part);
      args.ri(byteIndex < 0 ? -1 : s.codePointCount(0, byteIndex));
    });

    library.function("isSameIgnoreCase", args -> {
      args.arity(2);
      args.rb(args.s(0).equalsIgnoreCase(args.s(1)));
    });

    library.function("replace", args -> {
      args.arity(3);
      args.rs(args.s(0)
          .replace(args.s(1), args.s(2)));
    });
    library.function("split", args -> {
      args.arity(2);
      String text = args.s(0), separator = args.s(1);
      if (separator.isEmpty()) {
        throw new IllegalArgumentException("split separator must not be empty");
      }
      String[] parts = text.split(Pattern.quote(separator), -1);
      Span result = args.scratch(1), item = args.scratch(1);
      args.containers().newList(result, 0, parts.length);
      for (String part : parts) {
        item.setString(0, part);
        args.containers().listAppend(result, 0, item, 0);
      }
      args.rvalue(result, 0);
    });

    library.function("join", args -> {
      String separator = args.s(0);
      StringBuilder result = new StringBuilder();
      for (int i = 1; i < args.size(); i++) {
        if (i > 1) {
          result.append(separator);
        }
        result.append(args.s(i));
      }
      args.rs(result.toString());
    });

    library.function("repeat", args -> {
      args.arity(2);
      String text = args.s(0);
      long count = args.i(1);
      if (count < 0 || count > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("repeat count out of range");
      }
      args.rs(text.repeat((int) count));
    });
    library.function("padStart", args -> {
      args.arity(2, 3);
      String s = args.s(0);
      int target = Math.toIntExact(args.i(1));
      String pad;
      pad = args.size() == 3 ? args.s(2) : " ";
      args.rs(pad(s, target, pad, true));
    });
    library.function("padEnd", args -> {
      args.arity(2, 3);
      String s = args.s(0);
      int target = Math.toIntExact(args.i(1));
      String pad;
      pad = args.size() == 3 ? args.s(2) : " ";
      args.rs(pad(s, target, pad, false));
    });

    library.function("format", args -> {
      if (args.size() < 1) {
        throw new IllegalArgumentException("format expects a template string");
      }
      String template = args.s(0);
      StringBuilder out = new StringBuilder();
      int argIndex = 1;
      for (int i = 0; i < template.length(); i++) {
        char c = template.charAt(i);
        if (c == '{' && i + 1 < template.length() && template.charAt(i + 1) == '}') {
          if (argIndex >= args.size()) {
            throw new IllegalArgumentException("format placeholder without argument");
          }
          out.append(args.operations().format(args, argIndex++));
          i++;
        } else {
          out.append(c);
        }
      }
      args.rs(out.toString());
    });

    return library.freeze();
  }

  /**
   * Resolves a code-point index to a UTF-16 char index, rejecting out-of-range
   * values.
   *
   * @param text  source text
   * @param index zero-based code-point index
   * @return UTF-16 offset for the code point
   * @throws IllegalArgumentException when the index is outside the text
   */
  private static int codePointIndex(String text, int index) {
    int length = text.codePointCount(0, text.length());
    if (index < 0 || index >= length) {
      throw new IllegalArgumentException("index out of bounds: " + index + " (length " + length + ")");
    }
    return text.offsetByCodePoints(0, index);
  }

  /**
   * Pads a string to a target code-point length on the left or right.
   *
   * @param text   source text
   * @param target desired code-point length
   * @param pad    padding text; must contain at least one code point
   * @param start  {@code true} to pad on the left, {@code false} on the right
   * @return padded text, or the original text when already long enough
   */
  private static String pad(String text, int target, String pad, boolean start) {
    int length = text.codePointCount(0, text.length());
    if (length >= target) {
      return text;
    }
    if (pad.codePointCount(0, pad.length()) == 0) {
      throw new IllegalArgumentException("pad text must not be empty");
    }
    StringBuilder padding = new StringBuilder();
    int needed = target - length;
    int padLength = pad.codePointCount(0, pad.length());
    while (needed > 0) {
      int take = Math.min(needed, padLength);
      padding.append(pad, 0, pad.offsetByCodePoints(0, take));
      needed -= take;
    }
    return start ? padding + text : text + padding;
  }
}
