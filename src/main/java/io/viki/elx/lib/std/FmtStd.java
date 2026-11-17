package io.viki.elx.lib.std;

import io.viki.elx.Type;
import io.viki.elx.lib.Call;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.vm.VM;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.util.Locale;

/** Locale-independent printf formatting; callbacks run on the owning VM thread. */
public final class FmtStd {
  private FmtStd() {
  }

  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.function("format", args -> args.rs(format(args)));
    library.function("printf", args -> vm.output().accept(format(args)));
    return library.freeze();
  }

  private static String format(Call args) {
    if (args.size() == 0) {
      throw new IllegalArgumentException("fmt.format expects a format string");
    }
    @Nullable Object[] values = new Object[args.size() - 1];
    for (int i = 1; i < args.size(); i++) {
      Type type = args.type(i);
      values[i - 1] = switch (type) {
        case STRING -> args.s(i);
        case BOOL -> args.b(i);
        case BYTE, UBYTE, SHORT, USHORT, INT -> Math.toIntExact(args.i(i));
        case UINT, LONG -> args.i(i);
        case ULONG -> new BigInteger(Long.toUnsignedString(args.bits(i)));
        case FLOAT, DOUBLE -> args.d(i);
        case NULL -> null;
        default -> args.operations().format(args, i);
      };
    }
    return String.format(Locale.ROOT, args.s(0), values);
  }
}
