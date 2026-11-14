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

/**
 * ELX instruction set metadata.
 *
 * <p>Base operations are directly executable by the machine interpreter. Integrated {@code CX_*}
 * operations express language semantics such as table access, closures, and external calls; the
 * selected {@link Optimization} level either decomposes them into base operations or keeps them
 * fused. Each constant also declares its encoding layout, register fields, and control-word
 * interpretation so assembly, validation, and disassembly share one contract.
 */
public enum Op {
  // ==================== Base ISA: frame and execution-environment primitives ====================

  /** Loads the current VM frame base as a pointer value for low-level address calculations. */
  FRAME_ADDR(Format.R, Format.A),
  /** Loads the current function/closure handle into a VM value slot. */
  FRAME_CONTEXT(Format.R, Format.A),
  /**
   * Calls a linked execution-environment service directly with VM register windows.
   * A is the service handle, B is the input-window base, C is the result-window base;
   * the trailing control word stores argument count and result capacity.
   */
  ECALL(Format.RX, Format.ABC, Format.Control.CALL) {
    @Override
    void validate(Prototype p, int pc) {
      int word = p.word(pc), control = p.word(pc + 1);
      p.window(Instr.b(word), Instr.argumentCount(control), 255, pc);
      p.window(Instr.c(word), Instr.resultCount(control), 4, pc);
    }
  },
  /** Branches when the register payload is zero; does not interpret a language boolean. */
  BR_ZERO(Format.B, Format.A),
  /** Branches when the register payload is nonzero. */
  BR_NONZERO(Format.B, Format.A),

  // ==================== Base ISA: values and constants ====================

  /** NOP: performs no operation. */
  NOP(Format.R, Format.NO_REGISTERS),
  /** MOVE dst, src: copies an opaque register value. */
  MOVE(Format.R, Format.AB),
  /** LC dst, constant: loads a constant from linked data. */
  LC(Format.U, Format.A) {
    @Override
    void validate(Prototype p, int pc) {
      p.constantIndex(Instr.index(p.word(pc)), pc);
    }
  },
  // ==================== Base ISA: primitive arithmetic ====================
  /** IADD dst, lhs, rhs, kind: integer addition, wrapping at the specified integer width. */
  IADD(Format.RX, Format.ABC),
  /** ISUB dst, lhs, rhs, kind: integer subtraction, wrapping at the specified integer width. */
  ISUB(Format.RX, Format.ABC),
  /** IMUL dst, lhs, rhs, kind: integer multiplication, retaining the low bits of the specified width. */
  IMUL(Format.RX, Format.ABC),
  /** IDIV dst, lhs, rhs, kind: signed integer division toward zero; zero divisor fails, MIN/-1 wraps. */
  IDIV(Format.RX, Format.ABC),
  /** UDIV dst, lhs, rhs, kind: unsigned division at the selected width; a zero divisor fails. */
  UDIV(Format.RX, Format.ABC),
  /** UREM dst, lhs, rhs, kind: unsigned remainder at the selected width; a zero divisor fails. */
  UREM(Format.RX, Format.ABC),
  /** IREM dst, lhs, rhs, kind: signed integer remainder; zero divisor fails and MIN%-1 is zero. */
  IREM(Format.RX, Format.ABC),
  /** INEG dst, src, kind: integer negation with width-preserving wraparound. */
  INEG(Format.RX, Format.AB),
  /** FADD dst, lhs, rhs, kind: IEEE floating-point addition at the specified precision. */
  FADD(Format.RX, Format.ABC),
  /** FSUB dst, lhs, rhs, kind: IEEE floating-point subtraction at the specified precision. */
  FSUB(Format.RX, Format.ABC),
  /** FMUL dst, lhs, rhs, kind: IEEE floating-point multiplication, without implicit fused operations. */
  FMUL(Format.RX, Format.ABC),
  /** FDIV dst, lhs, rhs, kind: IEEE floating-point division, including signed zero, infinity and NaN. */
  FDIV(Format.RX, Format.ABC),
  /** FREM dst, lhs, rhs, kind: floating-point remainder corresponding to truncation toward zero. */
  FREM(Format.RX, Format.ABC),
  /** FNEG dst, src, kind: flips the floating-point sign without converting through an integer. */
  FNEG(Format.RX, Format.AB),
  /** BAND dst, lhs, rhs, kind: eager bitwise AND; bool operands use their one-bit representation. */
  BAND(Format.RX, Format.ABC),
  /** BOR dst, lhs, rhs, kind: eager bitwise OR; bool operands use their one-bit representation. */
  BOR(Format.RX, Format.ABC),
  /** BXOR dst, lhs, rhs, kind: eager bitwise XOR; bool operands use their one-bit representation. */
  BXOR(Format.RX, Format.ABC),
  /** BNOT dst, src, kind: complements bits within the specified integer width. */
  BNOT(Format.RX, Format.AB),
  /** SHL dst, src, count, kind: left shift; shift count is masked to the language-defined width. */
  SHL(Format.RX, Format.ABC),
  /** SHR dst, src, count, kind: arithmetic right shift, propagating the sign bit. */
  SHR(Format.RX, Format.ABC),
  /** USHR dst, src, count, kind: logical right shift, inserting zero bits. */
  USHR(Format.RX, Format.ABC),
  /** ICMP dst, lhs, rhs, relation, kind: signed integer comparison producing bool, without float conversion. */
  ICMP(Format.RX, Format.ABC, Format.Control.COMPARE),
  /** UCMP dst, lhs, rhs, relation, kind: unsigned integer comparison without signed reinterpretation. */
  UCMP(Format.RX, Format.ABC, Format.Control.COMPARE),
  /** FCMP dst, lhs, rhs, relation, kind: IEEE comparison producing bool; NaN follows the chosen relation. */
  FCMP(Format.RX, Format.ABC, Format.Control.COMPARE),
  /** EQ64 dst, lhs, rhs: compares the raw sixty-four-bit payloads; never interprets language objects. */
  EQ64(Format.R, Format.ABC),

