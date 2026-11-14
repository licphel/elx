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

import io.viki.elx.Type;
import io.viki.elx.lib.Symbol;
import io.viki.elx.memory.Slot;
import io.viki.elx.vm.RuntimeLibrary;

import java.util.*;

import static io.viki.elx.asm.DecomposeRule.Operand.*;

/**
 * O0 expansion and relocation. Output uses only machine primitives plus explicit execution-environment ABI calls.
 */
public final class Decomposer {
  private Decomposer() {
  }

  /**
   * Lowering decompose the instructions for a prototype.
   *
   * @param image the prototype to decompose
   * @return the decomposed prototype
   */
  public static Prototype lower(Prototype image) {
    if (image.baseOnly()) {
      return image;
    }
    Prototype[] children = new Prototype[image.functionCount()];
    for (int i = 0; i < children.length; i++) {
      children[i] = lower(image.function(i));
    }
    Assembler out = image.metadata(children);
    out.baseOnly = true;
    return new Emitter(image, out).emit();
  }

  private static final class Emitter {
    final Prototype image;
    final Assembler out;
    final int[] labels;
    final Map<String, Integer> symbols = new HashMap<>();
    final EnumMap<DecomposeRule.Operand, Integer> operands = new EnumMap<>(DecomposeRule.Operand.class);
    int pc, word, next, highWater;

    Emitter(Prototype image, Assembler out) {
      this.image = image;
      this.out = out;
      highWater = image.registerCount();
      labels = new int[image.wordCount()];
      Arrays.fill(labels, -1);
      for (int p = 0; p < image.wordCount(); p += Instr.op(image.word(p)).format().words()) {
        labels[p] = out.label();
      }
    }

    Prototype emit() {
      for (pc = 0; pc < image.wordCount(); pc += Instr.op(word).format().words()) {
        word = image.word(pc);
        Op op = Instr.op(word);
        out.bind(labels[pc]);
        out.line(image.line(pc));
        next = image.registerCount();
        operands.clear();
        if (op.base()) {
          original(op);
          continue;
        }
        List<DecomposeRule> rules = DecomposeRules.get(op);
        if (rules.isEmpty()) {
          throw new IllegalArgumentException("No base decomposition for " + op + " at " + image.sourceName() + ":" + image.line(pc));
        }
        for (DecomposeRule.Step step : rules.get(0).steps()) {
          if (step instanceof DecomposeRule.Instruction instruction) {
            instruction(instruction);
          } else {
            library((DecomposeRule.LibraryCall) step);
          }
        }
      }
      return out.build(highWater, image.parameterCount());
    }

    int reserve(int count) {
      int base = next;
      next += count;
      if (next > Instr.MAX_REGISTERS) {
        throw new IllegalArgumentException("O0 expansion exceeds 256 registers at " + image.sourceName() + ":" + image.line(pc));
      }
      highWater = Math.max(highWater, next);
      return base;
    }

    int extension() {
      return image.word(pc + 1);
    }

    int target() {
      return pc + Instr.op(word).format().offset(image, pc) / Instr.WORD_BYTES;
    }

    Type kind() {
      return Instr.op(word) == Op.CX_IADD_CONST ? Type.fromTag((byte) Instr.c(word)) : Instr.type(extension());
    }

    int operation() {
      return switch (Instr.op(word)) {
        case CX_ADD, CX_ADD_CONST -> Op.IADD.ordinal();
        case CX_SUB, CX_SUB_CONST -> Op.ISUB.ordinal();
        case CX_MUL -> Op.IMUL.ordinal();
        case CX_DIV -> Op.IDIV.ordinal();
        case CX_REM -> Op.IREM.ordinal();
        case CX_AND -> Op.BAND.ordinal();
        case CX_OR -> Op.BOR.ordinal();
        case CX_XOR -> Op.BXOR.ordinal();
        case CX_SHL -> Op.SHL.ordinal();
        case CX_SHR -> Op.SHR.ordinal();
        case CX_USHR -> Op.USHR.ordinal();
        case CX_CMP, CX_BR_CMP -> Instr.operator(Op.ICMP, Instr.relation(extension()));
        case CX_OPERATOR -> extension();
        default -> Instr.op(word).ordinal();
      };
    }

