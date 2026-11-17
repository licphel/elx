package io.viki.elx.lib.std;

import io.viki.elx.Type;
import io.viki.elx.lib.Call;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.lib.Span;
import io.viki.elx.value.ContOp;
import io.viki.elx.vm.VM;

/**
 * Primitive table operations used by the script-level {@code table} module.
 * Raw access deliberately bypasses table metamethods, matching direct storage
 * operations. Cursor keys are copied into rooted spans and cannot overwrite
 * the hash key held by the table. Callbacks are stateless and thread-confined.
 */
public final class NTableStd {
  private NTableStd() {
  }

  /**
   * Builds the native symbols for the table module. Public helpers are assembled
   * by {@code /std/table.elx} from these primitives.
   *
   * @param vm VM that will invoke the callbacks
   * @return frozen native library
   */
  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.function("create", NTableStd::create);
    library.function("get", NTableStd::get);
    library.function("set", NTableStd::set);
    library.function("remove", NTableStd::remove);
    library.function("has", NTableStd::has);
    library.function("keys", (Call args) -> collect(args, false));
    library.function("values", (Call args) -> collect(args, true));
    library.function("entries", NTableStd::entries);
    library.function("merge", NTableStd::merge);
    library.function("mergeInto", NTableStd::mergeInto);
    library.function("clear", NTableStd::clear);
    library.function("clone", NTableStd::cloneTable);
    library.function("invert", NTableStd::invert);
    library.function("size", NTableStd::size);
    library.function("isEmpty", NTableStd::isEmpty);
    return library.freeze();
  }

  private static void create(Call call) {
    call.arity(0);
    Span result = call.scratch(1);
    call.containers().newTable(result, 0, 0);
    call.rvalue(result, 0);
  }

  private static void get(Call call) {
    call.arity(2, 3);
    requireTable(call, 0);
    Span result = call.scratch(1);
    if (call.containers().tableGet(call, 0, call, 1, result, 0)) {
      call.rvalue(result, 0);
    } else if (call.size() == 3) {
      call.rvalue(call, 2);
    } else {
      call.rv();
    }
  }

  private static void set(Call call) {
    call.arity(3);
    requireTable(call, 0);
    call.containers().tableSet(call, 0, call, 1, call, 2);
  }

  private static void remove(Call call) {
    call.arity(2);
    requireTable(call, 0);
    call.rb(call.containers().tableRemove(call, 0, call, 1));
  }

  private static void has(Call call) {
    call.arity(2);
    requireTable(call, 0);
    Span ignored = call.scratch(1);
    call.rb(call.containers().tableGet(call, 0, call, 1, ignored, 0));
  }

  private static void collect(Call call, boolean values) {
    call.arity(1);
    requireTable(call, 0);
    ContOp containers = call.containers();
    Span result = call.scratch(1), item = call.scratch(1);
    containers.newList(result, 0, containers.tableSize(call, 0));
    try (ContOp.TableCursor cursor = containers.tableCursor(call, 0)) {
      while (cursor.next()) {
        if (values) {
          cursor.value(item, 0);
        } else {
          cursor.key(item, 0);
        }
        containers.listAppend(result, 0, item, 0);
      }
    }
    call.rvalue(result, 0);
  }

  private static void entries(Call call) {
    call.arity(1);
    requireTable(call, 0);
    ContOp containers = call.containers();
    Span work = call.scratch(5);
    containers.newList(work, 0, containers.tableSize(call, 0));
    String[] names = {"key", "value"};
    try (ContOp.TableCursor cursor = containers.tableCursor(call, 0)) {
      while (cursor.next()) {
        cursor.key(work, 2);
        cursor.value(work, 3);
        containers.newTable(work, 1, 2);
        for (int i = 0; i < 2; i++) {
          work.setString(4, names[i]);
          containers.tableSet(work, 1, work, 4, work, 2 + i);
        }
        containers.listAppend(work, 0, work, 1);
      }
    }
    call.rvalue(work, 0);
  }

  private static void merge(Call call) {
    call.arity(2);
    requireTable(call, 0);
    requireTable(call, 1);
    ContOp containers = call.containers();
    Span result = call.scratch(1), pair = call.scratch(2);
    containers.newTable(result, 0, containers.tableSize(call, 0) + containers.tableSize(call, 1));
    copyEntries(call, 0, result, 0, pair);
    copyEntries(call, 1, result, 0, pair);
    call.rvalue(result, 0);
  }

  private static void mergeInto(Call call) {
    call.arity(2);
    requireTable(call, 0);
    requireTable(call, 1);
    if (call.bits(0) == call.bits(1)) {
      return;
    }
    copyEntries(call, 1, call, 0, call.scratch(2));
  }

  private static void clear(Call call) {
    call.arity(1);
    requireTable(call, 0);
    call.containers().tableClear(call, 0);
  }

  private static void cloneTable(Call call) {
    call.arity(1);
    requireTable(call, 0);
    Span result = call.scratch(1);
    call.containers().newTable(result, 0, call.containers().tableSize(call, 0));
    copyEntries(call, 0, result, 0, call.scratch(2));
    call.rvalue(result, 0);
  }

  private static void invert(Call call) {
    call.arity(1);
    requireTable(call, 0);
    ContOp containers = call.containers();
    Span result = call.scratch(1), pair = call.scratch(2);
    containers.newTable(result, 0, containers.tableSize(call, 0));
    try (ContOp.TableCursor cursor = containers.tableCursor(call, 0)) {
      while (cursor.next()) {
        cursor.key(pair, 0);
        cursor.value(pair, 1);
        containers.tableSet(result, 0, pair, 1, pair, 0);
      }
    }
    call.rvalue(result, 0);
  }

  private static void size(Call call) {
    call.arity(1);
    requireTable(call, 0);
    call.ri(call.containers().tableSize(call, 0));
  }

  private static void isEmpty(Call call) {
    call.arity(1);
    requireTable(call, 0);
    call.rb(call.containers().tableSize(call, 0) == 0);
  }

  private static void copyEntries(Span values, int sourceIndex, Span target, int targetIndex, Span pair) {
    ContOp containers = values.containers();
    try (ContOp.TableCursor cursor = containers.tableCursor(values, sourceIndex)) {
      while (cursor.next()) {
        cursor.key(pair, 0);
        cursor.value(pair, 1);
        containers.tableSet(target, targetIndex, pair, 0, pair, 1);
      }
    }
  }

  private static void requireTable(Span values, int index) {
    if (values.type(index) != Type.TABLE) {
      throw new IllegalArgumentException("Argument " + index + " must be TABLE, got " + values.type(index));
    }
  }
}
