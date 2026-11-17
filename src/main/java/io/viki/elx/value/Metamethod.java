package io.viki.elx.value;

import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.memory.Memory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Named language hooks. Each enum's lowercase name is its sole metatable key.
 * Cached hook slots avoid string lookup during execution. Unary pos/neg are
 * distinct from binary add/sub. Metadata is immutable and safe to share.
 */
public enum Metamethod {
  ADD,
  SUB,
  MUL,
  DIV,
  MOD,
  AND,
  OR,
  XOR,
  SHL,
  SHR,
  USHR,
  POS,
  NEG,
  INV,
  NOT,
  BOOL,
  EQ,
  NE,
  LT,
  LE,
  GT,
  GE,
  GET,
  SET,
  CALL,
  INIT,
  STR,
  INC,
  DEC,
  GC,
  HASH;

  private static final Metamethod[] ALL = values();
  private final byte[] key = name().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);

  /**
   * Returns the number of defined metamethods.
   *
   * @return metamethod count
   */
  public static int count() {
    return ALL.length;
  }

  /**
   * Returns a metamethod by its stable ordinal.
   *
   * @param index ordinal in the range {@code 0 <= index < count()}
   * @return selected metamethod
   * @throws ArrayIndexOutOfBoundsException if the ordinal is outside the enum range
   */
  public static Metamethod at(int index) {
    return ALL[index];
  }

  /**
   * Resolves an instruction operation and comparison relation to a hook ordinal.
   *
   * @param operation bytecode operation
   * @param relation  encoded comparison relation, used by {@link Op#COMPARE}
   * @return hook ordinal, or {@code -1} when the operation has no metamethod
   */
  public static int operator(Op operation, int relation) {
    return switch (operation) {
      case ADD, ADDK, ADDI -> ADD.ordinal();
      case SUB, SUBK -> SUB.ordinal();
      case MUL -> MUL.ordinal();
      case DIV -> DIV.ordinal();
      case MOD -> MOD.ordinal();
      case BAND -> AND.ordinal();
      case BOR -> OR.ordinal();
      case BXOR -> XOR.ordinal();
      case SHL -> SHL.ordinal();
      case SHR -> SHR.ordinal();
      case USHR -> USHR.ordinal();
      case POS -> POS.ordinal();
      case NEG -> NEG.ordinal();
      case BNOT -> INV.ordinal();
      case NOT -> NOT.ordinal();
      case INC -> INC.ordinal();
      case DEC -> DEC.ordinal();
      case HASH -> HASH.ordinal();
      case COMPARE -> switch (Instr.relation(relation)) {
        case EQ -> EQ.ordinal();
        case NE -> NE.ordinal();
        case LT -> LT.ordinal();
        case LE -> LE.ordinal();
        case GT -> GT.ordinal();
        case GE -> GE.ordinal();
      };
      default -> -1;
    };
  }

  static int name(Memory memory, long string) {
    int length = memory.load32(string);
    for (Metamethod method : ALL) {
      if (method.key.length != length) {
        continue;
      }
      int i = 0;
      while (i < length && method.key[i] == memory.load8(string + Integer.BYTES + i)) {
        i++;
      }
      if (i == length) {
        return method.ordinal();
      }
    }
    return -1;
  }

  /**
   * Reports whether this hook is required to produce a boolean value.
   *
   * @return {@code true} for predicates and boolean conversion
   */
  public boolean booleanResult() {
    return this == NOT || this == BOOL || this == EQ || this == NE || this == LT || this == LE || this == GT || this == GE;
  }
}
