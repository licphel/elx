package io.viki.elx.lib.std;

import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.vm.VM;

import java.util.Random;

/** Per-module random state, confined to its owning VM thread. */
public final class RandomStd {
  private RandomStd() {
  }

  public static Library create(VM vm) {
    Random random = new Random();
    SimpleLibrary library = new SimpleLibrary();
    library.function("seed", args -> {
      args.arity(1);
      random.setSeed(args.i(0));
    });
    library.function("rand", args -> {
      args.arity(2);
      int min = Math.toIntExact(args.i(0)), max = Math.toIntExact(args.i(1));
      if (min > max) {
        throw new IllegalArgumentException("random.rand requires min <= max");
      }
      args.ri((int) random.nextLong(min, (long) max + 1));
    });
    library.function("randl", args -> {
      args.arity(2);
      long min = args.i(0), max = args.i(1);
      if (min > max) {
        throw new IllegalArgumentException("random.randl requires min <= max");
      }
      long width = max - min + 1;
      long result;
      if (width > 0) {
        result = min + random.nextLong(width);
      } else {
        do {
          result = random.nextLong();
        } while (result < min || result > max);
      }
      args.rl(result);
    });
    library.function("randf", args -> {
      args.arity(0, 2);
      if (args.size() == 0) {
        args.rf(random.nextFloat());
        return;
      }
      if (args.size() != 2) {
        throw new IllegalArgumentException("random.randf expects zero or two arguments");
      }
      float min = (float) args.d(0), max = (float) args.d(1);
      if (!Float.isFinite(min) || !Float.isFinite(max) || min > max) {
        throw new IllegalArgumentException("random.randf requires finite min <= max");
      }
      if (min == max) {
        args.rf(min);
        return;
      }
      float result = (float) random.nextDouble(min, max);
      args.rf(result < max ? result : Math.nextDown(max));
    });
    library.function("randd", args -> {
      args.arity(0, 2);
      if (args.size() == 0) {
        args.rd(random.nextDouble());
        return;
      }
      if (args.size() != 2) {
        throw new IllegalArgumentException("random.randd expects zero or two arguments");
      }
      double min = args.d(0), max = args.d(1);
      if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) {
        throw new IllegalArgumentException("random.randd requires finite min <= max");
      }
      if (min == max) {
        args.rd(min);
        return;
      }
      double fraction = random.nextDouble(), width = max - min;
      double result = Double.isInfinite(width)
          ? min * (1.0 - fraction) + max * fraction : min + width * fraction;
      args.rd(result < max ? result : Math.nextDown(max));
    });
    library.function("randb", args -> {
      args.arity(0);
      args.rb(random.nextBoolean());
    });
    return library.freeze();
  }
}
