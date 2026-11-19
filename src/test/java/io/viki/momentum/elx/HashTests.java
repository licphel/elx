package io.viki.elx;

import io.viki.elx.memory.*;
import io.viki.elx.value.ElxTable;
import io.viki.elx.value.RawValues;
import java.util.HashSet;
import java.util.Set;

public final class HashTests {
  private static int checks;

  public static void main(String[] args) {
    run();
  }

  public static void run() {
    exercise(new JavaMemory(16));
    exercise(new NativeMemory(16));
    System.out.println("ELX hashing: " + checks + " checks passed");
  }

  private static void exercise(Memory memory) {
    try (memory; ElxHeap heap = new ElxHeap(new Arena(memory))) {
      long values = heap.allocate(ElxHeap.Layout.VALUES, 512);
      int count = 0;
      long[] integers = {0, 1, -1, 127, 255, 32767, 65535, Integer.MAX_VALUE,
          0xffffffffL, 1L << 53, (1L << 53) + 1, Long.MIN_VALUE, Long.MAX_VALUE};
      for (Type type : Type.values()) {
        if (!type.numeric()) continue;
        for (long integer : integers) {
          long bits = type.integer() ? Type.normalize(integer, type)
              : Type.cast(integer, Type.LONG, type);
          Slot.write(memory, Slot.address(values, count++), type, bits);
        }
      }
      for (double number : new double[]{-0.0, 0.5, -0.5, 0x1p63, Math.nextDown(0x1p64),
          0x1p64, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
        Slot.write(memory, Slot.address(values, count++), Type.DOUBLE, Double.doubleToRawLongBits(number));
      }
      for (int i = 0; i < count; i++) {
        long a = Slot.address(values, i);
        for (int j = 0; j < count; j++) {
          long b = Slot.address(values, j);
          if (RawValues.equal(memory, a, b)) {
            check(RawValues.hash(memory, a) == RawValues.hash(memory, b), "Equal numeric keys hash differently");
          }
        }
      }
      long table = heap.allocate(ElxHeap.Layout.TABLE, 0);
      Set<Long> hashes = new HashSet<>();
      long key = values, value = Slot.address(values, 1), out = Slot.address(values, 2);
      for (int i = 0; i < 4096; i++) {
        Slot.write(memory, key, Type.LONG, 9223372036854770000L + i);
        Slot.write(memory, value, Type.INT, i);
        hashes.add(RawValues.hash(memory, key));
        ElxTable.set(heap, table, key, value);
      }
      check(hashes.size() == 4096, "Contiguous long keys collapsed during hashing");
      for (int i = 0; i < 4096; i++) {
        Slot.write(memory, key, Type.LONG, 9223372036854770000L + i);
        ElxTable.get(heap, table, key, out);
        check(Slot.bits(memory, out) == i, "Large integer key lookup failed");
      }
      // Distinct string allocations must probe identically, including after GC/address reuse.
      String text = "long-key-".repeat(64);
      long string = heap.importString(text);
      Slot.write(memory, key, Type.STRING, string);
      ElxTable.set(heap, table, key, value);
      long expectedHash = RawValues.hash(memory, key);
      heap.startGc(false);
      heap.mark(string);
      heap.sweep();
      check(heap.strings().hash(string) == expectedHash, "Surviving string hash changed");
      heap.startGc(false);
      heap.sweep();
      long replacement = heap.importString("different".repeat(64));
      Slot.write(memory, key = heap.allocate(ElxHeap.Layout.VALUES, 1), Type.STRING, replacement);
      check(heap.strings().hash(replacement) == RawValues.hash(memory, key), "Freed string hash reused");
      // Linked images own strings at interior addresses, not STRING allocation bases.
      long image = heap.allocateImage(1, 8);
      long embedded = image + Slot.SIZE;
      memory.store32(embedded, 4);
      memory.store32(embedded + Integer.BYTES, 0x61616161);
      heap.strings().hash(embedded);
      heap.strings().codePointLength(embedded);
      heap.startGc(false);
      heap.sweep();
      long nextImage = heap.allocateImage(1, 8);
      embedded = nextImage + Slot.SIZE;
      memory.store32(embedded, 3);
      memory.store32(embedded + Integer.BYTES, 0x626262);
      key = heap.allocate(ElxHeap.Layout.VALUES, 1);
      Slot.write(memory, key, Type.STRING, embedded);
      check(heap.strings().hash(embedded) == RawValues.hash(memory, key), "Freed image string hash reused");
      check(heap.strings().codePointLength(embedded) == 3, "Freed image string length reused");
      table = heap.allocate(ElxHeap.Layout.TABLE, 0);
      long slots = heap.allocate(ElxHeap.Layout.VALUES, 3);
      Slot.write(memory, slots, Type.STRING, heap.importString(text));
      Slot.write(memory, slots + Slot.SIZE, Type.INT, 42);
      ElxTable.set(heap, table, slots, slots + Slot.SIZE);
      Slot.write(memory, slots, Type.STRING, heap.importString(text));
      ElxTable.get(heap, table, slots, slots + 2L * Slot.SIZE);
      check(Slot.bits(memory, slots + 2L * Slot.SIZE) == 42, "Distinct equal string key lookup failed");
    }
  }

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }
}
