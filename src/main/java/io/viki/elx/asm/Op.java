package io.viki.elx.asm;

import io.viki.elx.Type;

/**
 * O1 register bytecode. Every operand register stores a complete tagged ELX
 * value. Arithmetic includes numeric promotion and metamethod fallback.
 * There is no machine ISA, raw-address arithmetic, or O0 decomposition layer.
 * Immutable metadata is shared safely between compiler and VM threads.
 *
 * <p>A/B/C meanings are specified per instruction. RX extension words carry
 * control or constant indices; register masks exclude immediate fields.
 * Comparison branches preserve the original relation and invert its final
 * boolean with BRANCH_FALSE, including unordered floating-point comparisons.
 */
public enum Op {
  /** A = B. */
  MOVE(Format.R, Format.AB),
  /** A = constant[Bx]. */
  LOADK(Format.U, Format.A),
  /** A = null. */
  LOADNULL(Format.R, Format.A),
  /** A = the missing-value sentinel. */
  LOADVOID(Format.R, Format.A),
  /** A = boolean immediate B (zero or one). */
  LOADBOOL(Format.R, Format.A),

  /** A = the current environment table. */
  GETENV(Format.R, Format.A),
  /** A = environment[constant[Bx]], honoring GET metamethods. */
  GETGLOBAL(Format.U, Format.A),
  /** environment[constant in extension] = A. */
  SETGLOBAL(Format.RX, Format.A, Format.Control.CONSTANT),
  /** A = captured binding B, where B is an index, not a register. */
  GETUPVAL(Format.R, Format.A),
  /** Captured binding B = A. */
  SETUPVAL(Format.R, Format.A),
  /** A = current function/closure. */
  SELF(Format.R, Format.A),
  /** A = closure(function value B, captures starting at C, count in extension). */
  CLOSURE(Format.RX, Format.ABC),

  /** A = new list with initial capacity B (an immediate). */
  NEWLIST(Format.R, Format.A),
  /** A = new table with initial capacity B (an immediate). */
  NEWTABLE(Format.R, Format.A),
  /** A = B[C], honoring GET metamethods. */
  GETTABLE(Format.R, Format.ABC),
  /** A[B] = C, honoring SET metamethods. */
  SETTABLE(Format.R, Format.ABC),
  /** A = B[constant in extension], honoring GET. */
  GETFIELD(Format.RX, Format.AB, Format.Control.CONSTANT),
  /** A[constant in extension] = B, honoring SET. */
  SETFIELD(Format.RX, Format.AB, Format.Control.CONSTANT),
  /** A = B[integer immediate C], honoring GET. */
  GETI(Format.R, Format.AB),
  /** A[integer immediate C] = B, honoring SET. */
  SETI(Format.R, Format.AB),
  /** A = B[C], accessing container storage without GET hooks. */
  RAWGET(Format.R, Format.ABC),
  /** A[B] = C, accessing container storage without SET hooks. */
  RAWSET(Format.R, Format.ABC),
  /** Append B to the list in A, without boxing or container hooks. */
  APPEND(Format.R, Format.AB),

  /** A = B + C; includes string/container behavior and ADD hooks. */
  ADD(Format.R, Format.ABC),
  /** A = B + constant in extension; exactly the semantics of ADD. */
  ADDK(Format.RX, Format.AB, Format.Control.CONSTANT),
  /** A = B + signed integer extension; preserves B's integer width. */
  ADDI(Format.I, Format.AB),
  /** A = B - C. */
  SUB(Format.R, Format.ABC),
  /** A = B - constant in extension. */
  SUBK(Format.RX, Format.AB, Format.Control.CONSTANT),
  /** A = B * C. */
  MUL(Format.R, Format.ABC),
  /** A = B / C; integer division truncates toward zero. */
  DIV(Format.R, Format.ABC),
  /** A = B % C. */
  MOD(Format.R, Format.ABC),
  /** A = {@code B & C}. */
  BAND(Format.R, Format.ABC),
  /** A = B | C. */
  BOR(Format.R, Format.ABC),
  /** A = B ^ C. */
  BXOR(Format.R, Format.ABC),
  /** A = {@code B << C}, masking the shift count to B's width. */
  SHL(Format.R, Format.ABC),
  /** A = B >> C, preserving the sign for signed integers. */
  SHR(Format.R, Format.ABC),
  /** A = B >>> C, shifting in zero bits. */
  USHR(Format.R, Format.ABC),
  /** A = -B. */
  NEG(Format.R, Format.AB),
  /** A = +B. */
  POS(Format.R, Format.AB),
  /** A = ~B. */
  BNOT(Format.R, Format.AB),
  /** A = !B for a boolean operand. */
  NOT(Format.R, Format.AB),
  /** A = B + 1. */
  INC(Format.R, Format.AB),
  /** A = B - 1. */
  DEC(Format.R, Format.AB),
  /** A = B relation C; relation is encoded in the extension. */
  COMPARE(Format.RX, Format.ABC, Format.Control.COMPARE),
  /** A = raw language key equality of B and C, without hooks. */
  RAWEQUAL(Format.R, Format.ABC),

