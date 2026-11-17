package io.viki.elx.lib.std;

import io.viki.elx.lib.Library;
import io.viki.elx.vm.VM;
import org.jspecify.annotations.Nullable;

/**
 * Compile-time native symbols for bundled standard modules. The loader supplies
 * these definitions to each module's compiler; they are not runtime modules.
 */
public final class Std {
  private Std() {
  }

  /**
   * Creates one standard module library for a VM.
   *
   * @param name module name whose primitives are supplied during compilation
   * @param vm   VM that owns callbacks and heap operations
   * @return frozen module library, or {@code null} when the name is unknown
   */
  public static @Nullable Library create(String name, VM vm) {
    return switch (name) {
      case "list" -> NListStd.create(vm);
      case "table" -> NTableStd.create(vm);
      case "math" -> MathStd.create(vm);
      case "io" -> IoStd.create(vm);
      case "gc" -> GcStd.create(vm);
      case "str" -> StrStd.create(vm);
      case "sys" -> SysStd.create(vm);
      case "util" -> UtilStd.create(vm);
      case "re" -> ReStd.create(vm);
      case "path" -> PathStd.create(vm);
      case "json" -> JsonStd.create(vm);
      case "random" -> RandomStd.create(vm);
      case "time" -> TimeStd.create(vm);
      case "fmt" -> FmtStd.create(vm);
      default -> null;
    };
  }
}
