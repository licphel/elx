package io.viki.elx.memory;

/**
 * VM-owned immutable STRING storage, independent of EXTERNAL objects.
 * String references are opaque outside this boundary. The first backend stores
 * UTF-8, including linked-image strings. Length/slices use Unicode code points;
 * comparison uses Unicode scalar order, not Java UTF-16 code-unit order.
 * Hash and code-point length can be cached because strings are immutable.
 * Thread-confined; VM slot roots govern string lifetime.
 */
public interface StringStore {
  long importJava(String value);

  String exportJava(long string);

  int codePointLength(long string);

  int utf8Length(long string);

  boolean equal(long left, long right);

  int compare(long left, long right);

  long hash(long string);

  long concat(long left, long right);

  /** Slice [fromCodePoint, toCodePoint), rejecting out-of-range bounds. */
  long slice(long string, int fromCodePoint, int toCodePoint);
}
