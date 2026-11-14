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
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Immutable function image, safe to share between VMs/threads. Code is primitive
 * words. Constants use tags/flags/bits plus a UTF-8 blob, not Java value wrappers.
 * STRING bits encode blob offset/length and FUNCTION bits encode a child index;
 * the VM loader resolves these into per-VM references before CONST executes.
 */
public final class Prototype {
  private final int registers, parameters, captures;
  private final boolean baseOnly;
  private final @Nullable Parameters signature;
  private final CallSite[] callSites;
  private final int[] lines;
  private final String sourceName, functionName;
  private final int[] code;
  private final byte[] types, flags, strings;
  private final long[] constants;
  private final Prototype[] functions;
  private final boolean[] boundaries, linked;
  private final @Nullable Symbol[] symbols;

  Prototype(Assembler source, int registers, int parameters) {
    if (registers < 1 || registers > Instr.MAX_REGISTERS || parameters < 0 || parameters > registers) {
      throw new IllegalArgumentException("Invalid register/parameter window: " + registers + "/" + parameters);
    }
    baseOnly = source.baseOnly;
    signature = source.parameters;
    if (signature != null && signature.names().size() != parameters) {
      throw new IllegalArgumentException("Signature parameter count does not match register window");
    }
    callSites = source.callSites.toArray(CallSite[]::new);
    this.registers = registers;
    this.parameters = parameters;
    this.captures = source.captureCount;
    code = source.linkedCode();
    lines = Arrays.copyOf(source.lines, code.length);
    sourceName = source.sourceName;
    functionName = source.functionName;
    types = Arrays.copyOf(source.types, source.constantCount);
    linked = Arrays.copyOf(source.linked, source.constantCount);
    symbols = Arrays.copyOf(source.symbols, source.constantCount);
    flags = Arrays.copyOf(source.flags, source.constantCount);
    constants = Arrays.copyOf(source.constants, source.constantCount);
    strings = source.strings.toByteArray();
    functions = Arrays.copyOf(source.functions, source.functionCount);
    boundaries = new boolean[code.length];
    validate();
  }

  private static IllegalArgumentException invalid(int pc, String reason) {
    return new IllegalArgumentException("Invalid ELX function at word " + pc + ": " + reason);
  }

  /**
     * Returns whether this image contains only the primitive instruction set.
     * O0 images are accepted by the decomposing interpreter and do not contain
     * integrated {@code CX_*} operations.
     *
     * @return {@code true} when the image is base-only
   */
  public boolean baseOnly() {
    return baseOnly;
  }

  /**
     * Returns the source-level signature retained for this function, when one
     * was emitted by the compiler.
     *
     * @return the parameter signature, or {@code null} for an anonymous/raw
     *     function image
   */
  public @Nullable Parameters signature() {
    return signature;
  }

  /**
     * Returns metadata for the call site at {@code index}.
     *
     * @param index zero-based call-site index
     * @return the immutable call-site descriptor
     * @throws IndexOutOfBoundsException if {@code index} is outside the call-site table
   */
  public CallSite callSite(int index) {
    return callSites[index];
  }

  /**
     * Maps a program-counter word offset to its source line. Out-of-range
     * offsets are clamped so diagnostics can still describe a failed boundary.
     *
     * @param pc word offset in this image
     * @return one-based source line, or the stored line for the nearest boundary
   */
  public int line(int pc) {
    return lines[Math.max(0, Math.min(pc, lines.length - 1))];
  }

  /**
     * Returns the source name associated with this image.
     *
     * @return source name used in diagnostics
   */
  public String sourceName() {
    return sourceName;
  }

  /**
     * Returns the compiler-visible name of this function.
     *
     * @return function name, including an implementation-generated name when applicable
   */
  public String functionName() {
    return functionName;
  }

  Assembler metadata(Prototype[] children) {
    Assembler result = new Assembler();
    result.source(sourceName, functionName);
    result.captureCount = captures;
    result.parameters = signature;
    result.types = Arrays.copyOf(types, Math.max(16, types.length));
    result.flags = Arrays.copyOf(flags, Math.max(16, flags.length));
    result.constants = Arrays.copyOf(constants, Math.max(16, constants.length));
    result.linked = Arrays.copyOf(linked, Math.max(16, linked.length));
    result.symbols = Arrays.copyOf(symbols, Math.max(16, symbols.length));
    result.constantCount = constants.length;
    result.strings.writeBytes(strings);
    result.functions = Arrays.copyOf(children, Math.max(8, children.length));
    result.functionCount = children.length;
    result.callSites.addAll(Arrays.asList(callSites));
    return result;
  }

  /**
     * Returns the number of virtual registers in the function window.
     *
     * @return register-window size
   */
  public int registerCount() {
    return registers;
  }

  /**
     * Returns the number of parameter registers populated on entry.
     *
     * @return parameter count
   */
  public int parameterCount() {
    return parameters;
  }

  /**
     * Returns the number of closure captures expected by this function.
     *
     * @return capture count
   */
  public int captureCount() {
    return captures;
  }

  /**
     * Returns the encoded instruction-image length in 32-bit words.
     *
     * @return code length
   */
  public int wordCount() {
    return code.length;
  }

