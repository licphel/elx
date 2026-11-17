package io.viki.elx.lib.std;

import io.viki.elx.Type;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.value.Pointers;
import io.viki.elx.vm.Slots;
import io.viki.elx.vm.VM;

/**
 * The {@code gc} module: pressure collection and explicit pin/unpin of managed
 * pointer handles.
 * <p>
 * {@code pin} requires a live non-stack pointer handle and retains it; {@code
 * unpin} releases one such retention. Pair them when retaining script pointers
 * across executions.
 */
public final class GcStd {
  private GcStd() {
  }

  /**
   * Builds the {@code gc} module.
   *
   * @param vm owning VM, used for collection and heap retention
   * @return frozen module library
   */
  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.function("collect", args -> {
      args.arity(0);
      Slots.vm(args).collect(true);
    });
    library.function("pin", args -> {
      args.arity(1);
      if (args.type(0) != Type.PTR) {
        throw new IllegalArgumentException("gc.pin expects a managed pointer handle");
      }
      long pointer = args.bits(0);
      if (!Pointers.canPin(Slots.heap(args), pointer)) {
        throw new IllegalArgumentException("gc.pin requires a live non-stack pointer handle");
      }
      Slots.heap(args).retain(pointer);
      args.rarg(0);
    });
    library.function("unpin", args -> {
      args.arity(1);
      if (args.type(0) != Type.PTR) {
        throw new IllegalArgumentException("gc.unpin expects a managed pointer handle");
      }
      Slots.heap(args).release(args.bits(0));
    });
    return library.freeze();
  }
}
