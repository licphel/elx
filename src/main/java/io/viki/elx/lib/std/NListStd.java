package io.viki.elx.lib.std;

import io.viki.elx.Type;
import io.viki.elx.asm.Instr;
import io.viki.elx.lib.Call;
import io.viki.elx.lib.Library;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.lib.Span;
import io.viki.elx.value.ContOp;
import io.viki.elx.value.ValueOperations;
import io.viki.elx.vm.VM;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/**
 * Primitive list operations used by the script-level {@code list} module.
 * Values move through rooted {@link Span} windows, so callbacks never retain
 * raw slot addresses. Equality and ordering use the same language operations
 * as bytecode, including numeric promotion and metamethods. The class is
 * stateless; each callback runs on its owning VM thread.
 */
public final class NListStd {
  private NListStd() {
  }

  /**
   * Builds the native symbols for the list module. Public list helpers are
   * assembled by {@code /std/list.elx} from these primitives.
   *
   * @param vm VM that will invoke the callbacks
   * @return frozen native library
   */
  public static Library create(VM vm) {
    SimpleLibrary library = new SimpleLibrary();
    library.function("copy", args -> copy(args, false));
    library.function("deepCopy", args -> copy(args, true));
    library.function("sort", NListStd::sort);
    library.function("reverse", NListStd::reverse);
    library.function("insert", NListStd::insert);
    library.function("removeAt", NListStd::removeAt);
    library.function("remove", NListStd::remove);
    library.function("pop", NListStd::pop);
    library.function("push", NListStd::push);
    library.function("clear", NListStd::clear);
    library.function("slice", NListStd::slice);
    library.function("concat", NListStd::concat);
    library.function("append", NListStd::append);
    library.function("prepend", NListStd::prepend);
    library.function("indexOf", NListStd::indexOf);
    library.function("lastIndexOf", NListStd::lastIndexOf);
    library.function("contains", NListStd::contains);
    library.function("count", NListStd::count);
    library.function("unique", NListStd::unique);
    library.function("flatten", NListStd::flatten);
    library.function("zip", NListStd::zip);
    library.function("chunk", NListStd::chunk);
    library.function("swap", NListStd::swap);
    library.function("fill", NListStd::fill);
    library.function("equals", NListStd::equals);
    library.function("isEmpty", NListStd::isEmpty);
    library.function("size", NListStd::size);
    library.function("empty", NListStd::empty);
    library.function("repeat", NListStd::repeat);
    library.function("range", NListStd::range);
    return library.freeze();
  }

  private static void copy(Call call, boolean deep) {
    call.arity(1);
    requireList(call, 0);
    Span result = call.scratch(1);
    if (deep) {
      deepCopy(call, call, 0, result, 0, new HashSet<>());
    } else {
      copyShallow(call, 0, result, 0);
    }
    call.rvalue(result, 0);
  }

  private static void copyShallow(Call values, int source, Span target, int targetIndex) {
    ContOp containers = values.containers();
    int size = containers.listSize(values, source);
    containers.newList(target, targetIndex, size);
    Span item = values.scratch(1);
    for (int i = 0; i < size; i++) {
      containers.listGet(values, source, i, item, 0);
      containers.listAppend(target, targetIndex, item, 0);
    }
  }

  private static void deepCopy(Call call, Span source, int sourceIndex,
                               Span target, int targetIndex, Set<Long> ancestors) {
    long identity = source.bits(sourceIndex);
    if (!ancestors.add(identity)) {
      throw new IllegalArgumentException("list.deepCopy does not support cyclic lists");
    }
    ContOp containers = call.containers();
    int size = containers.listSize(source, sourceIndex);
    containers.newList(target, targetIndex, size);
    Span pair = call.scratch(2);
    for (int i = 0; i < size; i++) {
      containers.listGet(source, sourceIndex, i, pair, 0);
      if (pair.type(0) == Type.LIST) {
        deepCopy(call, pair, 0, pair, 1, ancestors);
        containers.listAppend(target, targetIndex, pair, 1);
      } else {
        containers.listAppend(target, targetIndex, pair, 0);
      }
    }
    ancestors.remove(identity);
  }

