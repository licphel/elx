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
import io.viki.elx.asm.Assembler;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.ElxList;
import io.viki.elx.value.ElxTable;
import io.viki.elx.vm.ScriptTrace;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;

/**
 * Standard ELX library definitions. The table is assembled once from the VM's
 * primitive instruction ABI and then frozen, so every compiler can share the
 * same numeric conversion functions, container helpers, control utilities, and error
 * handling functions without retaining parser state.
 */
public final class Builtins {
  private static final Library STANDARD = create();

  private Builtins() {
  }

  /**
   * Returns the immutable standard library shared by compiler instances.
   *
   * @return standard built-in definitions
   */
  public static Library standard() {
    return STANDARD;
  }

  /**
   * Resolves a numeric conversion name used in source type annotations.
   *
   * @param name source conversion name, such as {@code int} or {@code double}
   * @return target type, or {@code null} when the name is not a conversion
   */
  public static @Nullable Type conversion(String name) {
    for (Type type : Type.values()) {
      if (type.numeric() && type.displayName().equals(name)) {
        return type;
      }
    }
    return null;
  }

  private static Library create() {
    SimpleLibrary library = new SimpleLibrary();
    for (Type type : Type.values()) {
      if (type.numeric()) {
        Assembler code = new Assembler();
        code.immediate(Op.CX_CONVERT, 1, 0, type.ordinal()).r(Op.CX_RETURN, 1, 1, 0);
        library.function(type.displayName(), code.build(2, 1));
      }
    }
    library.function("len",
        new Assembler().r(Op.CX_LEN, 1, 0, 0).r(Op.CX_RETURN, 1, 1, 0).build(2, 1));
    library.function("getrawattr",
        new Assembler().immediate(Op.CX_GUARD, 0, 0, Type.TABLE.ordinal()).r(Op.CX_GET_TABLE, 2, 0, 1).r(Op.CX_RETURN, 2, 1, 0).build(3, 2));
    library.function("setrawattr",
        new Assembler().immediate(Op.CX_GUARD, 0, 0, Type.TABLE.ordinal()).r(Op.CX_SET_TABLE, 0, 1, 2).r(Op.CX_RETURN, 0, 1, 0).build(3, 3));
    library.function("getmetatable",
        new Assembler().r(Op.CX_GET_META, 1, 0, 0).r(Op.CX_RETURN, 1, 1, 0).build(2, 1));
    library.function("setmetatable",
        new Assembler().r(Op.CX_SET_META, 2, 0, 1).r(Op.CX_RETURN, 2, 1, 0).build(3, 2));
    library.function("referenceequal",
        new Assembler().r(Op.EQ64, 2, 0, 1).r(Op.CX_RETURN, 2, 1, 0).build(3, 2));
    library.function("require", args -> {
      args.arity(1);
      // require may execute a module and reenter host functions before returning.
      if (args.typeof(0) != Type.STRING) {
        throw new IllegalArgumentException("require expects a module name string");
      }
      args.vm().modules().require(args.bits(0), args.slot(0));
      args.returnArgument(0);
    });
    library.function("print",
        args -> args.vm().output().accept(args.join("\t") + '\n'));
    library.function("str", args -> {
      args.arity(1);
      args.rs(args.format(0));
    });
    library.function("assert", args -> {
      if (args.size() < 1 || args.size() > 2 || args.typeof(0) != Type.BOOL) {
        throw new IllegalArgumentException("assert expects a bool and an optional message");
      }
      if (!args.b(0)) {
        throw new AssertionError(args.size() == 2 ? args.format(1) : "assertion failed");
      }
      args.returnArgument(0);
    });
    library.function("throw", args -> {
      args.arity(1);
      throw new IllegalStateException(args.format(0));
    });
    library.function("pcall", args -> {
      args.arity(1);
      if (args.typeof(0) != Type.FUNCTION) {
        throw new IllegalArgumentException("pcall expects a function");
      }
      try {
        int returned = args.vm().execute(args.bits(0), 0, 0);
        args.rb(true);
        returnResults(args, returned, 1);
      } catch (RuntimeException failure) {
        long error = errorTable(args, failure);
        args.heap().retain(error);
        try {
        args.rb(false);
          args.returnValue(Type.TABLE, error);
        } finally {
          args.heap().release(error);
        }
      }
    });
    library.function("range", args -> {
      if (args.size() < 1 || args.size() > 3) {
        throw new IllegalArgumentException("range expects stop, start/stop, or start/stop/step");
      }
      long start = args.size() == 1 ? 0 : rangeArgument(args, 0);
      long stop = rangeArgument(args, args.size() == 1 ? 0 : 1);
      long step = args.size() == 3 ? rangeArgument(args, 2) : 1;
      if (step == 0) {
        throw new IllegalArgumentException("range step cannot be zero");
      }
      BigInteger first = BigInteger.valueOf(start), last = BigInteger.valueOf(stop);
      BigInteger stride = BigInteger.valueOf(step).abs();
      BigInteger distance = step > 0 ? last.subtract(first) : first.subtract(last);
      BigInteger count = distance.signum() <= 0 ? BigInteger.ZERO : distance.add(stride).subtract(BigInteger.ONE).divide(stride);
      if (count.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
        throw new IllegalArgumentException("range is too large: " + count);
      }
      long list = ElxList.create(args.heap());
      int length = count.intValueExact();
      long value = start;
      for (int i = 0; i < length; i++) {
        ElxList.append(args.heap(), list, Type.LONG, value);
        if (i + 1 < length) {
          value = Math.addExact(value, step);
        }
      }
      args.returnValue(Type.LIST, list);
    });
    Assembler typeCode = new Assembler();
    typeCode.r(Op.CX_TYPE, 1, 0, 0);
    for (Type type : Type.values()) {
      int next = typeCode.label();
      typeCode.constant(2, typeCode.constant(Type.UBYTE, Byte.toUnsignedInt(type.tag())));
      typeCode.rx(Op.ICMP, 3, 1, 2, Instr.control(Type.UBYTE, Instr.Relation.EQ)).branch(Op.CX_BR_FALSE, 3, next);
      typeCode.constant(4, typeCode.string(type.displayName())).r(Op.CX_RETURN, 4, 1, 0).bind(next);
    }
    typeCode.immediate(Op.TRAP, 0, 0, 1);
    library.function("type", typeCode.build(5, 1));
    return library.freeze();
  }

