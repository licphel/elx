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

import io.viki.elx.memory.ExtHeap;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;

import java.util.Objects;

/**
 * Runtime-owned linked image: consecutive export Slots, function headers, constant
 * pools, instruction words and UTF-8 data in one managed allocation. Dependencies
 * reference already loaded images. Native implementations reside in the associated
 * external heap. Borrowed from its VM; after unload it needs an explicit heap pin
 * or another live script root. Addresses are not portable between VMs.
 */

/**
 * Runtime-owned linked image: consecutive export slots, function headers,
 * constant pools, instruction words, and UTF-8 data in one managed allocation.
 * Dependencies reference already loaded images; native implementations reside
 * in the associated external heap. The image is borrowed from its VM, addresses
 * are VM-local, and callers must retain a heap pin while using it across unload
 * boundaries.
 *
 * @param definitions linked symbol table for exported entries
 * @param memory      VM memory that owns the image allocation
 * @param external    external heap containing native callbacks and objects
 * @param address     base address of the linked image
 * @param bytes       allocation size in bytes
 */
public record DynamicLibrary(Library definitions, Memory memory, ExtHeap external, long address, int bytes) {
  /**
   * Returns the slot address for an exported definition.
   *
   * @param index definition index in {@link #definitions()}
   * @return VM-local slot address
   * @throws IndexOutOfBoundsException if {@code index} is outside the library
   */
  public long entry(int index) {
    Objects.checkIndex(index, definitions.size());
    return Slot.address(address, index);
  }
}
