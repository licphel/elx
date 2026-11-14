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

package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.Instr;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.lib.Library;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.ElxList;
import io.viki.elx.value.ElxTable;
import io.viki.elx.value.Pointers;
import io.viki.elx.value.ValueOperations;

/**
 * O0 slot-native runtime ABI. Symbols are statically linked, so runtime calls
 * operate through compact slot windows and never perform name lookups while
 * executing. The definitions are shared by every linker.
 */
public final class RuntimeLibrary {
  private static final Library DEFINITIONS = create();

  private RuntimeLibrary() {
  }

  /**
   * Returns immutable runtime ABI definitions used by O0 lowering.
   *
   * <p>Entries are linked as host callbacks and receive borrowed slot windows;
   * callers must not mutate or retain the returned library.</p>
   *
   * @return shared frozen runtime library
   */
  public static Library definitions() {
    return DEFINITIONS;
  }

  private static Library create() {
    SimpleLibrary library = new SimpleLibrary();
    library.function("call", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      int capacity = Math.toIntExact(Slot.bits(m, a + 3L * Slot.SIZE));
      vm.runtimeCall(r, a, Slot.bits(m, a + Slot.SIZE), Math.toIntExact(Slot.bits(m, a + 2L * Slot.SIZE)), capacity);
      args.returnCount(capacity);
    });
    library.function("return", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeReturn(Slot.bits(m, a), Math.toIntExact(Slot.bits(m, a + Slot.SIZE)));
      args.returnCount(0);
    });
    library.function("pack", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Type type = Type.fromTag((byte) Slot.bits(m, a));
      long bits = Slot.bits(m, a + Slot.SIZE);
      Slot.write(m, r, type, bits);
      args.returnCount(1);
    });
    library.function("guard", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Type expected = Type.fromTag((byte) Slot.bits(m, a + Slot.SIZE));
      if (Slot.type(m, a) != expected) {
        throw new IllegalArgumentException("Expected " + expected + ", got " + Slot.type(m, a));
      }
      args.returnCount(0);
    });
    library.function("convert", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeConvert(r, a, (int) Slot.bits(m, a + Slot.SIZE));
      args.returnCount(1);
    });
    library.function("constant.address", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.PTR, vm.constantPointer((int) Slot.bits(m, a)));
      args.returnCount(1);
    });
    library.function("local.pointer", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.PTR, vm.localPointer((int) Slot.bits(m, a)));
      args.returnCount(1);
    });
    library.function("capture.pointer", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.PTR, vm.capturePointer((int) Slot.bits(m, a)));
      args.returnCount(1);
    });
    library.function("to.string", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.STRING, vm.heap().importString(vm.format(a)));
      args.returnCount(1);
    });
    library.function("bool.check", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      if (Slot.type(m, a) != Type.BOOL) {
        throw new IllegalArgumentException("Branch requires BOOL, got " + Slot.type(m, a));
      }
      Slot.copy(m, r, a, 1);
      args.returnCount(1);
    });
    library.function("bool.not", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      if (Slot.type(m, a) != Type.BOOL) {
        throw new IllegalArgumentException("Branch requires BOOL, got " + Slot.type(m, a));
      }
      Slot.write(m, r, Type.BOOL, Slot.bits(m, a) == 0 ? 1 : 0);
      args.returnCount(1);
    });
    library.function("allocate", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeAllocate(r, a, a + Slot.SIZE);
      args.returnCount(1);
    });
    library.function("poll", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimePoll();
      args.returnCount(0);
    });
    library.function("host.call", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      int count = Math.toIntExact(Slot.bits(m, a + 3L * Slot.SIZE)), capacity = Math.toIntExact(Slot.bits(m, a + 4L * Slot.SIZE));
      vm.runtimeHostCall(r, a, a + Slot.SIZE, Slot.bits(m, a + 2L * Slot.SIZE), Instr.callWindow(count, capacity));
      args.returnCount(capacity);
    });
    library.function("host.get", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.external().nget(Slot.bits(m, a), Slot.bits(m, a + Slot.SIZE), r);
      args.returnCount(1);
    });
    library.function("host.set", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.external().nset(Slot.bits(m, a), Slot.bits(m, a + Slot.SIZE), a + 2L * Slot.SIZE);
      args.returnCount(0);
    });
    library.function("host.address", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.PTR, vm.external().naddress(Slot.bits(m, a), Slot.bits(m, a + Slot.SIZE)));
      args.returnCount(1);
    });
    library.function("host.retain", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.external().retain(Slot.bits(m, a));
      args.returnCount(0);
    });
    library.function("host.release", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.external().release(Slot.bits(m, a));
      args.returnCount(0);
    });
    library.function("operator", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeOperator((int) Slot.bits(m, a + 2L * Slot.SIZE), r, a, a + Slot.SIZE);
      args.returnCount(1);
    });
    library.function("env", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.TABLE, vm.globals());
      args.returnCount(1);
    });
    library.function("length", args -> {
      args.returnInt(ValueOperations.length(args.memory(), args.slot(0)));
    });
    library.function("index.get", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeReadIndex(r, a, a + Slot.SIZE);
      args.returnCount(1);
    });
    library.function("index.set", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeWriteIndex(a, a + Slot.SIZE, a + 2L * Slot.SIZE);
      args.returnCount(0);
    });
    library.function("index.pointer", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.PTR, Pointers.indexed(vm.heap(), a, a + Slot.SIZE));
      args.returnCount(1);
    });
    library.function("global.pointer", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.PTR, Pointers.global(vm.heap(), vm.globals(), a));
      args.returnCount(1);
    });
    library.function("pointer.write", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.pointerWrite(a, a + Slot.SIZE);
      args.returnCount(0);
    });
    library.function("table.get", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      ElxTable.get(vm.memory(), Slot.bits(m, a), a + Slot.SIZE, r);
      args.returnCount(1);
    });
    library.function("table.set", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      ElxTable.set(vm.heap(), Slot.bits(m, a), a + Slot.SIZE, a + 2L * Slot.SIZE);
      args.returnCount(0);
    });
    library.function("list.append", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      ElxList.append(vm.heap(), Slot.bits(m, a), a + Slot.SIZE);
      args.returnCount(0);
    });
    library.function("hash", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Slot.write(m, r, Type.LONG, ValueOperations.hash(vm.memory(), a));
      args.returnCount(1);
    });
    library.function("memory.copy", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      m.copyBytes(Slot.bits(m, a), Slot.bits(m, a + Slot.SIZE), Math.toIntExact(Slot.bits(m, a + 2L * Slot.SIZE)));
      args.returnCount(0);
    });
    library.function("deref", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      int count = Math.toIntExact(Slot.bits(m, a + Slot.SIZE)), available = Pointers.dereferenceCount(vm.memory(), a);
      if (count > available) {
        throw new IllegalArgumentException("Unpack needs " + count + " values, sequence has " + available);
      }
      Slot.write(m, r, Type.LONG, vm.pointerData(a));
      args.returnCount(1);
    });
    library.function("unpack", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      boolean list = Slot.bits(m, a + Slot.SIZE) != 0;
      int count = list ? Pointers.argumentCount(vm.memory(), a) : Pointers.dereferenceCount(vm.memory(), a);
      long data = list ? ElxList.data(vm.memory(), Slot.bits(m, a)) : vm.pointerData(a);
      Slot.write(m, r, Type.LONG, data);
      Slot.write(m, r + Slot.SIZE, Type.INT, count);
      args.returnCount(2);
    });
    library.function("append.unpack", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      long list = Slot.bits(m, a), source = a + Slot.SIZE;
      long data = Slot.type(m, source) == Type.LIST ? ElxList.data(m, Slot.bits(m, source)) : vm.pointerData(source);
      int count = Pointers.dereferenceCount(vm.memory(), source);
      for (int i = 0; i < count; i++) {
        ElxList.append(vm.heap(), list, Slot.address(data, i));
      }
      args.returnCount(0);
    });
    library.function("closure", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeClosure(r, a, Slot.bits(m, a + Slot.SIZE), Math.toIntExact(Slot.bits(m, a + 2L * Slot.SIZE)));
      args.returnCount(1);
    });
    library.function("call.named", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeNamed(r, a, Slot.bits(m, a + Slot.SIZE), (int) Slot.bits(m, a + 2L * Slot.SIZE), Slot.bits(m, a + 3L * Slot.SIZE));
      args.returnCount(4);
    });
    library.function("meta.set", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      Type kind = Slot.type(m, a + Slot.SIZE);
      if (Slot.type(m, a) != Type.TABLE || kind != Type.TABLE && kind != Type.NULL) {
        throw new IllegalArgumentException("setmetatable requires table and table/null");
      }
      long table = Slot.bits(m, a);
      ElxTable.setMetatable(vm.heap(), table, kind == Type.NULL ? 0 : Slot.bits(m, a + Slot.SIZE));
      Slot.write(m, r, Type.TABLE, table);
      args.returnCount(1);
    });
    library.function("meta.get", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      if (Slot.type(m, a) != Type.TABLE) {
        throw new IllegalArgumentException("getmetatable requires table");
      }
      long meta = ElxTable.metatable(vm.memory(), Slot.bits(m, a));
      Slot.write(m, r, meta == 0 ? Type.NULL : Type.TABLE, meta);
      args.returnCount(1);
    });
    library.function("next", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      vm.runtimeNext(r, a, a + Slot.SIZE);
      args.returnCount(4);
    });
    library.function("table.next", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      ElxTable.next(vm.memory(), r, Slot.bits(m, a), Slot.bits(m, a + Slot.SIZE));
      args.returnCount(4);
    });
    library.function("list.next", args -> {
      Memory m = args.memory();
      long a = args.inputAddress(), r = args.outputAddress();
      VM vm = args.vm();
      ElxList.next(vm.memory(), r, Slot.bits(m, a), Slot.bits(m, a + Slot.SIZE));
      args.returnCount(3);
    });
    return library.freeze();
  }
}
