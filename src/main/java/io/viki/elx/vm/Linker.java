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
import io.viki.elx.asm.Decomposer;
import io.viki.elx.asm.Prototype;
import io.viki.elx.lib.*;
import io.viki.elx.memory.*;
import io.viki.elx.lib.*;
import io.viki.elx.memory.*;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-confined loader. New headers/pools/code/text are packed into one allocation
 * per module. Linking uses identity maps; CX_CALL reads primitive headers directly.
 * Borrows both heaps; unload releases loader roots while live values retain their code.
 */
final class Linker implements AutoCloseable {
  private static final int HEADER_SLOTS = 3;
  private final VM vm;
  private final ElxHeap heap;
  private final ExtHeap external;
  private final Memory memory;
  private final Reference2IntOpenHashMap<Prototype> ids = new Reference2IntOpenHashMap<>();
  private final Reference2ObjectOpenHashMap<Library, DynamicLibrary> libraries = new Reference2ObjectOpenHashMap<>();
  private final Reference2ObjectOpenHashMap<Prototype, Prototype> lowered = new Reference2ObjectOpenHashMap<>();
  private final Set<Prototype> roots = Collections.newSetFromMap(new IdentityHashMap<>());
  private @Nullable Prototype[] images = new Prototype[16];
  private long[] functions = new long[16], constants = new long[16], codes = new long[16];
  private int count;
  private int[] reusable = new int[16];
  private int reusableCount;

  /**
   * Creates a linker borrowing the VM's heaps and memory.
   *
   * @param vm owning VM used for host callback binding
   * @param heap internal heap receiving packed images
   * @param external external heap receiving host callbacks
   */
  Linker(VM vm, ElxHeap heap, ExtHeap external) {
    this.vm = vm;
    this.heap = heap;
    this.external = external;
    memory = heap.memory();
    ids.defaultReturnValue(-1);
  }

  /**
   * Returns the immutable image assigned to a VM-local function index.
   *
   * @param index VM-local image index
   * @return linked prototype
   * @throws NullPointerException if the image was already pruned
   */
  Prototype image(int index) {
    return Objects.requireNonNull(images[index], "Unloaded function image");
  }

  /**
   * Returns the constant-pool base for a linked image.
   *
   * @param index VM-local image index
   * @return constant-pool slot base
   */
  long constants(int index) {
    return constants[index];
  }

  /**
   * Returns the encoded code base for a linked image.
   *
   * @param index VM-local image index
   * @return raw instruction byte address
   */
  long code(int index) {
    return codes[index];
  }

  /**
   * Resolves a function or closure handle to its packed function header.
   *
   * @param function function handle or closure pointer
   * @return packed function-header address
   */
  long body(long function) {
    return Slot.type(memory, function) == Type.FUNCTION ? Slot.bits(memory, function) : function;
  }

  /**
   * Returns the VM-local image index encoded by a function handle.
   *
   * @param function function handle or closure pointer
   * @return image index, or an arbitrary negative value for a native handle
   */
  int id(long function) {
    return (int) Slot.bits(memory, body(function));
  }

  /**
   * Returns the external handle stored in a native function header.
   *
   * @param function native function header or closure handle
   * @return external callback handle
   */
  long nativeTarget(long function) {
    return Slot.bits(memory, body(function) + Slot.SIZE);
  }

  /**
   * Loads an image and all nested images into one packed allocation.
   *
   * @param prototype image to root and load
   * @return VM-local function handle
   */
  long load(Prototype prototype) {
    roots.add(prototype);
    int id = ids.getInt(prototype);
    if (id >= 0) {
      return functions[id];
    }
    List<Prototype> pending = new ArrayList<>();
    collect(prototype, pending, new Reference2IntOpenHashMap<>());
    pack(null, pending);
    return functions[ids.getInt(prototype)];
  }

