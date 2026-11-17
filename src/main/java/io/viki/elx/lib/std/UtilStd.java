package io.viki.elx.lib.std;

import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.vm.VM;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * The {@code util} module: Base64 encode/decode and random UUID generation.
 * <p>
 * Base64 operates on UTF-8 bytes. The ELX wrapper also exports the date/time
 * aliases supplied by {@code time}. Callbacks are confined to the owning VM thread.
 */
public final class UtilStd {
  private UtilStd() {
  }

  /**
   * Builds the {@code util} module.
   *
   * @param vm owning VM (unused by current operations, kept for signature symmetry)
   * @return frozen module library
   */
  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.function("encodeBase64", args -> {
      args.arity(1);
      args.rs(Base64.getEncoder()
          .encodeToString(args.s(0).getBytes(StandardCharsets.UTF_8)));
    });
    library.function("decodeBase64", args -> {
      args.arity(1);
      args.rs(new String(
          Base64.getDecoder().decode(args.s(0)), StandardCharsets.UTF_8));
    });
    library.function("genUuid", args -> {
      args.arity(0);
      args.rs(UUID.randomUUID().toString());
    });
    return library.freeze();
  }
}
