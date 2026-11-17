package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.lib.Span;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.ContOp;
import io.viki.elx.value.ElxList;
import io.viki.elx.value.ElxTable;

import java.util.ConcurrentModificationException;

/** Thread-confined raw container implementation; no per-element wrappers. */
final class Containers implements ContOp {
  private final VM vm;
  private final Memory memory;

  Containers(VM vm) {
    this.vm = vm;
    this.memory = vm.memory();
  }

  private long container(Span span, int index, Type expected) {
    if (Slots.vm(span) != vm) {
      throw new IllegalArgumentException("Container belongs to another VM");
    }
    if (span.type(index) != expected) {
      throw new IllegalArgumentException("Expected " + expected + ", got " + span.type(index));
    }
    return span.bits(index);
  }

  private void same(Span span) {
    if (Slots.vm(span) != vm) {
      throw new IllegalArgumentException("Span belongs to another VM");
    }
  }

  @Override
  public void newList(Span out, int index, int capacity) {
    same(out);
    out.set(index, Type.LIST, vm.heap().allocate(ElxHeap.Layout.LIST, capacity));
  }

  @Override
  public void newTable(Span out, int index, int capacity) {
    same(out);
    out.set(index, Type.TABLE, vm.heap().allocate(ElxHeap.Layout.TABLE, capacity));
  }

  @Override
  public int listSize(Span values, int index) {
    return ElxList.size(memory, container(values, index, Type.LIST));
  }

  @Override
  public void listGet(Span values, int index, int element, Span out, int at) {
    same(out);
    ElxList.get(memory, container(values, index, Type.LIST), element, Slots.address(out, at));
  }

  @Override
  public void listSet(Span values, int index, int element, Span source, int at) {
    same(source);
    ElxList.set(vm.heap(), container(values, index, Type.LIST), element, Slots.address(source, at));
  }

  @Override
  public void listAppend(Span values, int index, Span source, int at) {
    same(source);
    ElxList.append(vm.heap(), container(values, index, Type.LIST), Slots.address(source, at));
  }

  @Override
  public void listRemove(Span values, int index, int element) {
    try (Slots.Scope out = vm.borrowSlots(1)) {
      ElxList.removeAt(vm.heap(), container(values, index, Type.LIST), element, Slots.address(out, 0));
    }
  }

  @Override
  public int tableSize(Span values, int index) {
    return ElxTable.size(memory, container(values, index, Type.TABLE));
  }

  @Override
  public boolean tableGet(Span values, int index, Span keys, int key, Span out, int at) {
    same(keys);
    same(out);
    long table = container(values, index, Type.TABLE);
    long value = ElxTable.own(memory, table, Slots.address(keys, key));
    if (value == 0) {
      out.setVoid(at);
    } else {
      Slot.copy(memory, Slots.address(out, at), value, 1);
    }
    return value != 0;
  }

  @Override
  public void tableSet(Span values, int index, Span keys, int key, Span source, int at) {
    same(keys);
    same(source);
    ElxTable.set(vm.heap(), container(values, index, Type.TABLE), Slots.address(keys, key), Slots.address(source, at));
  }

  @Override
  public boolean tableRemove(Span values, int index, Span keys, int key) {
    same(keys);
    return ElxTable.remove(memory, container(values, index, Type.TABLE), Slots.address(keys, key));
  }

  @Override
  public void tableClear(Span values, int index) {
    long table = container(values, index, Type.TABLE);
    try (Slots.Scope key = vm.borrowSlots(1)) {
      while (ElxTable.size(memory, table) > 0) {
        long data = ElxTable.data(memory, table);
        for (int i = 0, n = ElxTable.capacity(memory, table); i < n; i++) {
          long at = Slot.address(data, i * 2);
          if (Slot.type(memory, at) != Type.INVALID) {
            Slot.copy(memory, Slots.address(key, 0), at, 1);
            ElxTable.remove(memory, table, Slots.address(key, 0));
            break;
          }
        }
      }
    }
  }

  @Override
  public TableCursor tableCursor(Span values, int index) {
    long table = container(values, index, Type.TABLE);
    return new Cursor(table);
  }

  private final class Cursor implements TableCursor {
    private final long table, version;
    private int next, current = -1;
    private boolean closed;

    Cursor(long table) {
      this.table = table;
      this.version = ElxTable.version(memory, table);
      vm.heap().retain(table);
    }

    private void check() {
      if (closed) {
        throw new IllegalStateException("Table cursor is closed");
      }
      if (version != ElxTable.version(memory, table)) {
        throw new ConcurrentModificationException("Table changed during iteration");
      }
    }

    private long at() {
      check();
      if (current < 0) {
        throw new IllegalStateException("Cursor is not on an entry");
      }
      return Slot.address(ElxTable.data(memory, table), current * 2);
    }

    @Override
    public boolean next() {
      check();
      int capacity = ElxTable.capacity(memory, table);
      while (next < capacity) {
        int candidate = next++;
        if (Slot.type(memory, Slot.address(ElxTable.data(memory, table), candidate * 2)) != Type.INVALID) {
          current = candidate;
          return true;
        }
      }
      current = -1;
      return false;
    }

    @Override
    public void key(Span out, int index) {
      same(out);
      Slot.copy(memory, Slots.address(out, index), at(), 1);
    }

    @Override
    public void value(Span out, int index) {
      same(out);
      Slot.copy(memory, Slots.address(out, index), at() + Slot.SIZE, 1);
    }

    @Override
    public void setValue(Span source, int index) {
      same(source);
      ElxTable.set(vm.heap(), table, at(), Slots.address(source, index));
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        vm.heap().release(table);
      }
    }
  }
}
