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
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;

import java.nio.charset.StandardCharsets;

/**
 * Explicit formatting boundary for host output and conversions. Script values
 * remain in VM slots; this class reads those slots and creates Java text only at
 * the boundary where a host consumer requests it.
 */
public final class ValueText {
  private ValueText() {
  }

  /**
   * Formats the value stored in one slot using ELX display rules.
   *
   * @param memory memory containing the slot and referenced heap data
   * @param at     slot address
   * @return formatted value text
   */
  public static String string(Memory memory, long at) {
    Type type = Slot.type(memory, at);
    long bits = Slot.bits(memory, at);
    if (type == Type.STRING) {
      int length = memory.load32(bits);
      byte[] data = new byte[length];
      for (int i = 0; i < length; i++) {
        data[i] = memory.load8(bits + Integer.BYTES + i);
      }
      return new String(data, StandardCharsets.UTF_8);
    }
    if (type == Type.VOID) {
      return "void";
    }
    if (type == Type.NULL) {
      return "null";
    }
    if (type == Type.BOOL) {
      return bits == 0 ? "false" : "true";
    }
    if (type.integer()) {
      return type == Type.ULONG ? Long.toUnsignedString(bits) : Long.toString(bits);
    }
    if (type == Type.FLOAT) {
      return Float.toString(Slot.getFloat(memory, at));
    }
    if (type == Type.DOUBLE) {
      return Double.toString(Slot.getDouble(memory, at));
    }
    return "<" + type.displayName() + "@" + bits + ">";
  }

  /**
   * Formats a contiguous slot range and joins the results.
   *
   * @param memory    memory containing the slots
   * @param args      base address of the slot range
   * @param count     number of slots to format
   * @param separator text inserted between values
   * @return joined formatted text
   */
  public static String join(Memory memory, long args, int count, String separator) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        text.append(separator);
      }
      text.append(string(memory, Slot.address(args, i)));
    }
    return text.toString();
  }
}
