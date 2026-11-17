package io.viki.elx.lib.std;

import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.vm.VM;

import java.time.Instant;

/** Wall-clock milliseconds, monotonic nanoseconds and ISO instants; VM-thread confined. */
public final class TimeStd {
  private TimeStd() {
  }

  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.function("millis", args -> {
      args.arity(0);
      args.rl(System.currentTimeMillis());
    });
    library.function("nanos", args -> {
      args.arity(0);
      args.rl(System.nanoTime());
    });
    library.function("date", args -> {
      args.arity(0, 1);
      args.rs(Instant.ofEpochMilli(args.size() == 0 ? System.currentTimeMillis() : args.i(0)).toString());
    });
    library.function("parse", args -> {
      args.arity(1);
      args.rl(Instant.parse(args.s(0)).toEpochMilli());
    });
    library.function("sleep", args -> {
      args.arity(1);
      try {
        Thread.sleep(args.i(0));
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("time.sleep interrupted", failure);
      }
    });
    return library.freeze();
  }
}
