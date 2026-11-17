package io.viki.elx.lib.std;

import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.vm.VM;

import java.io.File;
import java.nio.file.Path;

/** Filesystem path manipulation without I/O, using the host platform's syntax. VM-thread confined. */
public final class PathStd {
  private PathStd() {
  }

  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.text("separator", File.separator);
    library.function("normalize", args -> {
      args.arity(1);
      args.rs(Path.of(args.s(0)).normalize().toString());
    });
    library.function("absolute", args -> {
      args.arity(1);
      args.rs(Path.of(args.s(0)).toAbsolutePath().normalize().toString());
    });
    library.function("isAbsolute", args -> {
      args.arity(1);
      args.rb(Path.of(args.s(0)).isAbsolute());
    });
    library.function("cwd", args -> {
      args.arity(0);
      args.rs(Path.of("").toAbsolutePath().toString());
    });
    library.function("join", args -> {
      if (args.size() == 0) {
        throw new IllegalArgumentException("path.join expects at least one path");
      }
      Path result = Path.of(args.s(0));
      for (int i = 1; i < args.size(); i++) {
        result = result.resolve(args.s(i));
      }
      args.rs(result.normalize().toString());
    });
    library.function("relative", args -> {
      args.arity(2);
      args.rs(Path.of(args.s(0)).normalize().relativize(Path.of(args.s(1)).normalize()).toString());
    });
    library.function("parent", args -> {
      args.arity(1);
      Path parent = Path.of(args.s(0)).getParent();
      if (parent == null) {
        args.rv();
      } else {
        args.rs(parent.toString());
      }
    });
    library.function("name", args -> {
      args.arity(1);
      Path name = Path.of(args.s(0)).getFileName();
      args.rs(name == null ? "" : name.toString());
    });
    library.function("stem", args -> {
      args.arity(1);
      Path file = Path.of(args.s(0)).getFileName();
      String name = file == null ? "" : file.toString();
      int dot = name.lastIndexOf('.');
      args.rs(dot <= 0 ? name : name.substring(0, dot));
    });
    library.function("extension", args -> {
      args.arity(1);
      Path file = Path.of(args.s(0)).getFileName();
      String name = file == null ? "" : file.toString();
      int dot = name.lastIndexOf('.');
      args.rs(dot <= 0 ? "" : name.substring(dot));
    });
    return library.freeze();
  }
}
