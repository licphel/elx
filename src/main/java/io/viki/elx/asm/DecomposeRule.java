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

package io.viki.elx.asm;

import java.util.List;

/**
 * Immutable compiler-side expansion template, never allocated by VM execution.
 * Rules of one operation are ordered, semantically equivalent alternatives.
 * A compiler chooses the first rule whose operations and linked libraries it supports.
 * These are alternative expansions, not a sequence of rules to apply together.
 * Operands are bound to the source instruction; temporaries are fresh per expansion
 * and branch destinations are resolved after expansion. An unavailable expansion
 * is a compilation error, never permission to change the operation's semantics.
 *
 * @param name stable diagnostic name for this expansion
 * @param steps primitive instructions and link-time calls emitted by the expansion
 */
public record DecomposeRule(String name, List<Step> steps) {
  /**
   * Validates and copies an expansion template.
   *
   * @param name diagnostic name
   * @param steps expansion steps, in emission order
   * @throws IllegalArgumentException if no steps are supplied
   */
  public DecomposeRule {
    steps = List.copyOf(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("A decomposition must contain at least one step: " + name);
    }
  }

  /**
   * Creates an expansion template from a sequence of steps.
   *
   * @param name diagnostic name
   * @param steps expansion steps, in emission order
   * @return validated decomposition rule
   * @throws IllegalArgumentException if no steps are supplied
   */
  public static DecomposeRule of(String name, Step... steps) {
    return new DecomposeRule(name, List.of(steps));
  }

  /**
   * Creates a primitive instruction step.
   *
   * @param op base operation emitted by the step
   * @param operands symbolic operands in the operation's documented order
   * @return instruction expansion step
   * @throws IllegalArgumentException if {@code op} is not a base operation
   */
  public static Instruction instruction(Op op, Operand... operands) {
    return new Instruction(op, List.of(operands));
  }

  /**
   * Creates a link-time library call that produces one output.
   *
   * @param symbol internal library symbol resolved by the linker
   * @param output output operand receiving the call result
   * @param inputs input operands passed to the symbol
   * @return library-call expansion step
   */
  public static LibraryCall function(String symbol, Operand output, Operand... inputs) {
    return new LibraryCall(symbol, List.of(inputs), List.of(output));
  }

  /**
   * Symbolic operands are substituted before encoding; TMP values are fresh virtual registers.
   */
  public enum Operand {
    /** Source A register, without role-dependent reinterpretation. */
    A,
    /** Source B register, without role-dependent reinterpretation. */
    B,
    /** Source C register, without role-dependent reinterpretation. */
    C,
    /** Addresses of source register windows. */
    ADDRESS_A,
    ADDRESS_B,
    ADDRESS_C,
    /** Current function/closure handle. */
    FRAME,
    /** Window byte count from source B. */
    WINDOW_BYTES,
    /** Source B value count. */
    COUNT_B,
    /** Source extension naming a count register. */
    COUNT_REGISTER,
    /** Slot offset of a captured binding. */
    CAPTURE_OFFSET,
    /** Dynamic operator control for the source instruction. */
    OPERATION,
    /** Packed-call permission to consume an internal list. */
    ALLOW_LIST,
    /** Constants for parameter-present lowering. */
    INVALID_TAG,
    /** Unsigned-byte non-equality comparison relation. */
    UBYTE_NE,
    /** Instruction destination. */
    DST,
    /** First input value. */
    X,
    /** Second input value. */
    Y,
    /** Third input value. */
    Z,
    /** Integer width or floating-point precision required by the source operation. */
    KIND,
    /** Branch destination, resolved after expansion. */
    LABEL,
    /** Constant pool entry indexed by the source extension. */
    CONSTANT,
    /** Numeric index of a constant-pool entry. */
    CONSTANT_INDEX,
    /** Final bool value on which a dynamic comparison branch is taken. */
    BRANCH_VALUE,
    /** Immediate constant carried by the source operation. */
    IMM,
    /** Address of the source call's consecutive argument registers. */
    ARG_BASE,
    /** Number of arguments. */
    ARG_COUNT,
    /** Expands to the source call's requested consecutive output registers. */
    RESULT_BASE,
    /** Requested result capacity, from zero through four. */
    RESULT_COUNT,
    /** Literal zero, not register zero. */
    ZERO,
    /** Literal one, not register one. */
    ONE,
    /** Literal eight bytes, used for payload addressing. */
    EIGHT,
    /** Literal signed 64-bit integer kind, independent of the source operands. */
    I64,
    /** Source iterator cursor, separate from the yielded logical index. */
    CURSOR,
    /** Source sequence's compile-time value count. */
    VALUE_COUNT,
    /** First result slot. */
    OUT0,
    /** Second result slot. */
    OUT1,
    /** Third result slot. */
    OUT2,
    /** Fourth result slot, used for a table iterator's next cursor. */
    OUT3,
    /** Fresh scratch value local to this expansion. */
    TMP0,
    /** Second fresh scratch value local to this expansion. */
    TMP1
  }

  /** An expansion consists of primitive instructions and statically linked internal-library calls. */
  public sealed interface Step permits Instruction, LibraryCall {
  }

  /**
   * A primitive base instruction whose operands follow the operation's documented order.
   *
   * @param op base operation to emit
   * @param operands symbolic operands in operation order
   */
  public record Instruction(Op op, List<Operand> operands) implements Step {
    /**
     * Validates and copies an instruction step.
     *
     * @param op base operation to emit
     * @param operands symbolic operands in operation order
     * @throws IllegalArgumentException if {@code op} is not a base operation
     */
    public Instruction {
      operands = List.copyOf(operands);
      if (!op.base()) {
        throw new IllegalArgumentException("A base decomposition cannot emit a runtime operation: " + op.name());
      }
    }
  }

  /**
   * A link-time Slot-native runtime symbol. Symbols resolve to function descriptors
   * during linking, never through a runtime string registry or Java reflection.
   * The current interpreter implements these ABI leaves in Java; values remain
   * typed Slots without conversion into Java value/collection wrappers. It reads inputs and writes up
   * to four outputs at the listed registers; result/input aliases require input
   * staging by the call lowerer before a result is written.
   *
   * @param symbol link-time runtime symbol
   * @param inputs operands read by the call
   * @param outputs operands receiving results, limited by the runtime ABI
   */
  public record LibraryCall(String symbol, List<Operand> inputs, List<Operand> outputs) implements Step {
    /**
     * Validates and copies a link-time library call.
     *
     * @param symbol link-time runtime symbol
     * @param inputs input operands
     * @param outputs output operands, at most four
     * @throws IllegalArgumentException if more than four outputs are requested
     */
    public LibraryCall {
      inputs = List.copyOf(inputs);
      outputs = List.copyOf(outputs);
      if (outputs.size() > 4) {
        throw new IllegalArgumentException("ELX supports at most four library results: " + symbol);
      }
    }
  }
}
