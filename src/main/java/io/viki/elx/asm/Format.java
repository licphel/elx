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

import java.util.Locale;
import java.util.Objects;

/**
 * Instruction encoding layouts and their disassembly/relocation behavior.
 *
 * <p>Each format declares how many words an instruction occupies, which register fields are
 * meaningful, and how a relocatable branch displacement is patched. The metadata is immutable and
 * safe to share between assemblers, optimizers, and tooling.
 */
public enum Format {
  /** op:8 A:8 B:8 C:8. */
  R(1) {
    @Override
    void append(StringBuilder out, Prototype p, int pc, Op op) {
      registers(out, p.word(pc), op.registerMask());
    }
  },
  /** R header and operation-specific control. */
  RX(2) {
    @Override
    void append(StringBuilder out, Prototype p, int pc, Op op) {
      registers(out, p.word(pc), op.registerMask());
      op.control().append(out, p.word(pc + 1));
    }
  },
  /** dst/base/control header and signed 32-bit immediate. */
  I(2) {
    @Override
    void append(StringBuilder out, Prototype p, int pc, Op op) {
      immediate(out, p, pc, op);
    }
  },
  /** src/base/control header and signed 32-bit displacement. */
  S(2) {
    @Override
    void append(StringBuilder out, Prototype p, int pc, Op op) {
      immediate(out, p, pc, op);
    }
  },
  /** condition register and signed 16-bit byte displacement. */
  B(1) {
    @Override
    public boolean branch() {
      return true;
    }

    @Override
    int offset(Prototype p, int pc) {
      return Instr.branchOffset(p.word(pc));
    }

    @Override
    void patch(int[] code, int pc, int offset) {
      code[pc] = Instr.branch(Instr.op(code[pc]), Instr.a(code[pc]), offset);
    }

    @Override
    void append(StringBuilder out, Prototype p, int pc, Op op) {
      registers(out, p.word(pc), op.registerMask());
      out.append(", ").append(target(pc, offset(p, pc)));
    }
  },
  /** destination register and unsigned 16-bit constant index. */
  U(1) {
    @Override
    void append(StringBuilder out, Prototype p, int pc, Op op) {
      int word = p.word(pc), index = Instr.index(word);
      out.append('r').append(Instr.a(word)).append(", k").append(index).append(" ; ").append(p.constantType(index)).append(' ');
      if (p.constantSymbol(index) != null) {
        out.append("library-slot=").append(Objects.requireNonNull(p.constantSymbol(index)).index());
      } else if (p.constantType(index) == Type.STRING && !p.constantLinked(index)) {
        out.append('"').append(escape(p.string(index))).append('"');
      } else {
        out.append("bits=0x").append(Long.toHexString(p.constantBits(index)));
      }
    }
  },
  /** signed 24-bit byte displacement. */
  J(1) {
    @Override
    public boolean branch() {
      return true;
    }

    @Override
    int offset(Prototype p, int pc) {
      return Instr.jumpOffset(p.word(pc));
    }

    @Override
    void patch(int[] code, int pc, int offset) {
      code[pc] = Instr.jump(offset);
    }

    @Override
    void append(StringBuilder out, Prototype p, int pc, Op op) {
      out.append(target(pc, offset(p, pc)));
    }
  },
  /** Register header, comparison control, signed 32-bit displacement. */
  BRX(3) {
    @Override
    public boolean branch() {
      return true;
    }

    @Override
    int offset(Prototype p, int pc) {
      return p.word(pc + 2);
    }

    @Override
    void patch(int[] code, int pc, int offset) {
      code[pc + 2] = offset;
    }

    @Override
    void append(StringBuilder out, Prototype p, int pc, Op op) {
      registers(out, p.word(pc), op.registerMask());
      out.append(", ").append(target(pc, offset(p, pc)));
      op.control().append(out, p.word(pc + 1));
    }
  };

  /** No register fields are present in the format. */
  public static final int NO_REGISTERS = 0;
  /** The A register field is present. */
  public static final int A = 1;
  /** The A and B register fields are present. */
  public static final int AB = 3;
  /** The A, B, and C register fields are present. */
  public static final int ABC = 7;

  private final int words;

  Format(int words) {
    this.words = words;
  }

  private static void registers(StringBuilder out, int word, int mask) {
    boolean first = true;
    for (int field = 0; field < 3; field++) {
      if ((mask & (1 << field)) != 0) {
        if (!first) {
          out.append(", ");
        }
        first = false;
        out.append('r').append(word >>> (8 + field * 8) & 255);
      }
    }
  }

  private static void immediate(StringBuilder out, Prototype p, int pc, Op op) {
    int word = p.word(pc);
    registers(out, word, op.registerMask());
    if (op.registerMask() != 0) {
      out.append(", ");
    }
    out.append(p.word(pc + 1));
    if (Instr.c(word) != 0) {
      out.append(" ctrl=").append(Instr.c(word));
    }
  }

  private static String target(int pc, int offset) {
    return String.format(Locale.ROOT, "@%06x (%+d)", pc * Instr.WORD_BYTES + offset, offset);
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\0", "\\0").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }

  /**
   * Returns the encoded length of this format.
   *
   * @return number of 32-bit instruction words
   */
  public int words() {
    return words;
  }

  /**
   * Returns whether this format carries a relocatable branch displacement.
   *
   * @return {@code true} for branch and jump formats
   */
  public boolean branch() {
    return false;
  }

  int offset(Prototype p, int pc) {
    throw new UnsupportedOperationException("No branch offset in " + this);
  }

  void patch(int[] code, int pc, int offset) {
    throw new UnsupportedOperationException("Not a relocatable format: " + this);
  }

  abstract void append(StringBuilder out, Prototype prototype, int pc, Op op);

  /** Interpretations available for an opcode's operation-specific control field. */
  public enum Control {
    /** No operation-specific control payload. */
    NONE {
      @Override
      void append(StringBuilder out, int control) {
      }
    },
    /** Numeric type or width control. */
    KIND {
      @Override
      void append(StringBuilder out, int control) {
        out.append(" kind=").append(Instr.type(control));
      }
    },
    /** Numeric type, relation, and branch-polarity control. */
    COMPARE {
      @Override
      void append(StringBuilder out, int control) {
        KIND.append(out, control);
        out.append(" relation=").append(Instr.relation(control));
        if ((control & Instr.BRANCH_FALSE) != 0) {
          out.append(" branch=false");
        }
      }
    },
    /** Argument-count and result-capacity control. */
    CALL {
      @Override
      void append(StringBuilder out, int control) {
        out.append(" argc=").append(Instr.argumentCount(control)).append(" results=").append(Instr.resultCount(control));
      }
    },
    /** External-call control including the argument-base register. */
    EX_CALL {
      @Override
      void append(StringBuilder out, int control) {
        out.append(" args=r").append(Instr.argumentBase(control));
        CALL.append(out, control);
      }
    },
    /** Constant-pool index control. */
    CONSTANT {
      @Override
      void append(StringBuilder out, int control) {
        out.append(" constant=k").append(control);
      }
    },
    /** Dynamic operator and relation control. */
    OPERATOR {
      @Override
      void append(StringBuilder out, int control) {
        out.append(" operator=").append(control);
      }
    };

    abstract void append(StringBuilder out, int control);
  }
}
