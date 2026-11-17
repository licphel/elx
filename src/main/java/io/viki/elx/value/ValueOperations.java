package io.viki.elx.value;

import io.viki.elx.Type;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.lib.Span;

/**
 * VM-owned language semantics shared by bytecode and host libraries.
 * Operations honor numeric promotion and metamethods, may run script code and
 * collect garbage, and complete synchronously for host callers. All spans
 * must belong to the same VM. Results may alias operands: implementations read
 * operands before overwriting a destination. Instances are thread-confined.
 *
 * <p>Numeric built-ins run first. Otherwise hooks are selected left then right
 * with original operand order, followed by built-in container/string fallback.
 * Comparisons follow script relations, including NaN; there is no implicit
 * Type-ordinal ordering. Raw table key equality/hash never dispatch hooks.
 */
public interface ValueOperations {
  /**
   * Applies one unary language operation to a value slot.
   *
   * @param operation        unary {@link Op}, such as {@link Op#NEG} or {@link Op#NOT}
   * @param source           borrowed source span
   * @param index            source slot index
   * @param destination      borrowed destination span
   * @param destinationIndex destination slot index
   * @throws IllegalArgumentException if the operation is not unary or the value is invalid
   */
  void unary(Op operation, Span source, int index, Span destination, int destinationIndex);

  /**
   * Applies one binary language operation to two value slots.
   *
   * @param operation        binary {@link Op}, such as {@link Op#ADD} or {@link Op#MUL}
   * @param left             left operand span
   * @param leftIndex        left operand slot index
   * @param right            right operand span
   * @param rightIndex       right operand slot index
   * @param destination      destination span
   * @param destinationIndex destination slot index
   * @throws IllegalArgumentException if the operation is not binary or the values are invalid
   */
  void binary(Op operation, Span left, int leftIndex, Span right, int rightIndex, Span destination, int destinationIndex);

  /**
   * Tests two values using the same relation semantics as bytecode comparison.
   *
   * @param relation   comparison relation
   * @param left       left operand span
   * @param leftIndex  left operand slot index
   * @param right      right operand span
   * @param rightIndex right operand slot index
   * @return whether the relation holds
   */
  boolean test(Instr.Relation relation, Span left, int leftIndex, Span right, int rightIndex);

  /**
   * Reads an indexed value through language GET semantics.
   *
   * @param target           indexed list, table, or host value
   * @param targetIndex      target slot index
   * @param key              key or list-index span
   * @param keyIndex         key slot index
   * @param destination      destination span
   * @param destinationIndex destination slot index
   */
  void get(Span target, int targetIndex, Span key, int keyIndex, Span destination, int destinationIndex);

  /**
   * Writes an indexed value through language SET semantics.
   *
   * @param target      indexed list, table, or host value
   * @param targetIndex target slot index
   * @param key         key or list-index span
   * @param keyIndex    key slot index
   * @param value       source value span
   * @param valueIndex  source slot index
   */
  void set(Span target, int targetIndex, Span key, int keyIndex, Span value, int valueIndex);

  /**
   * Converts a value using explicit ELX conversion rules.
   *
   * @param targetType       requested target type
   * @param source           source span
   * @param sourceIndex      source slot index
   * @param destination      destination span
   * @param destinationIndex destination slot index
   */
  void convert(Type targetType, Span source, int sourceIndex, Span destination, int destinationIndex);

  /**
   * Calls a function or a value's CALL hook, filling outputs and clearing unused
   * output slots to void. A normal function receives inputs unchanged, while a
   * CALL hook receives its object as the first argument.
   *
   * @param callable      span containing the function or callable value
   * @param callableIndex callable slot index
   * @param inputs        contiguous input span
   * @param outputs       destination span with capacity for returned values
   * @return number of values written, from zero through sixteen
   * @throws IllegalArgumentException if any span belongs to another VM
   */
  int call(Span callable, int callableIndex, Span inputs, Span outputs);

  /**
   * Hashes a language value through the semantic HASH operation.
   *
   * @param source source span
   * @param index  source slot index
   * @return integer hash value
   * @throws IllegalArgumentException if a HASH hook does not return one integer
   */
  long hash(Span source, int index);

  /**
   * Formats a value recursively, honoring STR and detecting container cycles.
   *
   * @param source source span
   * @param index  source slot index
   * @return formatted ELX text
   */
  String format(Span source, int index);
}
