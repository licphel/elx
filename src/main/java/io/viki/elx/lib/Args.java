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
import io.viki.elx.memory.*;
import io.viki.elx.memory.*;
import io.viki.elx.vm.VM;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Borrowed argument/result window, confined to its VM thread. Do not retain it.
 * Reentrant calls borrow separate windows; windows are reused after return.
 * Container getters return ELX pointers, not Java collections. Inputs remain readable
 * until overwritten by a return; consume inputs before writing aliased outputs.
 */
public final class Args {
  private final VM vm;
  private long inputs, outputs;
  private int count, capacity, returned;
  private @Nullable String memberName;

  private Args(VM vm) {
    this.vm = vm;
  }

  /**
   * Creates a borrowed argument/result view for an external host-member callback.
   * The returned view is valid only until that callback returns.
   *
   * @param vm VM that owns the borrowed windows
   * @param inputs input-window base address
   * @param count number of input slots
   * @param outputs output-window base address
   * @param capacity number of output slots available
   * @return borrowed callback view
   */
  public static Args host(VM vm, long inputs, int count, long outputs, int capacity) {
    return host(vm, null, inputs, count, outputs, capacity);
  }

  /**
   * Creates a borrowed argument/result view for an external host-member callback.
   * The returned view is valid only until that callback returns.
   *
   * @param vm VM that owns the borrowed windows
   * @param memberName the metamethod member name
   * @param inputs input-window base address
   * @param count number of input slots
   * @param outputs output-window base address
   * @param capacity number of output slots available
   * @return borrowed callback view
   */
  public static Args host(VM vm, @Nullable String memberName, long inputs, int count,
                          long outputs, int capacity) {
    Args args = new Args(vm);
    args.memberName = memberName;
    args.inputs = inputs;
    args.count = count;
    args.outputs = outputs;
    args.capacity = capacity;
    return args;
  }

  /**
   * Binds one host definition to a VM-owned reusable argument window.
   *
   * @param vm       VM that owns the argument slots and heaps
   * @param function host callback invoked for each call
   * @return linker callback suitable for an external-heap entry
   */
  public static ExtHeap.BoundCall bind(VM vm, HostFunction function) {
    return new ExtHeap.BoundCall() {
      private final ArrayList<Args> windows = new ArrayList<>();
      private int depth;

      @Override
      public int invoke(Memory memory, long inputs, int count, long outputs, int capacity) {
        if (depth == windows.size()) {
          windows.add(new Args(vm));
        }
        Args args = windows.get(depth++);
        args.inputs = inputs;
        args.outputs = outputs;
        args.count = count;
        args.capacity = capacity;
        args.returned = 0;
        try {
          function.invoke(args);
          return Math.min(args.returned, capacity);
        } finally {
          args.inputs = args.outputs = 0;
          args.count = args.capacity = args.returned = 0;
          args.memberName = null;
          depth--;
        }
      }

      @Override
      public void trace(HeapVisitor visitor) {
        function.trace(visitor);
      }
    };
  }

  /** @return the VM that owns this borrowed call window */
  public VM vm() {
    return vm;
  }

  /** @return the member name for a host metamethod callback */
  public String name() {
    String name = memberName;
    if (name == null) {
      throw new IllegalStateException("This Args view has no host member name");
    }
    return name;
  }

  /** @return VM memory containing argument and result slots */
  public Memory memory() {
    return vm.memory();
  }

  /** @return VM-managed ELX heap for container and string operations */
  public ElxHeap heap() {
    return vm.heap();
  }

  /** @return number of input arguments in this call */
  public int size() {
    return count;
  }

  /**
   * Returns the address of an input slot.
   *
   * @param index zero-based argument index
   * @return slot address in {@link #memory()}
   * @throws IndexOutOfBoundsException if {@code index} is not an argument
   */
  public long slot(int index) {
    return Slot.address(inputs, Objects.checkIndex(index, count));
  }

  /** @return base address of borrowed input slots for VM-owned native helpers */
  public long inputAddress() {
    return inputs;
  }

  /** @return base address of borrowed output slots for VM-owned native helpers */
  public long outputAddress() {
    return outputs;
  }

  /** @return number of result slots requested by the caller */
  public int resultCapacity() {
    return capacity;
  }

  /**
   * Reports output slots already written directly through {@link #outputAddress()}.
   *
   * @param count number of output slots written
   */
  public void returnCount(int count) {
    returned = count;
  }

  /**
   * Returns the number of values appended to this callback's result window.
   *
   * @return number of returned values
   */
  public int returnedCount() {
    return returned;
  }

  /**
   * Returns the runtime type tag of an input argument.
   *
   * @param index zero-based argument index
   * @return argument type
   */
  public Type typeof(int index) {
    return Slot.type(memory(), slot(index));
  }

  /**
   * Returns the raw payload of an input argument.
   *
   * @param index zero-based argument index
   * @return raw argument bits
   */
  public long bits(int index) {
    return Slot.bits(memory(), slot(index));
  }

  private long require(int index, Type expected) {
    long address = slot(index);
    Type actual = Slot.type(memory(), address);
    if (actual != expected) {
      throw new IllegalArgumentException("Argument " + index + ": expected " + expected + ", got " + actual);
    }
    return Slot.bits(memory(), address);
  }