  /**
   * Links a library, freezing its definitions and packing exports and code.
   * Linking is identity-cached, so repeated calls for the same library reuse
   * the existing VM-local export table.
   *
   * @param library library to freeze and link
   * @return VM-local export table
   */
  @SuppressWarnings("all")
  DynamicLibrary link(Library library) {
    DynamicLibrary known = libraries.getOrDefault(library, null);
    if (known != null) {
      return known;
    }
    library.freeze();
    List<Prototype> pending = new ArrayList<>();
    Reference2IntOpenHashMap<Prototype> seen = new Reference2IntOpenHashMap<>();
    for (int i = 0; i < library.size(); i++) {
      if (library.entry(i) instanceof LE_Function function) {
        collect(function.code(), pending, seen);
      }
    }
    pack(library, pending);
    return libraries.get(library);
  }

  private void collect(Prototype image, List<Prototype> pending, Reference2IntOpenHashMap<Prototype> seen) {
    if (ids.containsKey(image) || seen.containsKey(image)) {
      return;
    }
    seen.put(image, 1);
    pending.add(image);
    for (int i = 0; i < image.functionCount(); i++) {
      collect(image.function(i), pending, seen);
    }
  }

  private void pack(@Nullable Library library, List<Prototype> pending) {
    int exports = library == null ? 0 : library.size(), slots = exports, words = 0, textBytes = 0;
    for (Prototype image : pending) {
      slots = Math.addExact(slots, HEADER_SLOTS + Math.max(1, image.constantCount()));
      words = Math.addExact(words, image.wordCount());
      for (int i = 0; i < image.constantCount(); i++) {
        if (image.constantType(i) == Type.STRING && image.constantSymbol(i) == null && !image.constantLinked(i)) {
          textBytes = Math.addExact(textBytes, Integer.BYTES + image.stringLength(i));
        }
      }
    }
    byte[][] texts = new byte[exports][];
    for (int i = 0; i < exports; i++) {
      LibraryEntry entry = library.entry(i);
      if (entry instanceof LE_ExtObject) {
        slots = Math.addExact(slots, HEADER_SLOTS);
      } else if (entry instanceof LE_String text) {
        texts[i] = text.value().getBytes(StandardCharsets.UTF_8);
        textBytes = Math.addExact(textBytes, Integer.BYTES + texts[i].length);
      }
    }
    slots = Math.max(1, slots);
    int raw = Math.addExact(Math.multiplyExact(words, Integer.BYTES), textBytes);
    long base = heap.allocateImage(slots, raw), position = Slot.address(base, exports), codeAt = Slot.address(base, slots), textAt = codeAt + (long) words * Integer.BYTES;
    if (library != null) {
      libraries.put(library, new DynamicLibrary(library, memory, external, base, Math.addExact(slots * Slot.SIZE, raw)));
    }
    // Publish every script header before resolving any symbol, allowing dependency cycles during linkage.
    for (Prototype image : pending) {
      int id = reusableCount > 0 ? reusable[--reusableCount] : count++;
      if (id == images.length) {
        int size = id * 2;
        images = Arrays.copyOf(images, size);
        functions = Arrays.copyOf(functions, size);
        constants = Arrays.copyOf(constants, size);
        codes = Arrays.copyOf(codes, size);
      }
      ids.put(image, id);
      images[id] = image;
      functions[id] = position;
      constants[id] = position + HEADER_SLOTS * Slot.SIZE;
      codes[id] = codeAt;
      Slot.write(memory, position, Type.INT, id);
      Slot.write(memory, position + Slot.SIZE, Type.PTR, constants[id]);
      Slot.write(memory, position + 2L * Slot.SIZE, Type.PTR, codeAt);
      for (int pc = 0; pc < image.wordCount(); pc++) {
        memory.store32(codeAt + (long) pc * Integer.BYTES, image.word(pc));
      }
      position = Slot.address(constants[id], Math.max(1, image.constantCount()));
      codeAt += (long) image.wordCount() * Integer.BYTES;
    }
    // Export slots are also published before filling constant pools.
    for (int i = 0; i < exports; i++) {
      LibraryEntry entry = library.entry(i);
      long out = Slot.address(base, i);
      if (entry instanceof LE_Function function) {
        Slot.write(memory, out, Type.FUNCTION, functions[ids.getInt(function.code())]);
      } else if (entry instanceof LE_ExtObject nativeFunction) {
        Slot.write(memory, position, Type.INT, -1);
        Slot.write(memory, position + Slot.SIZE, Type.EXTERNAL, external.add(Args.bind(vm, nativeFunction.function())));
        Slot.write(memory, out, Type.FUNCTION, position);
        position += HEADER_SLOTS * Slot.SIZE;
      } else if (entry instanceof LE_Bits value) {
        Slot.write(memory, out, value.type(), value.bits());
      } else if (entry instanceof LE_String) {
        byte[] data = texts[i];
        memory.store32(textAt, data.length);
        for (int j = 0; j < data.length; j++) {
          memory.store8(textAt + Integer.BYTES + j, data[j]);
        }
        Slot.write(memory, out, Type.STRING, textAt);
        textAt += Integer.BYTES + data.length;
      }
    }
    for (Prototype image : pending) {
      int id = ids.getInt(image);
      long pool = constants[id];
      for (int i = 0; i < image.constantCount(); i++) {
        long out = Slot.address(pool, i);
        Symbol symbol = image.constantSymbol(i);
        if (symbol != null && image.baseOnly() && symbol.definition() instanceof LE_Function function) {
          Prototype dependency = lowered.computeIfAbsent(function.code(), Decomposer::lower);
          long header = load(dependency);
          roots.remove(dependency);
          Slot.write(memory, out, Type.FUNCTION, header);
          continue;
        }
        if (symbol != null) {
          DynamicLibrary module = link(symbol.library());
          Slot.copy(memory, out, module.entry(symbol.index()), 1);
          continue;
        }
        Type type = image.constantType(i);
        long bits = image.constantBits(i);
        if (!image.constantLinked(i)) {
          if (type == Type.FUNCTION) {
            bits = functions[ids.getInt(image.function((int) bits))];
          } else if (type == Type.STRING) {
            int length = image.stringLength(i), offset = image.stringOffset(i);
            memory.store32(textAt, length);
            for (int j = 0; j < length; j++) {
              memory.store8(textAt + Integer.BYTES + j, image.stringByte(offset + j));
            }
            bits = textAt;
            textAt += Integer.BYTES + length;
          }
        }
        Slot.write(memory, out, type, image.constantFlags(i), bits);
      }
    }
  }