  // ==================== Base ISA: memory ====================

  /** LEA dst, base, byteOffset: adds a byte displacement to an address. */
  LEA(Format.R, Format.ABC),
  /** LOAD8 dst, base, immediateByteOffset: loads an unsigned eight-bit memory quantity. */
  LOAD8(Format.I, Format.AB),
  /** LOAD16 dst, base, immediateByteOffset: loads an unsigned sixteen-bit memory quantity. */
  LOAD16(Format.I, Format.AB),
  /** LOAD32 dst, base, immediateByteOffset: loads an unsigned thirty-two-bit memory quantity. */
  LOAD32(Format.I, Format.AB),
  /** LOAD64 dst, base, immediateByteOffset: loads an opaque sixty-four-bit memory quantity. */
  LOAD64(Format.I, Format.AB),
  /** STORE8 src, base, immediateByteOffset: writes the low eight payload bits. */
  STORE8(Format.S, Format.AB),
  /** STORE16 src, base, immediateByteOffset: writes the low sixteen payload bits. */
  STORE16(Format.S, Format.AB),
  /** STORE32 src, base, immediateByteOffset: writes the low thirty-two payload bits. */
  STORE32(Format.S, Format.AB),
  /**
   * STORE64 src, base, immediateByteOffset: writes sixty-four payload bits; layout rules determine reference
   * visibility.
   */
  STORE64(Format.S, Format.AB),
  /** BOUNDS index, length: fails unless {@code 0 <= index < length}; does not access memory. */
  BOUNDS(Format.R, Format.AB),
  /** TRAP reason: transfers an opaque reason code to the execution environment's trap handler. */
  TRAP(Format.I, Format.NO_REGISTERS),

  // ==================== Base ISA: control flow ====================

