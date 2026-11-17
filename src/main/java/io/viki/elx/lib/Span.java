package io.viki.elx.lib;

import io.viki.elx.Type;
import io.viki.elx.value.ContOp;
import io.viki.elx.value.ValueOperations;
import org.jspecify.annotations.Nullable;

/**
 * Borrowed indexed sequence of tagged values, confined to its owning VM thread.
 * Reads and writes use the same zero-based index space. This interface carries
 * neither call-result state nor container backing addresses. The supplying
 * scope roots its values and keeps them valid across nested script calls.
 * Retain values beyond that scope only through a Pin.
 */
public interface Span {
  /**
   * Returns the number of addressable slots in this borrowed view.
   *
   * @return slot count
   */
  int size();

  /**
   * Returns the runtime type tag stored at an index.
   *
   * @param index zero-based slot index
   * @return stored ELX type
   */
  Type type(int index);

  /**
   * Returns the raw payload bits stored at an index.
   *
   * @param index zero-based slot index
   * @return tagged value payload
   */
  long bits(int index);

  /**
   * Replaces one slot with a tagged value.
   *
   * @param index destination slot index
   * @param type value type
   * @param bits raw payload for {@code type}
   * @throws IllegalArgumentException if the type or payload is invalid
   */
  void set(int index, Type type, long bits);

  /**
   * Decodes a string slot at the Java boundary.
   *
   * @param index string slot index
   * @return decoded Java string
   * @throws IllegalArgumentException if the slot is not a string
   */
  String s(int index);

  /**
   * Exports a slot as its Java representation; ELX null becomes Java null.
   *
   * @param index source slot index
   * @return exported value, possibly Java {@code null}
   */
  @Nullable Object o(int index);

  /**
   * Writes a Java string as an ELX string value.
   *
   * @param index destination slot index
   * @param value string value
   */
  void setString(int index, String value);

  /**
   * Imports a Java value into a slot using host conversion rules.
   *
   * @param index destination slot index
   * @param value value to import; Java {@code null} becomes ELX null
   */
  void setObject(int index, @Nullable Object value);

  /**
   * Returns the VM semantic operation facade for this span.
   *
   * @return shared value operations
   */
  ValueOperations operations();

  /**
   * Returns raw list and table operations for this span.
   *
   * @return shared container operations
   */
  ContOp containers();

  /**
   * Reads a checked integer value.
   *
   * @param index integer slot index
   * @return normalized signed or unsigned integer payload
   * @throws IllegalArgumentException if the slot is not integral
   */
  default long i(int index) {
    Type type = type(index);
    if (!type.integer()) {
      throw new IllegalArgumentException("Slot " + index + ": expected integer, got " + type);
    }
    return Type.normalize(bits(index), type);
  }

  /**
   * Reads a numeric value as a Java double.
   *
   * @param index numeric slot index
   * @return converted numeric value
   */
  default double d(int index) {
    return Type.asDouble(bits(index), type(index));
  }

  /**
   * Reads a checked boolean value.
   *
   * @param index boolean slot index
   * @return boolean value
   * @throws IllegalArgumentException if the slot is not boolean
   */
  default boolean b(int index) {
    if (type(index) != Type.BOOL) {
      throw new IllegalArgumentException("Slot " + index + ": expected BOOL, got " + type(index));
    }
    return bits(index) != 0;
  }

  /**
   * Copies a tagged value within one VM.
   *
   * @param index destination slot index
   * @param source source span in the same VM
   * @param sourceIndex source slot index
   * @throws IllegalArgumentException if the spans belong to different VMs
   */
  void copy(int index, Span source, int sourceIndex);

  /**
   * Writes the missing-value sentinel.
   *
   * @param index destination slot index
   */
  default void setVoid(int index) {
    set(index, Type.VOID, 0);
  }

  /**
   * Writes an explicit ELX null value.
   *
   * @param index destination slot index
   */
  default void setNull(int index) {
    set(index, Type.NULL, 0);
  }

  /**
   * Writes an 8-bit signed integer value.
   *
   * @param index destination slot index
   * @param value value to write
   */
  default void setByte(int index, byte value) {
    set(index, Type.BYTE, value);
  }

  /**
   * Writes a 16-bit signed integer value.
   *
   * @param index destination slot index
   * @param value value to write
   */
  default void setShort(int index, short value) {
    set(index, Type.SHORT, value);
  }

  /**
   * Writes a 32-bit signed integer value.
   *
   * @param index destination slot index
   * @param value value to write
   */
  default void setInt(int index, int value) {
    set(index, Type.INT, value);
  }

  /**
   * Writes a 64-bit signed integer value.
   *
   * @param index destination slot index
   * @param value value to write
   */
  default void setLong(int index, long value) {
    set(index, Type.LONG, value);
  }

  /**
   * Writes an IEEE-754 single-precision value.
   *
   * @param index destination slot index
   * @param value value to write
   */
  default void setFloat(int index, float value) {
    set(index, Type.FLOAT, Integer.toUnsignedLong(Float.floatToRawIntBits(value)));
  }

  /**
   * Writes an IEEE-754 double-precision value.
   *
   * @param index destination slot index
   * @param value value to write
   */
  default void setDouble(int index, double value) {
    set(index, Type.DOUBLE, Double.doubleToRawLongBits(value));
  }

  /**
   * Writes a boolean value.
   *
   * @param index destination slot index
   * @param value value to write
   */
  default void setBoolean(int index, boolean value) {
    set(index, Type.BOOL, value ? 1 : 0);
  }
}
