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
import io.viki.elx.asm.Op;
import io.viki.elx.memory.HeapVisitor;
import io.viki.elx.memory.HostAccess;
import io.viki.elx.value.ElxList;
import io.viki.elx.vm.ScriptTrace;
import io.viki.elx.vm.Slots;
import org.jspecify.annotations.Nullable;

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
        library.function(type.displayName(), args -> {
          args.arity(1);
          Span result = args.scratch(1);
          args.operations().convert(type, args, 0, result, 0);
          args.rvalue(result, 0);
        });
      }
    }
    library.function("len", args -> {
      args.arity(1);
      args.ri(io.viki.elx.value.RawValues.length(Slots.memory(args), Slots.address(args, 0)));
    });
    library.function("getrawattr", args -> {
      args.arity(2);
      Span out = args.scratch(1);
      args.containers().tableGet(args, 0, args, 1, out, 0);
      args.rvalue(out, 0);
    });
    library.function("setrawattr", args -> {
      args.arity(3);
      args.containers().tableSet(args, 0, args, 1, args, 2);
      args.rarg(0);
    });
    library.function("getmetatable", new Assembler().r(Op.GETMETA, 1, 0, 0).r(Op.RETURN1, 1, 0, 0).build(2, 1));
    library.function("setmetatable", new Assembler().r(Op.SETMETA, 2, 0, 1).r(Op.RETURN1, 2, 0, 0).build(3, 2));
    library.function("referenceequal", args -> {
      args.arity(2);
      args.rb(args.type(0) == args.type(1) && args.bits(0) == args.bits(1));
    });
    library.function("require", args -> {
      args.arity(1);
      if (args.type(0) != Type.STRING) {
        throw new IllegalArgumentException("require expects a module name string");
      }
      Span out = args.scratch(1);
      Slots.vm(args).modules().require(args.bits(0), Slots.address(out, 0));
      args.rvalue(out, 0);
    });
    library.function("print", args -> {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < args.size(); i++) {
        if (i > 0) {
          text.append(", ");
        }
        text.append(args.operations().format(args, i));
      }
      Slots.vm(args).output().accept(text.append('\n').toString());
    });
    library.function("str", args -> {
      args.arity(1);
      args.rs(args.operations().format(args, 0));
    });
    library.function("assert", args -> {
      args.arity(1, 2);
      if (!args.b(0)) {
        throw new AssertionError(args.size() == 2 ? args.operations().format(args, 1) : "assertion failed");
      }
      args.rarg(0);
    });
    library.function("throw", args -> {
      args.arity(1);
      throw new RuntimeException(args.operations().format(args, 0));
    });
    library.function("pcall", args -> {
      args.arity(2);
      for (int i = 0; i < 2; i++) {
        Type type = args.type(i);
        boolean callable = type == Type.FUNCTION
            || type == Type.EXTERNAL && Slots.vm(args).external().exportJava(args.bits(i)) instanceof HostAccess
            || type == Type.TABLE && io.viki.elx.value.ElxTable.hook(
            Slots.memory(args), args.bits(i), io.viki.elx.value.Metamethod.CALL.ordinal()) != 0;
        if (!callable) {
          throw new IllegalArgumentException("pcall argument " + i + " must be callable");
        }
      }
      Span out = args.scratch(16), inputs = args.scratch(0);
      try {
        int count = args.operations().call(args, 0, inputs, out);
        args.rb(true);
        for (int i = 0; i < Math.min(count, 15); i++) {
          args.rvalue(out, i);
        }
      } catch (RuntimeException | AssertionError failure) {
        Span table = args.scratch(1), pair = args.scratch(2);
        args.containers().newTable(table, 0, 2);
        pair.setString(0, "cause");
        pair.setString(1, failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        args.containers().tableSet(table, 0, pair, 0, pair, 1);
        StringBuilder trace = new StringBuilder();
        for (Throwable suppressed : failure.getSuppressed()) {
          if (suppressed instanceof ScriptTrace) {
            for (StackTraceElement frame : suppressed.getStackTrace()) {
              if (!trace.isEmpty()) {
                trace.append(System.lineSeparator());
              }
              trace.append(frame);
            }
          }
        }
        pair.setString(0, "stacktrace");
        pair.setString(1, trace.toString());
        args.containers().tableSet(table, 0, pair, 0, pair, 1);
        Span handlerInputs = args.scratch(1);
        handlerInputs.copy(0, table, 0);
        int count = args.operations().call(args, 1, handlerInputs, out);
        args.rb(false);
        for (int i = 0; i < Math.min(count, 15); i++) {
          args.rvalue(out, i);
        }
      }
    });
    library.function("range", args -> {
      args.arity(1, 3);
      long start = args.size() == 1 ? 0 : rangeArgument(args, 0);
      long stop = rangeArgument(args, args.size() == 1 ? 0 : 1);
      long step = args.size() == 3 ? rangeArgument(args, 2) : 1;
      if (step == 0) {
        throw new IllegalArgumentException("range step cannot be zero");
      }
      long function = Slots.vm(args).function(new HostFunction() {
        private long current = start;
        private boolean exhausted;

        @Override
        public void invoke(Call call) {
          call.arity(0);
          if (exhausted || (step > 0 ? current >= stop : current <= stop)) {
            return;
          }
          call.rl(current);
          long next = current + step;
          exhausted = step > 0 ? next < current : next > current;
          current = next;
        }
      });
      args.r(Type.FUNCTION, function);
    });
    library.function("itr", args -> {
      args.arity(1);
      Type type = args.type(0);
      if (type != Type.LIST && type != Type.TABLE) {
        throw new IllegalArgumentException("itr expects a list or table");
      }
      long container = args.bits(0);
      var vm = Slots.vm(args);
      long function = vm.function(new HostFunction() {
        private final long version = type == Type.TABLE ? io.viki.elx.value.ElxTable.version(vm.memory(), container) : 0;
        private int index;
        private long cursor;
        private boolean exhausted;

        @Override
        public void invoke(Call call) {
          call.arity(0);
          if (exhausted) {
            return;
          }
          Span out = call.scratch(type == Type.TABLE ? 4 : 1);
          if (type == Type.LIST) {
            if (index == ElxList.size(vm.memory(), container)) {
              exhausted = true;
              return;
            }
            ElxList.get(vm.memory(), container, index++, Slots.address(out, 0));
            call.rvalue(out, 0);
          } else {
            if (version != io.viki.elx.value.ElxTable.version(vm.memory(), container)) {
              throw new java.util.ConcurrentModificationException("Table changed during iteration");
            }
            io.viki.elx.value.ElxTable.next(vm.memory(), Slots.address(out, 0), container, cursor);
            cursor = out.i(3);
            if (cursor == -1) {
              exhausted = true;
              return;
            }
            call.rvalue(out, 1);
            call.rvalue(out, 2);
          }
        }

        @Override
        public void trace(HeapVisitor visitor) {
          visitor.visit(type, container);
        }
      });
      args.r(Type.FUNCTION, function);
    });
    library.function("hash", args -> {
      args.arity(1);
      args.rl(args.operations().hash(args, 0));
    });
    library.function("type", args -> {
      args.arity(1);
      args.rs(args.type(0).displayName());
    });
    return library.freeze();
  }

  private static long rangeArgument(Span args, int index) {
    if (args.type(index) == Type.ULONG && args.bits(index) < 0) {
      throw new IllegalArgumentException("range arguments must fit a signed long");
    }
    return args.i(index);
  }
}