  /**
   * Reads an integer argument and normalizes it to signed/unsigned width.
   *
   * @param index zero-based argument index
   * @return normalized integer value
   */
  public long i(int index) {
    Type type = typeof(index);
    if (!type.integer()) {
      throw new IllegalArgumentException("Argument " + index + ": expected integer, got " + type);
    }
    return Type.normalize(bits(index), type);
  }

  /**
   * Reads an integer or floating-point argument as a Java {@code double}.
   *
   * @param index zero-based argument index
   * @return numeric value
   */
  public double d(int index) {
    return Type.asDouble(bits(index), typeof(index));
  }

  /**
   * Reads a boolean argument.
   *
   * @param index zero-based argument index
   * @return boolean value
   */
  public boolean b(int index) {
    return require(index, Type.BOOL) != 0;
  }

  /**
   * Exports a string argument from the ELX heap.
   *
   * @param index zero-based argument index
   * @return Java string value
   */
  public String s(int index) {
    return heap().exportString(require(index, Type.STRING));
  }

  /**
   * Exports an external value through the VM's Java bridge.
   *
   * @param index zero-based argument index
   * @return Java value, possibly {@code null}
   */
  public @Nullable Object o(int index) {
    return vm.external().exportValue(slot(index));
  }

  /**
   * Returns the ELX table pointer at an argument index.
   *
   * @param index zero-based argument index
   * @return table pointer
   */
  public long t(int index) {
    return require(index, Type.TABLE);
  }

  /**
   * Returns the ELX list pointer at an argument index.
   *
   * @param index zero-based argument index
   * @return list pointer
   */
  public long l(int index) {
    return require(index, Type.LIST);
  }

  /**
   * Returns the native pointer value at an argument index.
   *
   * @param index zero-based argument index
   * @return pointer value
   */
  public long pointer(int index) {
    return require(index, Type.PTR);
  }

  /**
   * Requires exactly {@code expected} input arguments.
   *
   * @param expected required argument count
   * @throws IllegalArgumentException if the call has another arity
   */
  public void arity(int expected) {
    if (count != expected) {
      throw new IllegalArgumentException("Expected " + expected + " arguments, got " + count);
    }
  }

  /**
   * Appends a result. Unrequested values are discarded, but at most four may be
   * produced.
   *
   * @param type result runtime type
   * @param bits raw result payload
   */
  public void returnValue(Type type, long bits) {
    if (returned == 4) {
      throw new IllegalStateException("A host function may return at most four values");
    }
    if (returned < capacity) {
      Slot.write(memory(), Slot.address(outputs, returned), type, bits);
    }
    returned++;
  }

  /**
   * Appends an ELX null result.
   */
  public void rn() {
    returnValue(Type.NULL, 0);
  }

  /**
   * Appends an 8-bit integer result.
   *
   * @param value the returned value
   */
  public void rbyte(byte value) {
    returnValue(Type.BYTE, value);
  }

  /**
   * Appends a 16-bit integer result.
   *
   * @param value the returned value
   */
  public void rshort(short value) {
    returnValue(Type.SHORT, value);
  }

  /**
   * Appends a 32-bit integer result.
   *
   * @param value the returned value
   */
  public void ri(int value) {
    returnValue(Type.INT, value);
  }

  /**
   * Appends a 64-bit integer result.
   *
   * @param value the returned value
   */
  public void rl(long value) {
    returnValue(Type.LONG, value);
  }

  /**
   * Appends a single-precision result.
   *
   * @param value the returned value
   */
  public void rf(float value) {
    returnValue(Type.FLOAT, Float.floatToRawIntBits(value));
  }

  /**
   * Appends a double-precision result.
   *
   * @param value the returned value
   */
  public void rd(double value) {
    returnValue(Type.DOUBLE, Double.doubleToRawLongBits(value));
  }

  /**
   * Appends a boolean result.
   *
   * @param value the returned value
   */
  public void rb(boolean value) {
    returnValue(Type.BOOL, value ? 1 : 0);
  }

  /**
   * Appends a Java string as an ELX string result.
   *
   * @param value the returned value
   */
  public void rs(String value) {
    returnValue(Type.STRING, heap().importString(value));
  }

  /**
   * Appends a Java value as an external or scalar result.
   *
   * @param value the returned value
   */
  public void ro(@Nullable Object value) {
    if (returned == 4) {
      throw new IllegalStateException("A host function may return at most four values");
    }
    if (returned < capacity) {
      vm.external().importJava(Slot.address(outputs, returned), value);
    }
    returned++;
  }

  /** @param index input index to copy */
  public void returnArgument(int index) {
    long address = slot(index);
    returnValue(Slot.type(memory(), address), Slot.bits(memory(), address));
  }

  /**
   * Formats one argument using the VM's ELX display rules.
   *
   * @param index zero-based argument index
   * @return formatted text
   */
  public String format(int index) {
    return vm.format(slot(index));
  }

  /**
   * Formats all arguments and joins them in call order.
   *
   * @param separator text inserted between formatted arguments
   * @return joined argument text
   */
  public String join(String separator) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        text.append(separator);
      }
      text.append(format(i));
    }
    return text.toString();
  }
}
