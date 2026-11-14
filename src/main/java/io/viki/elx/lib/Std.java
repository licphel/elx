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
import io.viki.elx.value.ElxTable;
import io.viki.elx.value.Pointers;
import io.viki.elx.vm.VM;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Standard host libraries linked once per VM. Modules expose explicit math,
 * string, I/O, system, GC, and utility operations; Java I/O and formatting stay
 * at this host boundary instead of entering the VM's primitive value model.
 */
public final class Std {
  private Std() {
  }

  /**
   * Creates one standard module library for a VM.
   *
   * @param name module name such as {@code math}, {@code io}, or {@code gc}
   * @param vm   VM that owns callbacks and heap operations
   * @return frozen module library, or {@code null} when the name is unknown
   */
  public static @Nullable Library create(String name, VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    switch (name) {
      case "math" -> {
        unary(library, "abs", Math::abs);
        unary(library, "sqrt", Math::sqrt);
        unary(library, "floor", Math::floor);
        unary(library, "ceil", Math::ceil);
        unary(library, "sin", Math::sin);
        unary(library, "cos", Math::cos);
        unary(library, "log", Math::log);
        unary(library, "exp", Math::exp);
        library.constant("pi", Type.DOUBLE, Double.doubleToRawLongBits(Math.PI));
        library.constant("inf", Type.DOUBLE, Double.doubleToRawLongBits(Double.POSITIVE_INFINITY));
        library.function("pow", a -> {
          a.arity(2);
          a.returnDouble(Math.pow(a.d(0), a.d(1)));
        });
        library.function("min", a -> extreme(a, true));
        library.function("max", a -> extreme(a, false));
      }
      case "str" -> {
        library.function("length", a -> {
          a.arity(1);
          String s = a.s(0);
          a.returnInt(s.codePointCount(0, s.length()));
        });
        library.function("bytelen", a -> {
          a.arity(1);
          a.s(0);
          a.returnInt(a.memory().load32(a.bits(0)));
        });
        library.function("upper", a -> {
          a.arity(1);
          a.returnString(a.s(0).toUpperCase(Locale.ROOT));
        });
        library.function("lower", a -> {
          a.arity(1);
          a.returnString(a.s(0).toLowerCase(Locale.ROOT));
        });
        library.function("sub", a -> {
          if (a.size() < 2 || a.size() > 3) {
            throw new IllegalArgumentException("sub expects text, start, optional end");
          }
          String s = a.s(0);
          int from = Math.toIntExact(a.i(1));
          int end = a.size() == 3 ? Math.toIntExact(a.i(2)) : s.codePointCount(0, s.length());
          a.returnString(s.substring(s.offsetByCodePoints(0, from), s.offsetByCodePoints(0, end)));
        });
        library.function("contains", a -> {
          a.arity(2);
          a.returnBoolean(a.s(0).contains(a.s(1)));
        });
      }
      case "io" -> {
        library.function("stdout", a -> vm.output().accept(a.join("")));
        library.function("print", a -> vm.output().accept(a.join("\t") + System.lineSeparator()));
        library.function("read_text", a -> {
          a.arity(1);
          try {
            a.returnString(Files.readString(Path.of(a.s(0))));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
        library.function("write_text", a -> {
          a.arity(2);
          try {
            Files.writeString(Path.of(a.s(0)), a.s(1));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
        library.function("exists", a -> {
          a.arity(1);
          a.returnBoolean(Files.exists(Path.of(a.s(0))));
        });
      }
      case "sys" -> {
        // The environment export is installed as a rooted value by Modules.
        library.function("unload", a -> {
          a.arity(1);
          vm.modules().unload(a.s(0));
        });
        library.function("remove", a -> {
          a.arity(2);
          a.returnBoolean(ElxTable.remove(a.memory(), a.t(0), a.slot(1)));
        });
        library.function("identity", a -> {
          a.arity(1);
          a.returnLong(a.bits(0));
        });
      }
      case "gc" -> {
        library.function("collect", a -> {
          a.arity(0);
          vm.collect(true);
        });
        library.function("pin", a -> {
          a.arity(1);
          Type type = a.typeof(0);
          if (type != Type.PTR) {
            throw new IllegalArgumentException("gc.pin expects a managed pointer handle");
          }
          long pointer = a.bits(0);
          if (!Pointers.canPin(vm.heap(), pointer)) {
            throw new IllegalArgumentException("gc.pin requires a live non-stack pointer handle");
          }
          vm.heap().retain(pointer);
          a.returnArgument(0);
        });
        library.function("unpin", a -> {
          a.arity(1);
          Type type = a.typeof(0);
          if (type != Type.PTR) {
            throw new IllegalArgumentException("gc.unpin expects a managed pointer handle");
          }
          long pointer = a.bits(0);
          vm.heap().release(pointer);
        });
      }
      case "util" -> {
        library.function("base64_encode", a -> {
          a.arity(1);
          a.returnString(Base64.getEncoder().encodeToString(a.s(0).getBytes(StandardCharsets.UTF_8)));
        });
        library.function("base64_decode", a -> {
          a.arity(1);
          a.returnString(new String(Base64.getDecoder().decode(a.s(0)), StandardCharsets.UTF_8));
        });
        library.function("time", a -> {
          a.arity(0);
          a.returnLong(System.currentTimeMillis());
        });
        library.function("date", a -> {
          if (a.size() > 1) {
            throw new IllegalArgumentException("date expects optional epoch milliseconds");
          }
          a.returnString(Instant.ofEpochMilli(a.size() == 0 ? System.currentTimeMillis() : a.i(0)).toString());
        });
      }
      default -> {
        return null;
      }
    }
    return library.freeze();
  }

  private static void unary(SimpleLibrary library, String name, DoubleUnaryOperator operation) {
    library.function(name, a -> {
      a.arity(1);
      a.returnDouble(operation.applyAsDouble(a.d(0)));
    });
  }

  private static void extreme(Args args, boolean minimum) {
    if (args.size() == 0) {
      throw new IllegalArgumentException("min/max expects numbers");
    }
    double result = args.d(0);
    for (int i = 1; i < args.size(); i++) {
      result = minimum ? Math.min(result, args.d(i)) : Math.max(result, args.d(i));
    }
    args.returnDouble(result);
  }
}
