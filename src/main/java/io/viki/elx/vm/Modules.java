/*
 * MIT License
 *
 * Copyright (c) 2026 Licphel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.ModuleResolver;
import io.viki.elx.lib.std.Std;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.HeapVisitor;
import io.viki.elx.memory.Slot;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Per-VM module cache. Resolvers are consulted in registration order before
 * filesystem and bundled-resource lookup; loaded module values remain rooted
 * until unload or VM close. Loading is thread-confined and detects circular
 * requires.
 */
public final class Modules implements AutoCloseable {
  private final VM vm;
  private final Map<String, Loaded> loaded = new HashMap<>();
  private final Set<String> loading = new LinkedHashSet<>();
  private final List<ModuleResolver> resolvers = new ArrayList<>();
  // One rooted name avoids repeated UTF-8 export for a hot require call without retaining every temporary name.
  private long lastName, lastModule;
  private Path directory = Path.of(".");

  Modules(VM vm) {
    this.vm = vm;
  }

  /**
   * Sets the base directory used by filesystem module lookup.
   *
   * @param directory base directory resolved before bundled resources
   * @throws NullPointerException if {@code directory} is null
   */
  public void directory(Path directory) {
    this.directory = directory;
  }

  /**
   * Adds a resolver consulted in registration order before filesystem lookup.
   *
   * @param resolver resolver to retain until removed by {@link #close()}
   * @throws NullPointerException if {@code resolver} is null
   */
  public void addHandler(ModuleResolver resolver) {
    resolvers.add(resolver);
  }

  /**
   * Requires a borrowed ELX string pointer and writes the module value to a slot.
   * A one-entry pointer cache avoids repeatedly exporting the same hot module name.
   *
   * @param name   ELX string pointer naming the module
   * @param result destination slot
   * @throws IllegalArgumentException if {@code name} is not an ELX string
   * @throws IllegalStateException    if the module cannot be loaded or a cycle is found
   */
  public void require(long name, long result) {
    if (lastName == name && name != 0) {
      Slot.copy(vm.memory(), result, lastModule, 1);
      return;
    }
    String text = vm.heap().exportString(name);
    require(text, result);
    lastName = name;
    Loaded module = loaded.get(text);
    lastModule = module == null ? 0 : module.value();
  }

  /**
   * Resolves, compiles or links, executes, caches, and returns a named module.
   * Resolvers run in registration order, followed by the configured directory
   * and bundled resources. A successful module remains rooted until unload.
   *
   * @param name   module name or resource stem
   * @param result destination slot receiving the module value
   * @throws NullPointerException  if {@code name} is null
   * @throws IllegalStateException if loading is circular or compilation/execution fails
   * @throws UncheckedIOException  if a filesystem or resource read fails
   */
  public void require(String name, long result) {
    Loaded known = loaded.get(name);
    if (known != null) {
      Slot.copy(vm.memory(), result, known.value(), 1);
      return;
    }
    if (!loading.add(name)) {
      throw new IllegalStateException("Circular require: " + loading + " -> " + name);
    }
    long cell = vm.heap().allocate(ElxHeap.Layout.VALUES, 1);
    vm.heap().retain(cell);
    try {
      ModuleResolver.Source source = source(name);
      Library nativeLibrary = Std.create(name, vm);
      Library symbols = nativeLibrary == null ? source.library() : nativeLibrary;
      Prototype image = new Compiler().compile(source.name(), source.text(), symbols, vm.optimization());
      try {
        int count = vm.execute(image);
        if (count == 0) {
          Slot.write(vm.memory(), cell, Type.BOOL, 1);
        } else {
          Slot.copy(vm.memory(), cell, vm.results(), 1);
        }
      } catch (RuntimeException | Error failure) {
        vm.unload(image);
        if (nativeLibrary != null) {
          vm.unload(nativeLibrary);
        }
        throw failure;
      }
      loaded.put(name, new Loaded(cell, image, nativeLibrary));
      Slot.copy(vm.memory(), result, cell, 1);
    } finally {
      vm.heap().release(cell);
      loading.remove(name);
    }
  }

  private ModuleResolver.Source source(String name) {
    ModuleResolver.Source builtin = builtinSource(name);
    if (builtin != null) {
      return builtin;
    }
    for (ModuleResolver resolver : resolvers) {
      ModuleResolver.Source source = resolver.resolve(name);
      if (source != null) {
        return source;
      }
    }
    name = name.endsWith(".elx") ? name : name + ".elx";
    Path path = directory.resolve(name).normalize();
    try {
      if (Files.exists(path)) {
        return new ModuleResolver.Source(path.toString(), Files.readString(path));
      }
      String resource = "/elx/" + (name);
      try (var input = Modules.class.getResourceAsStream(resource)) {
        if (input != null) {
          return new ModuleResolver.Source(resource, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
      throw new IOException("No module file or bundled resource at " + path + " / " + resource);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot require module '" + name + "' at " + path, failure);
    }
  }

  // A cached name can live inside a code image; unloading code must not keep that image alive.
  void invalidateNameCache() {
    lastName = lastModule = 0;
  }

  /**
   * Unloads a cached module and releases its linker root. Existing values and
   * function handles may keep the linked image alive until garbage collection.
   *
   * @param name module name
   * @throws IllegalStateException if the module is currently loading
   */
  public void unload(String name) {
    if (loading.contains(name)) {
      throw new IllegalStateException("Cannot unload executing module " + name);
    }
    invalidateNameCache();
    Loaded module = loaded.remove(name);
    if (module != null) {
      module.unload(vm);
    }
  }

  void visitRoots(HeapVisitor visitor) {
    if (lastName != 0) {
      visitor.visit(Type.STRING, lastName);
    }
    for (Loaded module : loaded.values()) {
      visitor.visit(Type.PTR, module.value());
    }
  }

  /**
   * Unloads every cached module and clears registered resolvers.
   * Repeated calls are harmless.
   */
  @Override
  public void close() {
    for (Loaded module : loaded.values()) {
      module.unload(vm);
    }
    invalidateNameCache();
    loaded.clear();
    resolvers.clear();
  }

  /**
   * Reads a built-in ELX module from the packaged {@code /std/} resource
   * directory.
   *
   * <p>Built-in scripts are compiled and cached like any other module, but
   * they take precedence over host resolvers and the filesystem so a host
   * cannot accidentally shadow the standard library. A name such as {@code
   * list} maps to {@code /std/list.elx}; dotted names map to nested
   * directories.</p>
   *
   * @param name requested module name
   * @return source description, or {@code null} when no packaged script exists
   */
  private ModuleResolver.@Nullable Source builtinSource(String name) {
    String path = "/std/" + name.replace('.', '/') + ".elx";
    try (InputStream stream = Modules.class.getResourceAsStream(path)) {
      if (stream == null) {
        return null;
      }
      String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      return new ModuleResolver.Source(path, text);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read built-in module " + path, e);
    }
  }

  private record Loaded(long value, Prototype image, @Nullable Library nativeLibrary) {
    private void unload(VM vm) {
      vm.unload(image);
      if (nativeLibrary != null) {
        vm.unload(nativeLibrary);
      }
    }
  }
}
