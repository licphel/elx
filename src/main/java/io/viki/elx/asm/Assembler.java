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
import io.viki.elx.lib.LE_Bits;
import io.viki.elx.lib.LE_String;
import io.viki.elx.lib.LibraryEntry;
import io.viki.elx.lib.Symbol;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Thread-confined builder for one executable ELX prototype.
 *
 * <p>The assembler records primitive instruction words, constant-pool entries, source locations,
 * nested functions, and symbolic branch fixups. Labels are local to the builder and are resolved
 * when {@link #build(int, int)} creates the immutable {@link Prototype}; callers must complete a
 * builder before sharing the resulting prototype with another thread.
 *
 * @see Instr
 * @see Prototype
 */
public final class Assembler {
  final ByteArrayOutputStream strings = new ByteArrayOutputStream();
  final List<CallSite> callSites = new ArrayList<>();
  int[] code = new int[32];
  @Nullable Parameters parameters;
  int words;
  int[] lines = new int[32];
  String sourceName = "<script>", functionName = "<script>";
  byte[] types = new byte[16], flags = new byte[16];
  long[] constants = new long[16];
  int constantCount;
  boolean[] linked = new boolean[16];
  @Nullable Symbol[] symbols = new Symbol[16];
  Prototype[] functions = new Prototype[8];
  int functionCount;
  int captureCount;
  private int line = 1;
  private int[] labels = new int[16], fixPc = new int[16], fixLabel = new int[16];
  private int labelCount, fixCount;

  private static void format(Op op, Format expected) {
    if (op.format() != expected) {
      throw new IllegalArgumentException(op + " requires " + op.format() + ", not " + expected);
    }
  }

  private static int loopPosition(int pc, int entryJump, int stepStart, int bodyStart, int end) {
    if (pc < entryJump) {
      return pc;
    }
    if (pc == end) {
      return entryJump + end - bodyStart;
    }
    if (pc > end) {
      return pc - 1;
    }
    if (pc >= bodyStart) {
      return entryJump + pc - bodyStart;
    }
    if (pc >= stepStart) {
      return entryJump + end - bodyStart + pc - stepStart;
    }
    return entryJump;
  }

  /**
   * Sets the parameter and variadic metadata for the function being assembled.
   *
   * @param parameters parameter layout to attach to the resulting prototype
   */
  public void parameters(Parameters parameters) {
    this.parameters = parameters;
  }

  /**
   * Adds a call-site argument layout to the prototype metadata.
   *
   * @param site argument layout used by a call instruction
   * @return zero-based call-site index
   */
  public int callSite(CallSite site) {
    int index = callSites.size();
    callSites.add(site);
    return index;
  }

  /**
   * Returns the source name currently attached to emitted instructions.
   *
   * @return source name, or the default script name before {@link #source(String, String)}
   */
  public String sourceName() {
    return sourceName;
  }

  /**
   * Sets source and function names used by diagnostics and stack traces.
   *
   * @param source   source file or resource name
   * @param function function name within the source
   */
  public void source(String source, String function) {
    sourceName = source;
    functionName = function;
  }

  /**
   * Selects the source line attached to subsequently emitted instruction words.
   *
   * @param line one-based source line
   */
  public void line(int line) {
    this.line = line;
  }

  /**
   * Reserves consecutive closure value cells; these are not local registers.
   *
   * @param count number of capture cells to reserve
   * @return first capture-cell index
   * @throws IllegalArgumentException if the capture window would exceed the VM limit
   */
  public int capture(int count) {
    int first = captureCount;
    captureCount = Math.addExact(captureCount, count);
    if (captureCount > 255) {
      throw new IllegalArgumentException("Closure capture window exceeds 255");
    }
    return first;
  }

  /**
   * Returns the current instruction-word position.
   *
   * @return number of words emitted so far
   */
  public int position() {
    return words;
  }

  /**
   * Reorders a parsed for-loop so the step executes after the body and repairs labels and fixups.
   *
   * @param entryJump position of the loop's entry jump
   * @param stepStart first word of the loop step
   * @param bodyStart first word of the loop body
   */
  public void finishLoop(int entryJump, int stepStart, int bodyStart) {
    int end = words;
    int[] step = Arrays.copyOfRange(code, stepStart, bodyStart);
    int[] stepLines = Arrays.copyOfRange(lines, stepStart, bodyStart);
    int bodyWords = end - bodyStart;
    System.arraycopy(code, bodyStart, code, entryJump, bodyWords);
    System.arraycopy(step, 0, code, entryJump + bodyWords, step.length);
    System.arraycopy(lines, bodyStart, lines, entryJump, bodyWords);
    System.arraycopy(stepLines, 0, lines, entryJump + bodyWords, stepLines.length);
    words--;
    for (int i = 0; i < labelCount; i++) {
      if (labels[i] >= 0) {
        labels[i] = loopPosition(labels[i], entryJump, stepStart, bodyStart, end);
      }
    }
    int count = 0;
    for (int i = 0; i < fixCount; i++) {
      if (fixPc[i] == entryJump) {
        continue;
      }
      fixPc[count] = loopPosition(fixPc[i], entryJump, stepStart, bodyStart, end);
      fixLabel[count++] = fixLabel[i];
    }
    fixCount = count;
  }

  /**
   * Allocates an unresolved branch label.
   *
   * @return label token to pass to {@link #bind(int)} or a branch method
   */
  public int label() {
    if (labelCount == labels.length) {
      labels = Arrays.copyOf(labels, labelCount * 2);
    }
    labels[labelCount] = -1;
    return labelCount++;
  }

  /**
   * Binds a label to the current instruction position.
   *
   * @param label unresolved label token
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if the label is unknown or already bound
   */
  public Assembler bind(int label) {
    if (label < 0 || label >= labelCount || labels[label] >= 0) {
      throw new IllegalArgumentException("Unknown or rebound label: " + label);
    }
    labels[label] = words;
    return this;
  }

  /**
   * Adds a numeric or null constant with no flags.
   *
   * @param type constant type; must be numeric or {@link Type#NULL}
   * @param bits raw payload bits
   * @return constant-pool index
   * @throws IllegalArgumentException if {@code type} is not a supported scalar constant type
   */
  public int constant(Type type, long bits) {
    return constant(type, (byte) 0, bits);
  }

  /**
   * Adds a numeric or null constant with assembler-defined flags.
   *
   * @param type  constant type; must be numeric or {@link Type#NULL}
   * @param flags constant metadata flags
   * @param bits  raw payload bits
   * @return constant-pool index
   * @throws IllegalArgumentException if {@code type} is not a supported scalar constant type
   */
  public int constant(Type type, byte flags, long bits) {
    if (!type.numeric() && type != Type.NULL && type != Type.VOID) {
      throw new IllegalArgumentException("Use string/function constants or parameters for " + type);
    }
    return addConstant(type, flags, bits);
  }

  /**
   * Adds a process-local reference resolved by a compile-time library.
   *
   * @param type    reference type or scalar type for a plain constant
   * @param address process-local address or scalar payload
   * @return constant-pool index
   * @throws IllegalArgumentException if the scalar type is unsupported
   */
  public int link(Type type, long address) {
    if (!type.reference()) {
      return constant(type, address);
    }
    int index = addConstant(type, (byte) 0, address);
    linked[index] = true;
    return index;
  }

  /**
   * Adds or reuses a symbolic library entry in the constant pool.
   *
   * @param symbol library symbol to encode
   * @return constant-pool index
   */
  public int symbol(Symbol symbol) {
    for (int i = 0; i < constantCount; i++) {
      if (symbol.equals(symbols[i])) {
        return i;
      }
    }
    LibraryEntry entry = symbol.definition();
    Type type = entry instanceof LE_Bits value ? value.type() : entry instanceof LE_String ? Type.STRING : Type.FUNCTION;
    int index = addConstant(type, (byte) 0, 0);
    symbols[index] = symbol;
    return index;
  }

  /**
   * Adds a UTF-8 string to the prototype string table.
   *
   * @param value string literal to encode
   * @return constant-pool index
   */
  public int string(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    int offset = strings.size();
    strings.writeBytes(bytes);
    return addConstant(Type.STRING, (byte) 0, (long) offset << 32 | Integer.toUnsignedLong(bytes.length));
  }

  /**
   * Adds a nested function prototype to the function pool.
   *
   * @param prototype nested function prototype
   * @return function constant-pool index
   */
  public int function(Prototype prototype) {
    return addConstant(Type.FUNCTION, (byte) 0, functionIndex(prototype));
  }

  /**
   * Adds or reuses a child prototype and returns its transient child-pool index.
   *
   * @param prototype child function image
   * @return zero-based child-function index
   */
  public int functionIndex(Prototype prototype) {
    for (int i = 0; i < functionCount; i++) {
      if (functions[i] == prototype) {
        return i;
      }
    }
    if (functionCount == functions.length) {
      functions = Arrays.copyOf(functions, functionCount * 2);
    }
    int index = functionCount++;
    functions[index] = prototype;
    return index;
  }

  private int addConstant(Type type, byte flag, long bits) {
    if (constantCount == Instr.MAX_CONSTANTS) {
      throw new IllegalArgumentException("Constant pool exceeds u16");
    }
    if (constantCount == constants.length) {
      int size = constantCount * 2;
      symbols = Arrays.copyOf(symbols, size);
      linked = Arrays.copyOf(linked, size);
      constants = Arrays.copyOf(constants, size);
      types = Arrays.copyOf(types, size);
      flags = Arrays.copyOf(flags, size);
    }
    int index = constantCount++;
    types[index] = type.tag();
    flags[index] = flag;
    constants[index] = bits;
    return index;
  }

  void word(int word) {
    if (words == code.length) {
      code = Arrays.copyOf(code, words * 2);
      lines = Arrays.copyOf(lines, words * 2);
    }
    lines[words] = line;
    code[words++] = word;
  }

  /**
   * Emits an instruction whose operands are encoded in the register format.
   *
   * @param op register-format opcode
   * @param a  first register operand
   * @param b  second register operand
   * @param c  third register operand
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if {@code op} does not use register format
   */
  public Assembler r(Op op, int a, int b, int c) {
    format(op, Format.R);
    word(Instr.header(op, a, b, c));
    return this;
  }

  /**
   * Emits an extended register instruction with a trailing control word.
   *
   * @param op      extended-register opcode
   * @param a       first register operand
   * @param b       second register operand
   * @param c       third register operand
   * @param control operation-specific control word
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if {@code op} does not use extended-register format
   */
  public Assembler rx(Op op, int a, int b, int c, int control) {
    format(op, Format.RX);
    word(Instr.header(op, a, b, c));
    word(control);
    return this;
  }

  /**
   * Emits an immediate instruction without an explicit control word.
   *
   * @param op        immediate or signed-immediate opcode
   * @param a         first register operand
   * @param b         second register operand
   * @param immediate immediate payload
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if {@code op} does not use immediate format
   */
  public Assembler immediate(Op op, int a, int b, int immediate) {
    return immediate(op, a, b, 0, immediate);
  }

  /**
   * Emits an immediate instruction with an operation-specific control word.
   *
   * @param op        immediate or signed-immediate opcode
   * @param a         first register operand
   * @param b         second register operand
   * @param control   operation-specific control word
   * @param immediate immediate payload
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if {@code op} does not use immediate format
   */
  public Assembler immediate(Op op, int a, int b, int control, int immediate) {
    if (op.format() != Format.I) {
      throw new IllegalArgumentException("Not I format: " + op);
    }
    word(Instr.header(op, a, b, control));
    word(immediate);
    return this;
  }

  /**
   * Emits a constant-load instruction.
   *
   * @param destination destination register
   * @param index       constant-pool index
   * @return this assembler for fluent emission
   */
  public Assembler constant(int destination, int index) {
    word(Instr.upper(Op.LOADK, destination, index));
    return this;
  }

  /**
   * Emits an upper-format instruction with a constant or table index.
   *
   * @param op          upper-format opcode
   * @param destination destination register
   * @param index       encoded index
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if {@code op} does not use upper format
   */
  public Assembler upper(Op op, int destination, int index) {
    format(op, Format.U);
    word(Instr.upper(op, destination, index));
    return this;
  }

  /**
   * Emits a conditional branch whose displacement is fixed when the label is bound.
   *
   * @param op        branch-format opcode
   * @param condition condition register
   * @param label     branch target label
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if {@code op} is not branch format or the label is unknown
   */
  public Assembler branch(Op op, int condition, int label) {
    format(op, Format.B);
    fix(label);
    word(Instr.branch(op, condition, 0));
    return this;
  }

  /**
   * Emits an unconditional jump to a label.
   *
   * @param label jump target label
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if the label is unknown
   */
  public Assembler jump(int label) {
    fix(label);
    word(Instr.jump(0));
    return this;
  }

  /**
   * Emits a hooked comparison branch using a scratch register and two operands.
   *
   * @param scratch register used for a hooked comparison result
   * @param left    left operand register
   * @param right   right operand register
   * @param control comparison relation and branch polarity
   * @param label   branch target label
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if the label is unknown
   */
  public Assembler compareBranch(int scratch, int left, int right, int control, int label) {
    return compareBranch(Instr.header(Op.BR_COMPARE, scratch, left, right), control, label);
  }

  /**
   * Emits a comparison branch from a prebuilt branch header.
   *
   * @param header  branch-format instruction header
   * @param control comparison relation and type control
   * @param label   branch target label
   * @return this assembler for fluent emission
   * @throws IllegalArgumentException if the header is not a comparison branch or the label is unknown
   */
  public Assembler compareBranch(int header, int control, int label) {
    if (Instr.op(header).format() != Format.BRX) {
      throw new IllegalArgumentException("Expected comparison branch header");
    }
    fix(label);
    word(header);
    word(control);
    word(0);
    return this;
  }

  private void fix(int label) {
    if (label < 0 || label >= labelCount) {
      throw new IllegalArgumentException("Unknown label: " + label);
    }
    if (fixCount == fixPc.length) {
      fixPc = Arrays.copyOf(fixPc, fixCount * 2);
      fixLabel = Arrays.copyOf(fixLabel, fixCount * 2);
    }
    fixPc[fixCount] = words;
    fixLabel[fixCount++] = label;
  }

  int[] linkedCode() {
    int[] result = Arrays.copyOf(code, words);
    for (int i = 0; i < fixCount; i++) {
      int pc = fixPc[i], target = labels[fixLabel[i]];
      if (target < 0) {
        throw new IllegalStateException("Unbound label: " + fixLabel[i]);
      }
      int displacement = Math.multiplyExact(target - pc, Instr.WORD_BYTES);
      Op op = Instr.op(result[pc]);
      op.format().patch(result, pc, displacement);
    }
    return result;
  }

  /**
   * Finalizes labels, metadata, and constant tables into an executable prototype.
   *
   * @param registers  number of VM registers allocated to the function
   * @param parameters number of positional parameters
   * @return immutable executable prototype
   * @throws IllegalStateException    if a branch label remains unbound
   * @throws IllegalArgumentException if register or parameter limits are invalid
   */
  public Prototype build(int registers, int parameters) {
    return new Prototype(this, registers, parameters);
  }
}
