package io.viki.elx.lib;

import io.viki.elx.Type;
import org.jspecify.annotations.Nullable;

/**
 * Host invocation with indexed input slots and a separate append-only result
 * window. Borrowed and thread-confined. Input writes never overwrite results.
 * Empty results differ from returning one null. At most sixteen values may be
 * produced; unused caller capacity does not change the logical result count.
 */
public interface Call extends Span {
  /** Shared language limit for native and script return windows. */
  int MAX_RESULTS = 16;

  /**
   * Requires exactly the specified number of input arguments.
   *
   * @param expected required argument count
   * @throws IllegalArgumentException if the actual count differs
   */
  default void arity(int expected) {
    arity(expected, expected);
  }

  /**
   * Requires an input count within an inclusive range.
   *
   * @param minimum minimum accepted count
   * @param maximum maximum accepted count
   * @throws IllegalArgumentException if the actual count is outside the range
   */
  default void arity(int minimum, int maximum) {
    int actual = size();
    if (actual < minimum || actual > maximum) {
      throw new IllegalArgumentException(
          "Expected " + minimum + " to " + maximum + " arguments, got " + actual);
    }
  }

  /**
   * Appends a tagged value to the result window.
   *
   * @param type result type
   * @param bits raw result payload
   * @throws IllegalStateException if the result window is full
   */
  void r(Type type, long bits);

  /**
   * Appends an ELX string result.
   *
   * @param value string result
   */
  void rs(String value);

  /**
   * Appends a Java value result; Java {@code null} becomes ELX null.
   *
   * @param value Java result value
   */
  void ro(@Nullable Object value);

  /**
   * Returns the number of results appended so far.
   *
   * @return result count from zero through {@link #MAX_RESULTS}
   */
  int returnedCount();

  /**
   * Borrows a zero-initialized, GC-rooted scratch window until this invocation
   * returns. Nested requests yield independent windows; no element is boxed.
   *
   * @param size requested scratch slot count
   * @return borrowed scratch span
   * @throws IllegalArgumentException if {@code size} is negative
   */
  Span scratch(int size);

  /** Appends the ELX missing-value sentinel. */
  default void rv() {
    r(Type.VOID, 0);
  }

  /** Appends an explicit ELX null value. */
  default void rn() {
    r(Type.NULL, 0);
  }

  /**
   * Appends an 8-bit signed integer result.
   *
   * @param value result value
   */
  default void rbyte(byte value) {
    r(Type.BYTE, value);
  }

  /**
   * Appends a 16-bit signed integer result.
   *
   * @param value result value
   */
  default void rshort(short value) {
    r(Type.SHORT, value);
  }

  /**
   * Appends a 32-bit signed integer result.
   *
   * @param value result value
   */
  default void ri(int value) {
    r(Type.INT, value);
  }

  /**
   * Appends a 64-bit signed integer result.
   *
   * @param value result value
   */
  default void rl(long value) {
    r(Type.LONG, value);
  }

  /**
   * Appends an IEEE-754 single-precision result.
   *
   * @param value result value
   */
  default void rf(float value) {
    r(Type.FLOAT, Integer.toUnsignedLong(Float.floatToRawIntBits(value)));
  }

  /**
   * Appends an IEEE-754 double-precision result.
   *
   * @param value result value
   */
  default void rd(double value) {
    r(Type.DOUBLE, Double.doubleToRawLongBits(value));
  }

  /**
   * Appends a boolean result.
   *
   * @param value result value
   */
  default void rb(boolean value) {
    r(Type.BOOL, value ? 1 : 0);
  }

  /**
   * Appends a copy of one input argument.
   *
   * @param index source argument index
   */
  default void rarg(int index) {
    r(type(index), bits(index));
  }

  /**
   * Appends a value from another live span in the same VM.
   *
   * @param source source span
   * @param index source slot index
   * @throws IllegalArgumentException if the source belongs to another VM
   */
  void rvalue(Span source, int index);
}
