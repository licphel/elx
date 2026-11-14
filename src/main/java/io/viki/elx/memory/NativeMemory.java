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

package io.viki.elx.memory;

import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;

/**
 * Foreign-memory-backed {@link Memory} implementation. The segment is allocated
 * from a confined resource scope, so all accesses and lifecycle operations must
 * remain on the creating thread.
 */
public final class NativeMemory extends AbstractMemory {
  private ResourceScope scope;
  private MemorySegment segment;

  /** Creates native memory with the default initial capacity. */
  public NativeMemory() {
    this(4096);
  }

  /**
   * Creates native memory with at least {@code capacity} bytes reserved.
   *
   * @param capacity initial byte capacity
   * @throws IllegalArgumentException if {@code capacity} is less than one
   */
  public NativeMemory(int capacity) {
    super(capacity);
    scope = ResourceScope.newConfinedScope();
    try {
      segment = MemorySegment.allocateNative(capacity, 8, scope);
    } catch (RuntimeException | Error failure) {
      scope.close();
      throw failure;
    }
  }

  @Override
  protected void resize(int size) {
    ResourceScope replacementScope = ResourceScope.newConfinedScope();
    MemorySegment replacement;
    try {
      replacement = MemorySegment.allocateNative(size, 8, replacementScope);
      replacement.asSlice(0, capacity).copyFrom(segment);
    } catch (RuntimeException | Error failure) {
      replacementScope.close();
      throw failure;
    }
    scope.close();
    scope = replacementScope;
    segment = replacement;
  }

  @Override
  protected void dispose() {
    scope.close();
  }

  @Override
  public byte load8(long a) {
    return MemoryAccess.getByteAtOffset(segment, a);
  }

  @Override
  public short load16(long a) {
    return MemoryAccess.getShortAtOffset(segment, a);
  }

  @Override
  public int load32(long a) {
    return MemoryAccess.getIntAtOffset(segment, a);
  }

  @Override
  public long load64(long a) {
    return MemoryAccess.getLongAtOffset(segment, a);
  }

  @Override
  public void store8(long a, byte v) {
    MemoryAccess.setByteAtOffset(segment, a, v);
  }

  @Override
  public void store16(long a, short v) {
    MemoryAccess.setShortAtOffset(segment, a, v);
  }

  @Override
  public void store32(long a, int v) {
    MemoryAccess.setIntAtOffset(segment, a, v);
  }

  @Override
  public void store64(long a, long v) {
    MemoryAccess.setLongAtOffset(segment, a, v);
  }
}
