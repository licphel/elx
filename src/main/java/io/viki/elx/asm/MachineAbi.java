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

package io.viki.elx.asm;

/**
 * Platform register names reserved for a future native/JIT backend.
 *
 * <p>These constants are not an ELX bytecode register file and are not used by
 * the interpreter. A JIT may map the explicit {@code ECALL} windows onto this
 * convention when emitting a host call.</p>
 */
public final class MachineAbi {
  /** Number of platform registers addressable by the machine ABI. */
  public static final int REGISTER_COUNT = 32;
  /** Stack-pointer register holding the current storage window. */
  public static final int SP = 2;
  /** Argument and result registers used by the execution-environment ABI. */
  public static final int A0 = 10, A1 = 11, A2 = 12, A3 = 13, A4 = 14, A5 = 15, A6 = 16, A7 = 17;

  private MachineAbi() {
  }

  /**
   * Validates a platform register index before emitting or executing an ABI instruction.
   *
   * @param index platform register index
   * @throws IllegalArgumentException if {@code index} is outside the ABI register file
   */
  public static void checkRegister(int index) {
    if (index < 0 || index >= REGISTER_COUNT) {
      throw new IllegalArgumentException("Platform register outside 0..31: " + index);
    }
  }
}
