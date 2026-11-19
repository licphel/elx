package io.viki.elx.memory;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/** Thread-confined UTF-8 backend; cached metadata is discarded when storage is freed. */
final class Utf8StringStore implements StringStore {
  private final ElxHeap heap;
  private final Memory memory;
  private final Long2LongOpenHashMap lengths = new Long2LongOpenHashMap();
  private final Long2LongOpenHashMap hashes = new Long2LongOpenHashMap();

  Utf8StringStore(ElxHeap heap) {
    this.heap = heap;
    this.memory = heap.memory();
  }

  void invalidateImage() {
    // Linked images contain strings at interior addresses.
    lengths.clear();
    hashes.clear();
  }

  void invalidate(long string) {
    lengths.remove(string);
    hashes.remove(string);
  }

  @Override
  public long importJava(String value) {
    return heap.importString(value);
  }

  @Override
  public String exportJava(long string) {
    return heap.exportString(string);
  }

  @Override
  public int codePointLength(long string) {
    if (lengths.containsKey(string)) {
      return (int) lengths.get(string);
    }
    int length = utf8Length(string), count = 0;
    for (int i = 0; i < length; i++) {
      if ((memory.load8(string + 4 + i) & 0xc0) != 0x80) {
        count++;
      }
    }
    lengths.put(string, count);
    return count;
  }

  @Override
  public int utf8Length(long string) {
    return memory.load32(string);
  }

  @Override
  public boolean equal(long left, long right) {
    return left == right || compare(left, right) == 0;
  }

  @Override
  public int compare(long left, long right) {
    int a = utf8Length(left), b = utf8Length(right);
    for (int i = 0, n = Math.min(a, b); i < n; i++) {
      int comparison = Integer.compare(memory.load8(left + 4 + i) & 255, memory.load8(right + 4 + i) & 255);
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(a, b);
  }

  @Override
  public long hash(long string) {
    if (hashes.containsKey(string)) {
      return hashes.get(string);
    }
    long hash = 0xcbf29ce484222325L;
    for (int i = 0, n = utf8Length(string); i < n; i++) {
      hash = (hash ^ (memory.load8(string + 4 + i) & 255)) * 0x100000001b3L;
    }
    hashes.put(string, hash);
    return hash;
  }

  @Override
  public long concat(long left, long right) {
    int a = utf8Length(left), b = utf8Length(right);
    long result = heap.allocate(ElxHeap.Layout.STRING, Math.addExact(a, b));
    memory.copyBytes(result + 4, left + 4, a);
    memory.copyBytes(result + 4 + a, right + 4, b);
    return result;
  }

  @Override
  public long slice(long string, int from, int to) {
    java.util.Objects.checkFromToIndex(from, to, codePointLength(string));
    int start = -1, end = -1, count = 0, bytes = utf8Length(string);
    for (int i = 0; i <= bytes; i++) {
      if (i == bytes || (memory.load8(string + 4 + i) & 0xc0) != 0x80) {
        if (count == from) {
          start = i;
        }
        if (count == to) {
          end = i;
          break;
        }
        count++;
      }
    }
    long result = heap.allocate(ElxHeap.Layout.STRING, end - start);
    memory.copyBytes(result + 4, string + 4 + start, end - start);
    return result;
  }
}