  /**
   * Visits loader roots that keep linked code and library allocations alive.
   *
   * @param visitor root visitor used by the VM collector
   */
  void visitRoots(HeapVisitor visitor) {
    for (Prototype image : roots) {
      visitor.visit(Type.FUNCTION, functions[ids.getInt(image)]);
    }
    for (DynamicLibrary library : libraries.values()) {
      visitor.visit(Type.PTR, library.address());
    }
  }

  /**
   * Drops a loader root for an image; live function values may retain it.
   *
   * @param image image to unroot
   */
  void unload(Prototype image) {
    roots.remove(image);
  }

  /**
   * Drops a loader root for a linked library.
   *
   * @param library library to unroot
   */
  void unload(Library library) {
    libraries.remove(library);
  }

  void prune() {
    var iterator = ids.reference2IntEntrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (!heap.isAlive(functions[entry.getIntValue()])) {
        int id = entry.getIntValue();
        images[id] = null;
        functions[id] = constants[id] = codes[id] = 0;
        if (reusableCount == reusable.length) {
          reusable = Arrays.copyOf(reusable, reusableCount * 2);
        }
        reusable[reusableCount++] = id;
        iterator.remove();
      }
    }
    lowered.values().removeIf(image -> !ids.containsKey(image));
  }

  @Override
  public void close() {
    lowered.clear();
    roots.clear();
    ids.clear();
    libraries.clear();
    Arrays.fill(images, null);
    count = reusableCount = 0;
  }
}