  /**
     * Reads one encoded instruction word without decoding it.
     *
     * @param pc word offset
     * @return raw instruction or operand word
     * @throws IndexOutOfBoundsException if {@code pc} is outside the image
   */
  public int word(int pc) {
    return code[pc];
  }

  /**
     * Tests whether a word offset is the start of an encoded instruction.
     *
     * @param pc word offset
     * @return {@code true} when {@code pc} is an instruction boundary
   */
  public boolean instructionStart(int pc) {
    return pc >= 0 && pc < boundaries.length && boundaries[pc];
  }

  /**
     * Returns the number of entries in the constant pool.
     *
     * @return constant count
   */
  public int constantCount() {
    return constants.length;
  }

  /**
     * Returns the declared runtime type of a constant-pool entry.
     *
     * @param index constant-pool index
     * @return encoded constant type
     * @throws IndexOutOfBoundsException if {@code index} is outside the pool
   */
  public Type constantType(int index) {
    return Type.fromTag(types[index]);
  }

  /**
     * Returns the symbolic reference attached to a constant, if it requires
     * link-time resolution.
     *
     * @param index constant-pool index
     * @return unresolved symbol, or {@code null} for a literal/non-symbolic entry
   */
  public @Nullable Symbol constantSymbol(int index) {
    return symbols[index];
  }

  /**
     * Tests whether a constant has already been resolved for a VM image.
     *
     * @param index constant-pool index
     * @return {@code true} when link-time resolution has completed
   */
  public boolean constantLinked(int index) {
    return linked[index];
  }

  /**
     * Returns compiler/linker flags stored alongside a constant.
     *
     * @param index constant-pool index
     * @return raw constant flags
   */
  public byte constantFlags(int index) {
    return flags[index];
  }

  /**
     * Returns the raw 64-bit payload of a numeric or encoded constant.
     *
     * @param index constant-pool index
     * @return raw constant bits
   */
  public long constantBits(int index) {
    return constants[index];
  }

  /**
     * Returns the byte offset of a UTF-8 string constant in the shared blob.
     *
     * @param index constant-pool index
     * @return UTF-8 byte offset
   */
  public int stringOffset(int index) {
    return (int) (constants[index] >>> 32);
  }

  /**
     * Returns the UTF-8 byte length of a string constant.
     *
     * @param index constant-pool index
     * @return byte length
   */
  public int stringLength(int index) {
    return (int) constants[index];
  }

  /**
     * Reads a byte from the image-wide UTF-8 string blob.
     *
     * @param offset byte offset in the blob
     * @return raw UTF-8 byte
   */
  public byte stringByte(int offset) {
    return strings[offset];
  }

  /**
   * Decodes a string constant for tooling or Java host integration. The VM can
   * use {@link #stringOffset(int)} and {@link #stringLength(int)} directly and
   * avoid allocating a Java {@link String}.
   *
   * @param index constant-pool index
   * @return decoded UTF-8 text
   */
  public String string(int index) {
    return new String(strings, stringOffset(index), stringLength(index), StandardCharsets.UTF_8);
  }

  /**
     * Returns the number of nested function prototypes embedded in this image.
     *
     * @return nested-function count
   */
  public int functionCount() {
    return functions.length;
  }

  /**
     * Returns a nested function prototype by index.
     *
     * @param index nested-function index
     * @return child prototype
     * @throws IndexOutOfBoundsException if {@code index} is outside the child table
   */
  public Prototype function(int index) {
    return functions[index];
  }

  private void validate() {
    if (code.length == 0) {
      throw new IllegalArgumentException("Empty function body");
    }
    for (int pc = 0; pc < code.length; ) {
      Op op = Instr.op(code[pc]);
      if (baseOnly && !op.base()) {
        throw invalid(pc, "runtime opcode in O0 image: " + op);
      }
      int length = op.format().words();
      if (pc + length > code.length) {
        throw invalid(pc, "truncated " + op);
      }
      boundaries[pc] = true;
      int mask = op.registerMask(), word = code[pc];
      if ((mask & 1) != 0) {
        register(Instr.a(word), pc);
      }
      if ((mask & 2) != 0) {
        register(Instr.b(word), pc);
      }
      if ((mask & 4) != 0) {
        register(Instr.c(word), pc);
      }
      op.validate(this, pc);
      pc += length;
    }
    for (int pc = 0; pc < code.length; pc += Instr.op(code[pc]).format().words()) {
      Format format = Instr.op(code[pc]).format();
      if (!format.branch()) {
        continue;
      }
      int offset = format.offset(this, pc);
      long target = (long) pc + offset / Instr.WORD_BYTES;
      if ((offset & 3) != 0 || target < 0 || target >= code.length || !boundaries[(int) target]) {
        throw invalid(pc, "branch into non-instruction location");
      }
    }
  }

  void constantIndex(int index, int pc) {
    if (index >= constants.length) {
      throw invalid(pc, "constant index outside pool");
    }
  }

  void register(int value, int pc) {
    if (value < 0 || value >= registers) {
      throw invalid(pc, "register r" + value + " outside window");
    }
  }

  void window(int base, int count, int maximum, int pc) {
    if (count < 0 || count > maximum || base + count > registers) {
      throw invalid(pc, "invalid value window " + base + "+" + count);
    }
  }
}
