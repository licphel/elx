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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Byte-array-backed {@link Memory} implementation. Primitive accesses use
 * native-order VarHandles and resize by replacing the backing array; addresses
 * are offsets within the array and are invalid after close.
 */
public final class JavaMemory extends AbstractMemory {
  private static final int DEFAULT_CAPACITY = 4096;
  private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());
  private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
  private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
  private byte[] data;

  /**
   * Creates memory with the default initial capacity.
   */
  public JavaMemory() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates memory with at least {@code capacity} bytes reserved.
   *
   * @param capacity initial byte capacity
   * @throws IllegalArgumentException if {@code capacity} is less than one
   */
  public JavaMemory(int capacity) {
    super(capacity);
    data = new byte[capacity];
  }

  @Override
  protected void resize(int size) {
    data = Arrays.copyOf(data, size);
  }

  @Override
  protected void dispose() {
    data = new byte[0];
  }

  @Override
  public void clear(long address, int bytes) {
    int at = (int) address;
    Arrays.fill(data, at, at + bytes, (byte) 0);
  }

  @Override
  public void copyBytes(long destination, long source, int bytes) {
    System.arraycopy(data, (int) source, data, (int) destination, bytes);
  }

  @Override
  public byte load8(long a) {
    return data[(int) a];
  }

  @Override
  public short load16(long a) {
    return (short) SHORT.get(data, (int) a);
  }

  @Override
  public int load32(long a) {
    return (int) INT.get(data, (int) a);
  }

  @Override
  public long load64(long a) {
    return (long) LONG.get(data, (int) a);
  }

  @Override
  public void store8(long a, byte v) {
    data[(int) a] = v;
  }

  @Override
  public void store16(long a, short v) {
    SHORT.set(data, (int) a, v);
  }

  @Override
  public void store32(long a, int v) {
    INT.set(data, (int) a, v);
  }

  @Override
  public void store64(long a, long v) {
    LONG.set(data, (int) a, v);
  }
}
