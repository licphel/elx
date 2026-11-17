package io.viki.elx.lib.std;

import io.viki.elx.Type;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.vm.VM;

/**
 * The {@code math} module: unary double functions, {@code pow}, {@code fmod},
 * {@code modf}, and extrema. Random generation belongs to {@code random}.
 * <p>
 * All unary functions and {@code pow}/{@code fmod} return {@code double}, matching
 * the manual's numeric model. Callbacks are confined to their owning VM thread.
 */
public final class MathStd {
  private MathStd() {
  }

  /**
   * Builds the {@code math} module.
   *
   * @param vm owning VM
   * @return frozen module library
   */
  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    StdUtil.unaryDouble(library, "abs", Math::abs);
    StdUtil.unaryDouble(library, "sqrt", Math::sqrt);
    StdUtil.unaryDouble(library, "floor", Math::floor);
    StdUtil.unaryDouble(library, "ceil", Math::ceil);
    StdUtil.unaryDouble(library, "sin", Math::sin);
    StdUtil.unaryDouble(library, "cos", Math::cos);
    StdUtil.unaryDouble(library, "tan", Math::tan);
    StdUtil.unaryDouble(library, "asin", Math::asin);
    StdUtil.unaryDouble(library, "acos", Math::acos);
    StdUtil.unaryDouble(library, "atan", Math::atan);
    StdUtil.unaryDouble(library, "log", Math::log);
    StdUtil.unaryDouble(library, "exp", Math::exp);
    StdUtil.unaryDouble(library, "to_degrees", Math::toDegrees);
    StdUtil.unaryDouble(library, "to_radians", Math::toRadians);

    library.constant("pi", Type.DOUBLE, Double.doubleToRawLongBits(Math.PI));
    library.constant("inf", Type.DOUBLE, Double.doubleToRawLongBits(Double.POSITIVE_INFINITY));

    library.function("pow", args -> {
      args.arity(2);
      args.rd(Math.pow(args.d(0), args.d(1)));
    });
    library.function("fmod", args -> {
      args.arity(2);
      double x = args.d(0), y = args.d(1);
      double quotient = x / y;
      double truncated = (x < 0) != (y < 0) ? Math.ceil(quotient) : Math.floor(quotient);
      args.rd(x - y * truncated);
    });
    library.function("modf", args -> {
      args.arity(1);
      double value = args.d(0);
      double integral = value < 0 ? Math.ceil(value) : Math.floor(value);
      args.rd(integral);
      args.rd(value - integral);
    });
    library.function("min", args -> StdUtil.extreme(args, true));
    library.function("max", args -> StdUtil.extreme(args, false));
    return library.freeze();
  }
}
