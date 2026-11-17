package io.viki.elx.lib.std;

import io.viki.elx.lib.Builtins;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.lib.Span;
import io.viki.elx.vm.VM;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The {@code io} module: comma-separated print, whole-file text I/O,
 * existence checks, delete, append, and directory listing.
 * <p>
 * All text is UTF-8. Relative paths resolve against the Java process working
 * directory. File failures surface as {@link UncheckedIOException}, matching the
 * manual's host-error policy.
 */
public final class IoStd {
  private IoStd() {
  }

  /**
   * Builds the {@code io} module.
   *
   * @param vm owning VM, used for the output sink
   * @return frozen module library
   */
  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.define("print", Objects.requireNonNull(Builtins.standard().resolve("print")).definition());
    library.function("readText", args -> {
      args.arity(1);
      try {
        args.rs(Files.readString(Path.of(args.s(0))));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    library.function("writeText", args -> {
      args.arity(2);
      try {
        Files.writeString(Path.of(args.s(0)), args.s(1));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    library.function("appendText", args -> {
      args.arity(2);
      try {
        Files.writeString(Path.of(args.s(0)), args.s(1),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    library.function("exists", args -> {
      args.arity(1);
      args.rb(Files.exists(Path.of(args.s(0))));
    });
    library.function("delete", args -> {
      args.arity(1);
      try {
        args.rb(Files.deleteIfExists(Path.of(args.s(0))));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    library.function("listDir", args -> {
      args.arity(1);
      try (Stream<Path> stream = Files.list(Path.of(args.s(0)))) {
        String[] names = stream.map(path -> path.getFileName().toString()).toArray(String[]::new);
        Span result = args.scratch(1), item = args.scratch(1);
        args.containers().newList(result, 0, names.length);
        for (String name : names) {
          item.setString(0, name);
          args.containers().listAppend(result, 0, item, 0);
        }
        args.rvalue(result, 0);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    return library.freeze();
  }
}