  /** JUMP label: transfers unconditionally to a basic-block destination. */
  JUMP(Format.J, Format.NO_REGISTERS),
  // ==================== Integrated language/runtime operations ====================
  /** Addresses a local register Slot; does not extend its frame/scope lifetime. */
  CX_LOCAL_PTR(Format.R, Format.AB),
  /** Creates a logical container-element pointer, stable across backing-store resize. */
  CX_INDEX_PTR(Format.R, Format.ABC),
  /** Returns the current value Slot of a global; the global environment owns the value's lifetime. */
  CX_GLOBAL_PTR(Format.R, Format.AB),
  /** Writes the value at a pointer. */
  CX_WRITE_PTR(Format.R, Format.AB),
  /** Addresses a captured value in the closure's persistent allocation. */
  CX_CAPTURE_PTR(Format.I, Format.A),
  /** CX_CONST_PTR dst, constantIndex: creates a GC-managed reference to a linked constant Slot. */
  CX_CONST_PTR(Format.U, Format.A) {
    @Override
    void validate(Prototype p, int pc) {
      p.constantIndex(Instr.index(p.word(pc)), pc);
    }
  },
  /** Loads the persistent VM environment table. */
  CX_ENV(Format.R, Format.A),
  /** Branches when a parameter was supplied, preserving explicit null. */
  CX_BR_PRESENT(Format.B, Format.A),
  /** Calls using immutable named/spread call-site metadata in the prototype. */
  CX_CALL_NAMED(Format.RX, Format.ABC) {
    @Override
    void validate(Prototype p, int pc) {
      CallSite site = p.callSite(p.word(pc + 1));
      p.window(Instr.c(p.word(pc)), site.names().size(), 255, pc);
      p.window(Instr.a(p.word(pc)), 4, 4, pc);
    }
  },
  /** Reads a persistent VM global using a constant-pool key; still observes subsequent global reassignment. */
  CX_GET_GLOBAL_CONST(Format.U, Format.A) {
    @Override
    void validate(Prototype p, int pc) {
      p.constantIndex(Instr.index(p.word(pc)), pc);
    }
  },
  /** Reads a persistent VM global by string key. */
  CX_GET_GLOBAL(Format.R, Format.AB),
  /** Writes a persistent VM global by string key. */
  CX_SET_GLOBAL(Format.R, Format.AB),

  // ==================== Integrated runtime calling convention ====================
  /** Calls with a PTR argument window; extension holds count register and result capacity. */
  CX_CALL_INDIRECT(Format.RX, Format.ABC, Format.Control.NONE) {
    @Override
    void validate(Prototype p, int pc) {
      int control = p.word(pc + 1);
      p.register(control & 255, pc);
      p.window(Instr.a(p.word(pc)), (control >>> 8) & 255, 4, pc);
    }
  },
  /** Returns from a PTR window and count register, at most four values. */
  CX_RETURN_INDIRECT(Format.R, Format.AB),

  /** CX_TYPE dst, src: reads the source value's type code as an integer. */
  CX_TYPE(Format.R, Format.AB),
  /** CX_BITS dst, src: reads the raw payload as an unsigned 64-bit bit pattern, without dereferencing it. */
  CX_BITS(Format.R, Format.AB),
  /** CX_LEN dst, src: container count, string Unicode code points, numeric byte width, or zero for opaque values. */
  CX_LEN(Format.R, Format.AB),
  /** CX_PACK dst, type, bits: builds a tagged value; trusted library/compiler code must preserve valid references. */
  CX_PACK(Format.R, Format.ABC),
  /** CX_CONVERT dst, src, kind: explicit numeric conversion; narrowing is never inserted implicitly. */
  CX_CONVERT(Format.I, Format.AB),
  /** CX_TO_STRING dst, src: formats one value through its str hook into an ELX string. */
  CX_TO_STRING(Format.R, Format.AB),
  /** CX_GUARD src, expectedType: fails with a type error unless the value has the required type. */
  CX_GUARD(Format.I, Format.A),