  private static void sort(Call call) {
    call.arity(1);
    requireList(call, 0);
    ContOp containers = call.containers();
    int size = containers.listSize(call, 0);
    if (size < 2) {
      return;
    }

    // Snapshot into GC-rooted slots because the language comparator may run
    // script metamethods and trigger collection while the Java sort is active.
    Span snapshot = call.scratch(size);
    for (int i = 0; i < size; i++) {
      containers.listGet(call, 0, i, snapshot, i);
    }
    int[] order = new int[size], work = new int[size];
    for (int i = 0; i < size; i++) {
      order[i] = i;
    }
    mergeSort(order, work, 0, size, snapshot, call.operations());
    if (containers.listSize(call, 0) != size) {
      throw new IllegalStateException("list.sort input changed size during comparison");
    }
    Span item = call.scratch(1);
    for (int i = 0; i < size; i++) {
      item.copy(0, snapshot, order[i]);
      containers.listSet(call, 0, i, item, 0);
    }
  }

  private static void reverse(Call call) {
    call.arity(1);
    requireList(call, 0);
    ContOp containers = call.containers();
    int size = containers.listSize(call, 0);
    Span pair = call.scratch(2);
    for (int i = 0; i < size / 2; i++) {
      containers.listGet(call, 0, i, pair, 0);
      containers.listGet(call, 0, size - 1 - i, pair, 1);
      containers.listSet(call, 0, i, pair, 1);
      containers.listSet(call, 0, size - 1 - i, pair, 0);
    }
  }

  private static void insert(Call call) {
    call.arity(3);
    requireList(call, 0);
    int size = call.containers().listSize(call, 0);
    int index = index(call, 1);
    if (index < 0 || index > size) {
      throw new IllegalArgumentException("list.insert index out of bounds: " + index);
    }
    ContOp containers = call.containers();
    Span pair = call.scratch(2);
    pair.copy(0, call, 2);
    containers.listAppend(call, 0, pair, 0);
    for (int i = size; i > index; i--) {
      containers.listGet(call, 0, i - 1, pair, 1);
      containers.listSet(call, 0, i, pair, 1);
    }
    containers.listSet(call, 0, index, pair, 0);
  }

  private static void removeAt(Call call) {
    call.arity(2);
    requireList(call, 0);
    int size = call.containers().listSize(call, 0), index = index(call, 1);
    checkExistingIndex("list.removeAt", index, size);
    Span item = call.scratch(1);
    call.containers().listGet(call, 0, index, item, 0);
    call.containers().listRemove(call, 0, index);
    call.rvalue(item, 0);
  }

  private static void remove(Call call) {
    call.arity(2);
    requireList(call, 0);
    int index = find(call, 0, call, 1, 0, false);
    if (index >= 0) {
      call.containers().listRemove(call, 0, index);
    }
    call.rb(index >= 0);
  }

  private static void pop(Call call) {
    call.arity(1);
    requireList(call, 0);
    int size = call.containers().listSize(call, 0);
    if (size == 0) {
      throw new IllegalArgumentException("list.pop expects a non-empty list");
    }
    Span item = call.scratch(1);
    call.containers().listGet(call, 0, size - 1, item, 0);
    call.containers().listRemove(call, 0, size - 1);
    call.rvalue(item, 0);
  }

  private static void push(Call call) {
    call.arity(2);
    requireList(call, 0);
    call.containers().listAppend(call, 0, call, 1);
  }

  private static void clear(Call call) {
    call.arity(1);
    requireList(call, 0);
    ContOp containers = call.containers();
    while (containers.listSize(call, 0) != 0) {
      containers.listRemove(call, 0, containers.listSize(call, 0) - 1);
    }
  }

  private static void slice(Call call) {
    call.arity(2, 3);
    requireList(call, 0);
    ContOp containers = call.containers();
    int size = containers.listSize(call, 0), from = index(call, 1);
    int end = call.size() == 3 ? index(call, 2) : size;
    if (from < 0 || end < from || end > size) {
      throw new IllegalArgumentException("list.slice range out of bounds");
    }
    Span result = call.scratch(1), item = call.scratch(1);
    containers.newList(result, 0, end - from);
    for (int i = from; i < end; i++) {
      containers.listGet(call, 0, i, item, 0);
      containers.listAppend(result, 0, item, 0);
    }
    call.rvalue(result, 0);
  }

