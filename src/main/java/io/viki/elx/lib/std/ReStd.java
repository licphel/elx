package io.viki.elx.lib.std;

import io.viki.elx.Type;
import io.viki.elx.lib.Call;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.lib.Span;
import io.viki.elx.vm.VM;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java regular expressions with code-point offsets and raw replacement syntax. VM-thread confined. */
public final class ReStd {
  private ReStd() {
  }

  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.constant("caseInsensitive", Type.INT, Pattern.CASE_INSENSITIVE);
    library.constant("multiline", Type.INT, Pattern.MULTILINE);
    library.constant("dotAll", Type.INT, Pattern.DOTALL);
    library.constant("unicodeCase", Type.INT, Pattern.UNICODE_CASE);
    library.function("escape", args -> {
      args.arity(1);
      args.rs(Pattern.quote(args.s(0)));
    });
    library.function("matches", args -> {
      args.arity(2, 3);
      args.rb(pattern(args, 2).matcher(args.s(0)).matches());
    });
    library.function("find", args -> {
      args.arity(2, 4);
      String text = args.s(0);
      Matcher match = pattern(args, 3).matcher(text);
      int start = args.size() >= 3 ? Math.toIntExact(args.i(2)) : 0;
      int length = text.codePointCount(0, text.length());
      if (start < 0 || start > length) {
        throw new IllegalArgumentException("re.find start outside string: " + start);
      }
      if (!match.find(text.offsetByCodePoints(0, start))) {
        args.rv();
        return;
      }
      Span result = args.scratch(1), workspace = args.scratch(5);
      workspace.setInt(3, text.codePointCount(0, match.start()));
      workspace.setInt(4, text.codePointCount(0, match.end()));
      match(args, match, result, workspace);
      args.rvalue(result, 0);
    });
    library.function("findAll", args -> {
      args.arity(2, 3);
      String text = args.s(0);
      Matcher match = pattern(args, 2).matcher(text);
      Span result = args.scratch(1), item = args.scratch(1), workspace = args.scratch(5);
      // Prefix offsets avoid rescanning the input for each match.
      int[] offsets = new int[text.length() + 1];
      for (int i = 0; i < text.length(); i++) {
        boolean lowHalf = i > 0 && Character.isHighSurrogate(text.charAt(i - 1))
            && Character.isLowSurrogate(text.charAt(i));
        offsets[i + 1] = offsets[i] + (lowHalf ? 0 : 1);
      }
      args.containers().newList(result, 0, 0);
      while (match.find()) {
        workspace.setInt(3, offsets[match.start()]);
        workspace.setInt(4, offsets[match.end()]);
        match(args, match, item, workspace);
        args.containers().listAppend(result, 0, item, 0);
      }
      args.rvalue(result, 0);
    });
    library.function("replace", args -> {
      args.arity(3, 4);
      args.rs(pattern(args, 3).matcher(args.s(0)).replaceAll(args.s(2)));
    });
    library.function("split", args -> {
      args.arity(2, 3);
      String[] parts = pattern(args, 2).split(args.s(0), -1);
      Span result = args.scratch(1), item = args.scratch(1);
      args.containers().newList(result, 0, parts.length);
      for (String part : parts) {
        item.setString(0, part);
        args.containers().listAppend(result, 0, item, 0);
      }
      args.rvalue(result, 0);
    });
    return library.freeze();
  }

  private static Pattern pattern(Call args, int flagIndex) {
    return Pattern.compile(args.s(1), args.size() > flagIndex ? Math.toIntExact(args.i(flagIndex)) : 0);
  }

  private static void match(Call args, Matcher match, Span result, Span pair) {
    args.containers().newTable(result, 0, 3);
    pair.setString(0, "start");
    pair.copy(1, pair, 3);
    args.containers().tableSet(result, 0, pair, 0, pair, 1);
    pair.setString(0, "end");
    pair.copy(1, pair, 4);
    args.containers().tableSet(result, 0, pair, 0, pair, 1);
    args.containers().newList(pair, 2, match.groupCount() + 1);
    for (int i = 0; i <= match.groupCount(); i++) {
      String group = match.group(i);
      if (group == null) {
        pair.setNull(1);
      } else {
        pair.setString(1, group);
      }
      args.containers().listAppend(pair, 2, pair, 1);
    }
    pair.setString(0, "groups");
    args.containers().tableSet(result, 0, pair, 0, pair, 2);
  }
}