  /** Unconditional branch to the encoded displacement. */
  JMP(Format.J, Format.NO_REGISTERS),
  /** Branch on a strictly boolean A. */
  BR_TRUE(Format.B, Format.A),
  /** Branch on a strictly boolean false A. */
  BR_FALSE(Format.B, Format.A),
  /** Branch when A is supplied, preserving the distinction from explicit null. */
  BR_PRESENT(Format.B, Format.A),
  /** Branch only for the missing/omitted result sentinel. */
  BR_VOID(Format.B, Format.A),
  /** Compare B/C then branch; A is scratch for a hooked boolean result. */
  BR_COMPARE(Format.BRX, Format.ABC, Format.Control.COMPARE),

  /** Call B with arguments starting at C; results start at A. */
  CALL(Format.RX, Format.ABC, Format.Control.CALL),
  /** Call B with a list argument sequence in C; extension is result capacity. */
  CALL_PACKED(Format.RX, Format.ABC),
  /** Named/spread call of B; C starts arguments, extension indexes CallSite. */
  CALL_NAMED(Format.RX, Format.ABC),
  /** Return B consecutive values starting at A; B is an immediate count. */
  RETURN(Format.R, Format.A),
  /** Return without values. */
  RETURN0(Format.R, Format.NO_REGISTERS),
  /** Return the single value in A. */
  RETURN1(Format.R, Format.A),
  /** Return the language sequence represented by A, at most sixteen values. */
  RETURN_UNPACK(Format.R, Format.A),
  /** Copy sequence B into A onward; extension is destination count. */
  UNPACK(Format.RX, Format.AB),
  /** Append the sequence in B to the argument list A. */
  APPEND_UNPACK(Format.R, Format.AB),

  /** A = logical reference to local register B. */
  LOCALREF(Format.R, Format.A),
  /** A = logical reference to B[C], stable across storage resize. */
  INDEXREF(Format.R, Format.ABC),
  /** A = logical reference to environment key B. */
  GLOBALREF(Format.R, Format.AB),
  /** A = logical reference to captured binding B. */
  UPVALREF(Format.R, Format.A),
  /** A = logical reference to constant[Bx]. */
  CONSTREF(Format.U, Format.A),
  /** A = dereferenced value B, preserving managed-pointer checks. */
  GETREF(Format.R, Format.AB),
  /** Write B through logical reference A. */
  SETREF(Format.R, Format.AB),

  /** A = the runtime type tag of B. */
  TYPE(Format.R, Format.AB),
  /** Convert B to type immediate C, honoring the language conversion rules. */
  CONVERT(Format.R, Format.AB),
  /** A = the formatted string representation of B. */
  TOSTRING(Format.R, Format.AB),
  /** A = the language-defined length of B. */
  LEN(Format.R, Format.AB),
  /** A = raw key hash of B. */
  HASH(Format.R, Format.AB),
  /** Assign table/null metatable C to B, returning B in A. */
  SETMETA(Format.R, Format.ABC),
  /** A = the metatable attached to B, or void when none is attached. */
  GETMETA(Format.R, Format.AB),
  /** Publish roots and check budget/collection. */
  SAFEPOINT(Format.R, Format.NO_REGISTERS);