    long literal(DecomposeRule.Operand operand) {
      return switch (operand) {
        case BRANCH_VALUE -> (extension() & Instr.BRANCH_FALSE) == 0 ? 1 : 0;
        case ZERO -> 0;
        case ONE -> 1;
        case EIGHT -> 8;
        case I64 -> Instr.control(Type.LONG);
        case IMM -> extension();
        case CONSTANT_INDEX -> Instr.op(word).format() == Format.U ? Instr.index(word) : extension();
        case WINDOW_BYTES -> (long) Instr.b(word) * Slot.SIZE;
        case COUNT_B -> Instr.b(word);
        case CAPTURE_OFFSET -> ((long) extension() + 1) * Slot.SIZE;
        case OPERATION -> operation();
        case INVALID_TAG -> Byte.toUnsignedInt(Type.INVALID.tag());
        case UBYTE_NE -> Instr.control(Type.UBYTE, Instr.Relation.NE);
        case KIND -> Instr.control(kind());
        case VALUE_COUNT -> (long) extension() * Slot.SIZE;
        case ALLOW_LIST -> Instr.op(word) == Op.CX_RETURN_DEREF ? Instr.b(word) : ((extension() & 256) != 0 ? 1 : 0);
        case ARG_COUNT -> Instr.argumentCount(extension());
        case RESULT_COUNT -> resultCount();
        default -> throw new IllegalArgumentException("Not a literal operand: " + operand);
      };
    }

    Type literalType(DecomposeRule.Operand operand) {
      if (operand == BRANCH_VALUE) {
        return Type.BOOL;
      }
      if (operand == INVALID_TAG) {
        return Type.UBYTE;
      }
      if (operand == EIGHT || operand == CAPTURE_OFFSET) {
        return Type.LONG;
      }
      if (operand == CONSTANT_INDEX) {
        return Type.INT;
      }
      if (operand == IMM && Instr.op(word) == Op.CX_IADD_CONST) {
        return kind();
      }
      return Type.INT;
    }

    int resultCount() {
      return switch (Instr.op(word)) {
        case CX_CALL_INDIRECT -> extension() >>> 8 & 255;
        case CX_CALL_PACKED -> extension() & 255;
        default -> Instr.resultCount(extension());
      };
    }

    int value(DecomposeRule.Operand operand) {
      if (operand == ARG_COUNT && Instr.op(word) == Op.CX_CALL_INDIRECT) {
        return extension() & 255;
      }
      switch (operand) {
        case A, DST, OUT0 -> {
          return Instr.a(word);
        }
        case B, X -> {
          return Instr.b(word);
        }
        case C, Y -> {
          return Instr.c(word);
        }
        case OUT1 -> {
          return Instr.a(word) + 1;
        }
        case OUT2 -> {
          return Instr.a(word) + 2;
        }
        case OUT3 -> {
          return Instr.a(word) + 3;
        }
        case COUNT_REGISTER -> {
          return extension();
        }
        default -> {
        }
      }
      Integer known = operands.get(operand);
      if (known != null) {
        return known;
      }
      int reg = reserve(1);
      operands.put(operand, reg);
      switch (operand) {
        case TMP0, TMP1 -> {
        }
        case CONSTANT -> out.constant(reg, Instr.op(word).format() == Format.U ? Instr.index(word) : extension());
        case ADDRESS_A -> address(reg, Instr.a(word));
        case ADDRESS_B -> address(reg, Instr.b(word));
        case ADDRESS_C -> address(reg, Instr.c(word));
        case ARG_BASE -> address(reg, Instr.op(word) == Op.CX_CALL ? Instr.c(word) : Instr.argumentBase(extension()));
        case FRAME -> out.r(Op.FRAME_CONTEXT, reg, 0, 0);
        default -> out.constant(reg, out.constant(literalType(operand), literal(operand)));
      }
      return reg;
    }