  /** CX_BOOL_NOT dst, src: boolean negation; numeric and reference operands are rejected. */
  CX_BOOL_NOT(Format.R, Format.AB),

  /** CX_ALLOC dst, size, layout: allocates an internal region using a GC layout descriptor; may reach a safepoint. */
  CX_ALLOC(Format.R, Format.ABC),

  /** CX_SAFEPOINT: publishes live roots and checks collection, cancellation and execution budget. */
  CX_SAFEPOINT(Format.R, Format.NO_REGISTERS),

  /** CX_BR_TRUE condition, label: branches if a strictly boolean condition is true. */
  CX_BR_TRUE(Format.B, Format.A),
  /** CX_BR_FALSE condition, label: branches if a strictly boolean condition is false. */
  CX_BR_FALSE(Format.B, Format.A),
  /** CX_CALL function, argBase, argCount, resultBase, resultCount: calls an internal function with up to four results. */
  CX_CALL(Format.RX, Format.ABC, Format.Control.CALL) {
    @Override
    void validate(Prototype p, int pc) {
      int word = p.word(pc), control = p.word(pc + 1);
      p.window(Instr.c(word), Instr.argumentCount(control), 255, pc);
      p.window(Instr.a(word), Instr.resultCount(control), 4, pc);
    }
  },
  /** CX_RETURN valueBase, valueCount: returns zero through four consecutive values and releases the frame window. */
  CX_RETURN(Format.R, Format.A) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.a(p.word(pc)), Instr.b(p.word(pc)), 4, pc);
    }

    @Override
    void append(StringBuilder out, Prototype p, int pc) {
      out.append('r').append(Instr.a(p.word(pc))).append(" count=").append(Instr.b(p.word(pc)));
    }
  },

  // ==================== Integrated execution-environment services ====================

  /**
   * CX_EX_CALL target, member, argBase, argCount, resultBase, resultCount: dispatches to an external provider;
   * publishes
   * VM state for callbacks.
   */
  CX_EX_CALL(Format.RX, Format.ABC, Format.Control.EX_CALL) {
    @Override
    void validate(Prototype p, int pc) {
      int word = p.word(pc), control = p.word(pc + 1);
      p.window(Instr.argumentBase(control), Instr.argumentCount(control), 255, pc);
      p.window(Instr.a(word), Instr.resultCount(control), 4, pc);
    }
  },
  /** CX_EX_GET dst, target, memberNamePtr: reads an external member into a complete ELX value cell. */
  CX_EX_GET(Format.R, Format.ABC),
  /** CX_EX_SET target, memberNamePtr, value: writes a complete ELX value into an external member. */
  CX_EX_SET(Format.R, Format.ABC),
  /** CX_EX_ADDRESS dst, target, memberNamePtr: obtains an opaque external field location, never a JVM memory address. */
  CX_EX_ADDRESS(Format.R, Format.ABC),
  /** CX_EX_RETAIN externalRef: requests an explicit external lifetime hold; does not inspect the referenced resource. */
  CX_EX_RETAIN(Format.R, Format.A),
  /** CX_EX_RELEASE externalRef: releases a matching explicit external hold; does not free an internal ELX value. */
  CX_EX_RELEASE(Format.R, Format.A),

  // ==================== Integrated ISA: compiler-selected fused operations ====================

  /** CX_ADD dst, lhs, rhs: same-kind numeric fast path, otherwise normal + overload dispatch. */
  CX_ADD(Format.R, Format.ABC),
  /** CX_ADD_CONST dst, lhs, constantIndex: dynamic addition with an immutable constant-pool rhs. */
  CX_ADD_CONST(Format.RX, Format.AB, Format.Control.CONSTANT) {
    @Override
    void validate(Prototype p, int pc) {
      p.constantIndex(p.word(pc + 1), pc);
    }
  },
  /** CX_SUB_CONST dst, lhs, constantIndex: dynamic subtraction with an immutable constant-pool rhs. */
  CX_SUB_CONST(Format.RX, Format.AB, Format.Control.CONSTANT) {
    @Override
    void validate(Prototype p, int pc) {
      p.constantIndex(p.word(pc + 1), pc);
    }
  },
  /** CX_SUB dst, lhs, rhs: same-kind numeric fast path, otherwise normal - overload dispatch. */
  CX_SUB(Format.R, Format.ABC),
  /** CX_MUL dst, lhs, rhs: same-kind numeric fast path, otherwise normal * overload dispatch. */
  CX_MUL(Format.R, Format.ABC),
  /** CX_DIV dst, lhs, rhs: same-kind numeric fast path, otherwise normal / overload dispatch. */
  CX_DIV(Format.R, Format.ABC),
  /** CX_REM dst, lhs, rhs: same-kind numeric fast path, otherwise normal % overload dispatch. */
  CX_REM(Format.R, Format.ABC),
  /** CX_AND dst, lhs, rhs: same-kind numeric fast path, otherwise normal {@code &} overload dispatch. */
  CX_AND(Format.R, Format.ABC),
  /** CX_OR dst, lhs, rhs: same-kind numeric fast path, otherwise normal | overload dispatch. */
  CX_OR(Format.R, Format.ABC),
  /** CX_XOR dst, lhs, rhs: same-kind numeric fast path, otherwise normal ^ overload dispatch. */
  CX_XOR(Format.R, Format.ABC),
  /** CX_SHL dst, lhs, rhs: same-kind numeric fast path, otherwise normal {@code <<} overload dispatch. */
  CX_SHL(Format.R, Format.ABC),
  /** CX_SHR dst, lhs, rhs: same-kind numeric fast path, otherwise normal >> overload dispatch. */
  CX_SHR(Format.R, Format.ABC),
  /** CX_USHR dst, lhs, rhs: same-kind numeric fast path, otherwise normal >>> overload dispatch. */
  CX_USHR(Format.R, Format.ABC),
  /** CX_CMP dst, lhs, rhs, relation: same-kind numeric comparison with normal mixed/overloaded fallback. */
  CX_CMP(Format.RX, Format.ABC, Format.Control.COMPARE),
  /** CX_DEREF dst, src, count: copies values from a list or reads Slots at a raw LONG address. */
  CX_DEREF(Format.RX, Format.AB, Format.Control.NONE) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.a(p.word(pc)), p.word(pc + 1), 255, pc);
    }
  },
  /** CX_RETURN_DEREF src: returns the dereferenced value sequence (at most four values). */
  CX_RETURN_DEREF(Format.R, Format.A),
  /** CX_APPEND_DEREF list, src: appends a dereferenced sequence to a dynamic argument buffer. */
  CX_APPEND_DEREF(Format.R, Format.AB),
  /** CX_CALL_PACKED dst, function, list: calls with dynamically expanded positional arguments. */
  CX_CALL_PACKED(Format.RX, Format.ABC, Format.Control.NONE) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.a(p.word(pc)), p.word(pc + 1) & 255, 4, pc);
    }
  },
  /** CX_INCR dst, src: adds one at the operand's numeric width; may invoke a table __inc__ hook. */
  CX_INCR(Format.R, Format.AB),
  /** CX_DECR dst, src: subtracts one at the operand's numeric width; may invoke a table __dec__ hook. */
  CX_DECR(Format.R, Format.AB),
  /** CX_POS dst, src: numeric unary plus or table __pos__. */
  CX_POS(Format.R, Format.AB),
  /** CX_CLOSURE dst, function, captureBase, count: copies captures into an internal closure, not Java objects. */
  CX_CLOSURE(Format.RX, Format.ABC, Format.Control.NONE) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.c(p.word(pc)), p.word(pc + 1), 255, pc);
    }
  },
  /** CX_GET_CAPTURE dst, count, index: reads consecutive closure cells; B is a value count, not a register. */
  CX_GET_CAPTURE(Format.I, Format.A) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.a(p.word(pc)), Instr.b(p.word(pc)), 255, pc);
    }
  },
  /** CX_SET_CAPTURE src, count, index: updates this closure's private captured bindings immediately. */
  CX_SET_CAPTURE(Format.I, Format.A) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.a(p.word(pc)), Instr.b(p.word(pc)), 255, pc);
    }
  },
  /** CX_SELF dst: loads the current internal function, for recursive noncapturing functions. */
  CX_SELF(Format.R, Format.A),
  /** CX_GET_INDEX_CONST dst, target, constantIndex: member lookup using a linked constant Slot, preserving getattr. */
  CX_GET_INDEX_CONST(Format.RX, Format.AB, Format.Control.CONSTANT) {
    @Override
    void validate(Prototype p, int pc) {
      p.constantIndex(p.word(pc + 1), pc);
    }
  },
  /**
   * CX_BR_CMP scratch, lhs, rhs, control, label: dynamic comparison and branch.
   * A is a clobberable bool scratch for overloaded comparisons; numeric paths need not write it.
   * Control preserves the original relation; BRANCH_FALSE negates the final bool, including NaN and hooks.
   */
  CX_BR_CMP(Format.BRX, Format.ABC, Format.Control.COMPARE),
  /** CX_GET_INDEX dst, target, key: list/table access, including table __get__. */
  CX_GET_INDEX(Format.R, Format.ABC),
  /** CX_SET_INDEX target, key, value: list/table assignment, including table __set__. */
  CX_SET_INDEX(Format.R, Format.ABC),
  /** CX_SET_META dst, table, meta: assigns a table/null metatable and returns the table. */
  CX_SET_META(Format.R, Format.ABC),
  /** CX_GET_META dst, table: reads the table's metatable, or null. */
  CX_GET_META(Format.R, Format.AB),
  /** CX_NEXT resultBase, container, cursor: normalizes list/table iteration to ordinal/key/value/nextCursor. */
  CX_NEXT(Format.R, Format.ABC) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.a(p.word(pc)), 4, 4, pc);
    }
  },
  /** CX_IADD_CONST dst, src, immediate, kind: integer addition with a constant, equivalent to CONST followed by IADD. */
  CX_IADD_CONST(Format.I, Format.AB),
  /**
   * CX_BR_ICMP lhs, rhs, relation, kind, label: compares integers and branches on true without materializing a
   * long-lived bool.
   */
  CX_BR_ICMP(Format.BRX, Format.AB, Format.Control.COMPARE),
  /**
   * CX_LOAD64_INDEXED dst, base, i64Index: loads raw eight-byte data at base + index * 8; this is not a tagged Slot
   * lookup.
   */
  CX_LOAD64_INDEXED(Format.R, Format.ABC),
  /** CX_COPY_MEMORY dst, src, count: overlap-safe byte-region copy; base form is the internal memmove library. */
  CX_COPY_MEMORY(Format.R, Format.ABC),
  /** CX_HASH dst, value: invokes the internal language hash algorithm, including key-normalization rules. */
  CX_HASH(Format.R, Format.AB),
  /**
   * CX_EQUAL dst, lhs, rhs: language equality, including internal value types and operator hooks, rather than reference
   * identity.
   */
  CX_EQUAL(Format.R, Format.ABC),
  /**
   * CX_OPERATOR dst, lhs, rhs, operator: operator is a base Op ordinal (IADD, ISUB, etc.; CX_EQUAL for equality).
   * Resolves through internal type behavior and library
   * functions.
   */
  CX_OPERATOR(Format.RX, Format.ABC, Format.Control.OPERATOR),
  /** CX_GET_TABLE dst, table, key: complete table lookup, including hashing, probing, equality and absent-key semantics. */
  CX_GET_TABLE(Format.R, Format.ABC),
  /**
   * CX_GET_TABLE_IKV resultBase, table, cursor: advances iteration and writes index/key/value plus a next-cursor
   * result; exhaustion is signaled by that cursor.
   */
  CX_GET_TABLE_IKV(Format.R, Format.ABC) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.a(p.word(pc)), 4, 4, pc);
    }
  },
  /** CX_GET_LIST_IV resultBase, list, cursor: advances iteration and writes index/value plus a next-cursor result. */
  CX_GET_LIST_IV(Format.R, Format.ABC) {
    @Override
    void validate(Prototype p, int pc) {
      p.window(Instr.a(p.word(pc)), 3, 4, pc);
    }
  },
  /**
   * CX_SET_TABLE table, key, value: full insertion/update, including resizing, key normalization and reference
   * barriers.
   */
  CX_SET_TABLE(Format.R, Format.ABC),
  /** CX_LIST_APPEND list, value: grows the internal list when needed and appends one complete tagged value. */
  CX_LIST_APPEND(Format.R, Format.AB),

  // ==================== Integrated external heap ISA ====================

  /** CX_EX_CALL0 dst, target, member: external call with zero arguments and capacity for one result. */
  CX_EX_CALL0(Format.R, Format.ABC);

  private final Format format;
  private final int registerMask;
  private final Format.Control control;

  Op(Format format, int registerMask) {
    this(format, registerMask, format == Format.RX ? Format.Control.KIND : Format.Control.NONE);
  }

  Op(Format format, int registerMask, Format.Control control) {
    this.format = format;
    this.registerMask = registerMask;
    this.control = control;
  }

  /**
   * Returns the encoding layout for this operation.
   *
   * @return instruction format
   */
  public Format format() {
    return format;
  }

  /**
   * Returns the mask of register fields used by this operation.
   *
   * @return register-field mask from {@link Format#NO_REGISTERS} through {@link Format#ABC}
   */
  public int registerMask() {
    return registerMask;
  }

  /**
   * Returns the interpretation of this operation's control word.
   *
   * @return control-field metadata
   */
  public Format.Control control() {
    return control;
  }

  void validate(Prototype prototype, int pc) {
  }

  void append(StringBuilder out, Prototype prototype, int pc) {
    format.append(out, prototype, pc, this);
  }

  /**
   * Returns whether this operation belongs to the directly executable base ISA.
   *
   * <p>The explicit list is an O0 contract: adding an opcode requires deliberately choosing its
   * machine representation instead of silently treating a new integrated operation as executable.
   *
   * @return {@code true} when the operation has a base interpreter implementation
   */
  public boolean base() {
    return switch (this) {
      case FRAME_ADDR, FRAME_CONTEXT, ECALL, NOP, MOVE, LC, BR_ZERO, BR_NONZERO,
           IADD, ISUB, IMUL, IDIV, UDIV, UREM, IREM, INEG, FADD, FSUB, FMUL, FDIV, FREM, FNEG,
           BAND, BOR, BXOR, BNOT, SHL, SHR, USHR, ICMP, UCMP, FCMP, EQ64,
           LEA, LOAD8, LOAD16, LOAD32, LOAD64, STORE8, STORE16, STORE32, STORE64, BOUNDS, TRAP, JUMP -> true;
      default -> false;
    };
  }

  /**
   * Returns whether this operation is an integrated language/runtime operation.
   *
   * @return {@code true} for {@code CX_*} operations
   */
  public boolean integrated() {
    return name().startsWith("CX_");
  }

  /**
   * Returns whether this operation crosses the execution-environment boundary.
   *
   * @return {@code true} for {@code ECALL} and external integrated operations
   */
  public boolean external() {
    return this == ECALL || name().startsWith("CX_EX_");
  }
}