  private final Format format;
  private final int registerMask;
  private final Format.Control control;

  Op(Format format, int registerMask) {
    this(format, registerMask, Format.Control.NONE);
  }

  Op(Format format, int registerMask, Format.Control control) {
    this.format = format;
    this.registerMask = registerMask;
    this.control = control;
  }

  private static void relation(int control, int pc, boolean branch) {
    int allowed = branch ? Instr.BRANCH_FALSE | 255 : 255;
    if ((control & ~allowed) != 0 || (control & 255) >= Instr.Relation.values().length) {
      throw invalid(pc, "invalid comparison control " + control);
    }
  }

  private static void resultCapacity(int capacity, int pc) {
    if (capacity < 0 || capacity > 16) {
      throw invalid(pc, "result capacity outside 0..16: " + capacity);
    }
  }

  private static IllegalArgumentException invalid(int pc, String reason) {
    return new IllegalArgumentException("Invalid ELX instruction at word " + pc + ": " + reason);
  }

  /**
   * Returns the word layout used to encode this instruction.
   *
   * @return instruction layout
   */
  public Format format() {
    return format;
  }

  /**
   * Returns the bit mask identifying register operands in the instruction word.
   *
   * @return register operand mask
   */
  public int registerMask() {
    return registerMask;
  }

  /**
   * Returns the extension-word interpretation required by this instruction.
   *
   * @return extension control kind
   */
  public Format.Control control() {
    return control;
  }

  void validate(Prototype prototype, int pc) {
    int word = prototype.word(pc);
    int a = Instr.a(word), b = Instr.b(word), c = Instr.c(word);
    int extension = format.words() > 1 ? prototype.word(pc + 1) : 0;
    switch (this) {
      case LOADBOOL -> {
        if (b > 1) {
          throw invalid(pc, "boolean immediate outside 0..1");
        }
      }
      case LOCALREF -> prototype.register(b, pc);
      case LOADK, GETGLOBAL, CONSTREF -> prototype.constantIndex(Instr.index(word), pc);
      case SETGLOBAL, GETFIELD, SETFIELD, ADDK, SUBK -> prototype.constantIndex(extension, pc);
      case GETUPVAL, SETUPVAL, UPVALREF -> prototype.captureIndex(b, pc);
      case CLOSURE -> {
        prototype.register(b, pc);
        int count = extension;
        prototype.window(c, count, prototype.registerCount(), pc);
      }
      case COMPARE -> relation(extension, pc, false);
      case BR_COMPARE -> relation(prototype.word(pc + 1), pc, true);
      case CALL -> {
        int arguments = Instr.argumentCount(extension);
        int results = Instr.resultCount(extension);
        prototype.window(c, arguments, 255, pc);
        prototype.window(a, results, 16, pc);
      }
      case CALL_PACKED -> {
        prototype.register(b, pc);
        prototype.register(c, pc);
        resultCapacity(extension & 255, pc);
        prototype.window(a, extension & 255, 16, pc);
      }
      case CALL_NAMED -> {
        prototype.register(b, pc);
        prototype.register(c, pc);
        prototype.callSiteIndex(extension, pc);
        prototype.window(a, prototype.callSite(extension).resultCapacity(), 16, pc);
      }
      case RETURN -> {
        if (b > 16) {
          throw invalid(pc, "return count exceeds sixteen values");
        }
        prototype.window(a, b, 16, pc);
      }
      case UNPACK -> {
        resultCapacity(extension, pc);
        prototype.window(a, extension, 16, pc);
      }
      case CONVERT -> {
        if (c >= Type.values().length) {
          throw invalid(pc, "unknown conversion type " + c);
        }
      }
      case GETI, SETI -> {
        if (c > 255) {
          throw invalid(pc, "integer index immediate must fit signed byte");
        }
      }
      case NEWLIST, NEWTABLE -> {
        // B is an unsigned initial-capacity hint, not a register index.
      }
      default -> {
      }
    }
    if (control == Format.Control.CONSTANT) {
      prototype.constantIndex(extension, pc);
    }
  }

  void append(StringBuilder out, Prototype prototype, int pc) {
    format.append(out, prototype, pc, this);
  }
}