    void address(int result, int register) {
      out.r(Op.FRAME_ADDR, result, 0, 0);
      int offset = reserve(1);
      out.constant(offset, out.constant(Type.LONG, (long) register * Slot.SIZE));
      out.r(Op.LEA, result, result, offset);
    }

    void branch(Op op, int condition, int label) {
      // Long jump trampoline keeps expansions independent of the source's 16-bit branch reach.
      int skip = out.label();
      out.branch(op == Op.BR_NONZERO ? Op.BR_ZERO : Op.BR_NONZERO, condition, skip);
      out.jump(label);
      out.bind(skip);
    }

    void original(Op op) {
      if (op.format() == Format.J) {
        out.jump(labels[target()]);
        return;
      }
      if (op.format() == Format.B) {
        branch(op, Instr.a(word), labels[target()]);
        return;
      }
      if (op.format().branch()) {
        throw new IllegalArgumentException("Unexpected non-base branch: " + op);
      }
      for (int i = 0; i < op.format().words(); i++) {
        out.word(image.word(pc + i));
      }
    }

    void instruction(DecomposeRule.Instruction step) {
      Op op = step.op();
      List<DecomposeRule.Operand> args = step.operands();
      switch (op) {
        case LC -> {
          var literal = args.get(1);
          out.constant(value(args.get(0)), out.constant(literalType(literal), literal(literal)));
        }
        case BR_NONZERO, BR_ZERO -> branch(op, value(args.get(0)), labels[target()]);
        case IADD, IMUL, ICMP ->
            out.rx(op, value(args.get(0)), value(args.get(1)), value(args.get(2)), (int) literal(args.get(3)));
        case LOAD8, LOAD64 -> out.immediate(op, value(args.get(0)), value(args.get(1)), (int) literal(args.get(2)));
        default ->
            out.r(op, value(args.get(0)), args.size() > 1 ? value(args.get(1)) : 0, args.size() > 2 ? value(args.get(2)) : 0);
      }
    }

    void library(DecomposeRule.LibraryCall step) {
      // Materialize each input before staging, since address/context operands emit code too.
      int[] inputs = new int[step.inputs().size()];
      for (int i = 0; i < inputs.length; i++) {
        inputs[i] = value(step.inputs().get(i));
      }
      boolean variableResults = step.outputs().equals(List.of(RESULT_BASE));
      int[] outputs = new int[variableResults ? resultCount() : step.outputs().size()];
      for (int i = 0; i < outputs.length; i++) {
        outputs[i] = variableResults ? Instr.a(word) + i : value(step.outputs().get(i));
      }
      int args = reserve(inputs.length), results = reserve(Math.max(1, outputs.length)), function = reserve(1);
      for (int i = 0; i < inputs.length; i++) {
        out.r(Op.MOVE, args + i, inputs[i], 0);
      }
      int constant = symbols.computeIfAbsent(step.symbol(), name -> {
        Symbol symbol = RuntimeLibrary.definitions().resolve(name);
        if (symbol == null) {
          throw new IllegalArgumentException("Missing O0 runtime symbol: " + name);
        }
        return out.symbol(symbol);
      });
      out.constant(function, constant);
      out.rx(Op.ECALL, function, args, results, Instr.callWindow(inputs.length, outputs.length));
      for (int i = 0; i < outputs.length; i++) {
        out.r(Op.MOVE, outputs[i], results + i, 0);
      }
      // ABI scratch windows are reusable between steps, but bound TMP/materialized operands survive.
      next = args;
    }
  }
}