  private static long rangeArgument(Args args, int index) {
    if (args.typeof(index) == Type.ULONG && args.bits(index) < 0) {
      throw new IllegalArgumentException("range arguments must fit a signed long");
    }
    return args.i(index);
  }

  private static void returnResults(Args args, int count, int offset) {
    long results = args.vm().results();
    for (int i = 0; i < count && i + offset < 4; i++) {
      long at = Slot.address(results, i);
      args.returnValue(Slot.type(args.memory(), at), Slot.bits(args.memory(), at));
    }
  }

  private static long errorTable(Args args, RuntimeException failure) {
    String message = failure.getMessage();
    String cause = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    StringBuilder stacktrace = new StringBuilder();
    for (Throwable suppressed : failure.getSuppressed()) {
      if (suppressed instanceof ScriptTrace) {
        for (StackTraceElement frame : suppressed.getStackTrace()) {
          if (!stacktrace.isEmpty()) {
            stacktrace.append(System.lineSeparator());
          }
          stacktrace.append(frame);
        }
      }
    }
    long table = ElxTable.create(args.heap());
    long pair = args.heap().arena().allocate(2 * Slot.SIZE);
    try {
      Slot.write(args.memory(), pair, Type.STRING, args.heap().importString("cause"));
      Slot.write(args.memory(), pair + Slot.SIZE, Type.STRING, args.heap().importString(cause));
      ElxTable.set(args.heap(), table, pair, pair + Slot.SIZE);
      Slot.write(args.memory(), pair, Type.STRING, args.heap().importString("stacktrace"));
      Slot.write(args.memory(), pair + Slot.SIZE, Type.STRING, args.heap().importString(stacktrace.toString()));
      ElxTable.set(args.heap(), table, pair, pair + Slot.SIZE);
    } finally {
      args.heap().arena().free(pair);
    }
    return table;
  }
}
