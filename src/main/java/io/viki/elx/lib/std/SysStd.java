package io.viki.elx.lib.std;

import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.vm.Slots;
import io.viki.elx.vm.VM;

/**
 * The {@code sys} module: module unloading, table entry removal, and raw value
 * identity. The environment export is installed separately by the module loader
 * as a rooted value; this builder only supplies the operations.
 */
public final class SysStd {
  private SysStd() {
  }

  /**
   * Builds the {@code sys} module.
   *
   * @param vm owning VM, used for module cache and heap access
   * @return frozen module library
   */
  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.function("unload", args -> {
      args.arity(1);
      Slots.vm(args).modules().unload(args.s(0));
    });
    library.function("remove", args -> {
      args.arity(2);
      args.rb(args.containers().tableRemove(args, 0, args, 1));
    });
    library.function("identity", args -> {
      args.arity(1);
      args.rl(args.bits(0));
    });
    return library.freeze();
  }
}
