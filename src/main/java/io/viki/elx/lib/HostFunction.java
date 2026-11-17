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

import io.viki.elx.memory.HeapVisitor;

/**
 * Thread-confined host callback invoked by the VM's external-call bridge.
 * {@link Call} is borrowed only for the duration of {@link #invoke(Call)} and
 * must not be retained; implementations that capture ELX pointers should expose
 * them through {@link #trace(HeapVisitor)} so the VM can keep them alive.
 */
@FunctionalInterface
public interface HostFunction {
  /**
   * Executes the host operation for one borrowed argument/result window.
   *
   * @param args borrowed call context and result writer
   */
  void invoke(Call args);

  /**
   * Visits ELX pointers retained by this callback.
   *
   * @param visitor visitor receiving retained heap roots
   */
  default void trace(HeapVisitor visitor) {
  }
}
