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

package io.viki.elx.lib;

import io.viki.elx.Type;
import io.viki.elx.asm.Prototype;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;

/**
 * Mutable, thread-confined definition table. Linkage freezes it; frozen instances
 * can be shared by independent runtimes, each assigning its own addresses once.
 */
public final class SimpleLibrary implements Library {
  private final Object2ObjectOpenHashMap<String, Symbol> symbols = new Object2ObjectOpenHashMap<>();
  private final ArrayList<LibraryEntry> entries = new ArrayList<>();
  private final ArrayList<String> names = new ArrayList<>();
  private boolean frozen;

  /**
   * Adds a named definition while this table is mutable.
   *
   * @param name  unique source-level name
   * @param entry definition payload
   * @return this library for fluent assembly
   * @throws IllegalStateException    if the table was frozen
   * @throws IllegalArgumentException if the name is empty or already defined
   */
  public SimpleLibrary define(String name, LibraryEntry entry) {
    if (frozen) {
      throw new IllegalStateException("Library has been frozen by its loader");
    }
    if (name.isEmpty() || symbols.containsKey(name)) {
      throw new IllegalArgumentException("Empty or duplicate library symbol: " + name);
    }
    int index = entries.size();
    entries.add(entry);
    names.add(name);
    symbols.put(name, new Symbol(this, index));
    return this;
  }

  /**
   * Registers a closure-free compiled function.
   *
   * @param name source-level function name
   * @param code function prototype
   * @return this library
   */
  public SimpleLibrary function(String name, Prototype code) {
    return define(name, new LE_Function(code));
  }

  /**
   * Registers a host callback.
   *
   * @param name     source-level function name
   * @param function host implementation
   * @return this library
   */
  public SimpleLibrary function(String name, HostFunction function) {
    return define(name, new LE_ExtObject(function));
  }

  /**
   * Registers a scalar constant using its runtime type and raw payload.
   *
   * @param name source-level constant name
   * @param type scalar type
   * @param bits encoded value bits
   * @return this library
   */
  public SimpleLibrary constant(String name, Type type, long bits) {
    return define(name, new LE_Bits(type, bits));
  }

  /**
   * Registers a text constant.
   *
   * @param name  source-level constant name
   * @param value text value
   * @return this library
   */
  public SimpleLibrary text(String name, String value) {
    return define(name, new LE_String(value));
  }

  @SuppressWarnings("all")
  @Override
  public @Nullable Symbol resolve(String name) {
    return symbols.getOrDefault(name, null);
  }

  @Override
  public int size() {
    return entries.size();
  }

  @Override
  public String name(int index) {
    return names.get(index);
  }

  @Override
  public LibraryEntry entry(int index) {
    return entries.get(index);
  }

  @Override
  public SimpleLibrary freeze() {
    frozen = true;
    return this;
  }
}