  private static void concat(Call call) {
    call.arity(2);
    requireList(call, 0);
    String separator = call.s(1);
    int size = call.containers().listSize(call, 0);
    Span snapshot = call.scratch(Math.max(1, size));
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < size; i++) {
      call.containers().listGet(call, 0, i, snapshot, i);
      if (i != 0) {
        result.append(separator);
      }
      result.append(call.operations().format(snapshot, i));
    }
    call.rs(result.toString());
  }

  private static void append(Call call) {
    combine(call, false);
  }

  private static void prepend(Call call) {
    combine(call, true);
  }

  private static void combine(Call call, boolean prepend) {
    call.arity(2);
    requireList(call, 0);
    requireList(call, 1);
    ContOp containers = call.containers();
    int count = containers.listSize(call, 1);
    Span snapshot = call.scratch(count == 0 ? 1 : count);
    for (int i = 0; i < count; i++) {
      containers.listGet(call, 1, i, snapshot, i);
    }
    if (prepend) {
      for (int i = 0; i < count; i++) {
        insertValue(call, 0, i, snapshot, i);
      }
    } else {
      for (int i = 0; i < count; i++) {
        containers.listAppend(call, 0, snapshot, i);
      }
    }
  }

  private static void insertValue(Call values, int listIndex, int index, Span source, int sourceIndex) {
    ContOp containers = values.containers();
    int size = containers.listSize(values, listIndex);
    Span pair = values.scratch(2);
    pair.copy(0, source, sourceIndex);
    containers.listAppend(values, listIndex, pair, 0);
    for (int i = size; i > index; i--) {
      containers.listGet(values, listIndex, i - 1, pair, 1);
      containers.listSet(values, listIndex, i, pair, 1);
    }
    containers.listSet(values, listIndex, index, pair, 0);
  }

  private static void indexOf(Call call) {
    call.arity(2, 3);
    requireList(call, 0);
    call.ri(find(call, 0, call, 1, call.size() == 3 ? index(call, 2) : 0, false));
  }

  private static void lastIndexOf(Call call) {
    call.arity(2);
    requireList(call, 0);
    call.ri(find(call, 0, call, 1, 0, true));
  }

  private static void contains(Call call) {
    call.arity(2);
    requireList(call, 0);
    call.rb(find(call, 0, call, 1, 0, false) >= 0);
  }

  private static void count(Call call) {
    call.arity(2);
    requireList(call, 0);
    ContOp containers = call.containers();
    int size = containers.listSize(call, 0), count = 0;
    Span item = call.scratch(1);
    for (int i = 0; i < size; i++) {
      containers.listGet(call, 0, i, item, 0);
      if (call.operations().test(Instr.Relation.EQ, item, 0, call, 1)) {
        count++;
      }
    }
    call.ri(count);
  }

  private static void unique(Call call) {
    call.arity(1);
    requireList(call, 0);
    ContOp containers = call.containers();
    int size = containers.listSize(call, 0);
    Span snapshot = call.scratch(size == 0 ? 2 : size + 1);
    for (int i = 0; i < size; i++) {
      containers.listGet(call, 0, i, snapshot, i);
    }
    containers.newList(snapshot, size, size);
    Span candidate = call.scratch(1), previous = call.scratch(1);
    int unique = 0;
    for (int i = 0; i < size; i++) {
      candidate.copy(0, snapshot, i);
      boolean seen = false;
      for (int j = 0; j < unique; j++) {
        containers.listGet(snapshot, size, j, previous, 0);
        if (call.operations().test(Instr.Relation.EQ, candidate, 0, previous, 0)) {
          seen = true;
          break;
        }
      }
      if (!seen) {
        containers.listAppend(snapshot, size, candidate, 0);
        unique++;
      }
    }
    call.rvalue(snapshot, size);
  }

  private static void flatten(Call call) {
    call.arity(1);
    requireList(call, 0);
    ContOp containers = call.containers();
    int size = containers.listSize(call, 0);
    Span result = call.scratch(1), item = call.scratch(1), child = call.scratch(1);
    containers.newList(result, 0, size);
    for (int i = 0; i < size; i++) {
      containers.listGet(call, 0, i, item, 0);
      if (item.type(0) == Type.LIST) {
        int childSize = containers.listSize(item, 0);
        for (int j = 0; j < childSize; j++) {
          containers.listGet(item, 0, j, child, 0);
          containers.listAppend(result, 0, child, 0);
        }
      } else {
        containers.listAppend(result, 0, item, 0);
      }
    }
    call.rvalue(result, 0);
  }

  private static void zip(Call call) {
    call.arity(2);
    requireList(call, 0);
    requireList(call, 1);
    ContOp containers = call.containers();
    int size = Math.min(containers.listSize(call, 0), containers.listSize(call, 1));
    Span result = call.scratch(1), pair = call.scratch(2), nested = call.scratch(1);
    containers.newList(result, 0, size);
    for (int i = 0; i < size; i++) {
      containers.listGet(call, 0, i, pair, 0);
      containers.listGet(call, 1, i, pair, 1);
      containers.newList(nested, 0, 2);
      containers.listAppend(nested, 0, pair, 0);
      containers.listAppend(nested, 0, pair, 1);
      containers.listAppend(result, 0, nested, 0);
    }
    call.rvalue(result, 0);
  }

  private static void chunk(Call call) {
    call.arity(2);
    requireList(call, 0);
    int size = call.containers().listSize(call, 0), width = index(call, 1);
    if (width <= 0) {
      throw new IllegalArgumentException("list.chunk size must be positive");
    }
    int chunks = (int) ((size + (long) width - 1) / width);
    Span result = call.scratch(1), chunk = call.scratch(1), item = call.scratch(1);
    call.containers().newList(result, 0, chunks);
    for (int start = 0; start < size; start += width) {
      int end = width >= size - start ? size : start + width;
      call.containers().newList(chunk, 0, end - start);
      for (int i = start; i < end; i++) {
        call.containers().listGet(call, 0, i, item, 0);
        call.containers().listAppend(chunk, 0, item, 0);
      }
      call.containers().listAppend(result, 0, chunk, 0);
    }
    call.rvalue(result, 0);
  }

  private static void swap(Call call) {
    call.arity(3);
    requireList(call, 0);
    int size = call.containers().listSize(call, 0), left = index(call, 1), right = index(call, 2);
    checkExistingIndex("list.swap", left, size);
    checkExistingIndex("list.swap", right, size);
    Span pair = call.scratch(2);
    call.containers().listGet(call, 0, left, pair, 0);
    call.containers().listGet(call, 0, right, pair, 1);
    call.containers().listSet(call, 0, left, pair, 1);
    call.containers().listSet(call, 0, right, pair, 0);
  }

  private static void fill(Call call) {
    call.arity(2);
    requireList(call, 0);
    int size = call.containers().listSize(call, 0);
    for (int i = 0; i < size; i++) {
      call.containers().listSet(call, 0, i, call, 1);
    }
  }

  private static void equals(Call call) {
    call.arity(2);
    requireList(call, 0);
    requireList(call, 1);
    ContOp containers = call.containers();
    int size = containers.listSize(call, 0);
    if (size != containers.listSize(call, 1)) {
      call.rb(false);
      return;
    }
    Span pair = call.scratch(2);
    for (int i = 0; i < size; i++) {
      containers.listGet(call, 0, i, pair, 0);
      containers.listGet(call, 1, i, pair, 1);
      if (!call.operations().test(Instr.Relation.EQ, pair, 0, pair, 1)) {
        call.rb(false);
        return;
      }
    }
    call.rb(true);
  }

  private static void isEmpty(Call call) {
    call.arity(1);
    requireList(call, 0);
    call.rb(call.containers().listSize(call, 0) == 0);
  }

  private static void size(Call call) {
    call.arity(1);
    requireList(call, 0);
    call.ri(call.containers().listSize(call, 0));
  }

  private static void empty(Call call) {
    call.arity(0);
    Span result = call.scratch(1);
    call.containers().newList(result, 0, 0);
    call.rvalue(result, 0);
  }

  private static void repeat(Call call) {
    call.arity(2);
    long countValue = call.i(1);
    if (countValue < 0 || countValue > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("list.repeat count out of range: " + countValue);
    }
    int count = (int) countValue;
    Span result = call.scratch(1);
    call.containers().newList(result, 0, count);
    for (int i = 0; i < count; i++) {
      call.containers().listAppend(result, 0, call, 0);
    }
    call.rvalue(result, 0);
  }

  private static void range(Call call) {
    call.arity(1, 3);
    long start = call.size() == 1 ? 0 : call.i(0);
    long end = call.size() == 1 ? call.i(0) : call.i(1);
    long step = call.size() == 3 ? call.i(2) : 1;
    if (step == 0) {
      throw new IllegalArgumentException("list.range step cannot be zero");
    }
    BigInteger countValue = rangeCount(start, end, step);
    if (countValue.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
      throw new IllegalArgumentException("list.range result is too large");
    }
    int count = countValue.intValueExact();
    Span result = call.scratch(1), item = call.scratch(1);
    call.containers().newList(result, 0, count);
    long value = start;
    for (int i = 0; i < count; i++) {
      item.setLong(0, value);
      call.containers().listAppend(result, 0, item, 0);
      if (i + 1 < count) {
        value = Math.addExact(value, step);
      }
    }
    call.rvalue(result, 0);
  }

  private static BigInteger rangeCount(long start, long end, long step) {
    BigInteger from = BigInteger.valueOf(start), to = BigInteger.valueOf(end);
    BigInteger stride = BigInteger.valueOf(step).abs();
    BigInteger distance = step > 0 ? to.subtract(from) : from.subtract(to);
    if (distance.signum() <= 0) {
      return BigInteger.ZERO;
    }
    return distance.add(stride).subtract(BigInteger.ONE).divide(stride);
  }

  private static int find(Call call, int listIndex, Span value, int valueIndex, int from, boolean backwards) {
    ContOp containers = call.containers();
    int size = containers.listSize(call, listIndex);
    Span item = call.scratch(1);
    ValueOperations operations = call.operations();
    if (backwards) {
      for (int i = size - 1; i >= 0; i--) {
        containers.listGet(call, listIndex, i, item, 0);
        if (operations.test(Instr.Relation.EQ, item, 0, value, valueIndex)) {
          return i;
        }
      }
    } else {
      for (int i = Math.max(0, from); i < size; i++) {
        containers.listGet(call, listIndex, i, item, 0);
        if (operations.test(Instr.Relation.EQ, item, 0, value, valueIndex)) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int index(Call call, int argument) {
    return Math.toIntExact(call.i(argument));
  }

  private static void requireList(Span values, int index) {
    if (values.type(index) != Type.LIST) {
      throw new IllegalArgumentException("Argument " + index + " must be LIST, got " + values.type(index));
    }
  }

  private static void checkExistingIndex(String operation, int index, int size) {
    if (index < 0 || index >= size) {
      throw new IllegalArgumentException(operation + " index out of bounds: " + index + " (size " + size + ")");
    }
  }

  private static void mergeSort(int[] order, int[] work, int from, int to,
                                Span values, ValueOperations operations) {
    if (to - from < 2) {
      return;
    }
    int middle = (from + to) >>> 1;
    mergeSort(order, work, from, middle, values, operations);
    mergeSort(order, work, middle, to, values, operations);
    int left = from, right = middle, output = from;
    while (left < middle && right < to) {
      int a = order[left], b = order[right];
      if (operations.test(Instr.Relation.LT, values, b, values, a)) {
        work[output++] = order[right++];
      } else {
        work[output++] = order[left++];
      }
    }
    while (left < middle) {
      work[output++] = order[left++];
    }
    while (right < to) {
      work[output++] = order[right++];
    }
    System.arraycopy(work, from, order, from, to - from);
  }
}
