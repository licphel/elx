package io.viki.elx.asm;

import io.viki.elx.Type;

import java.util.Arrays;
import java.util.BitSet;

import static io.viki.elx.asm.InstructionFlow.Node;

/**
 * Stateless O1 optimizer. Rewrites pure constants and copies, then fuses common
 * operations. Calls and operations that may run hooks invalidate propagated
 * bindings. Address-taken locals remain observable across every rewrite.
 */
public final class Optimizer {
  // Bound compile-time work while allowing copy removal and result narrowing to converge.
  private static final int MAX_PASSES = 8;

  private Optimizer() {
  }

  /**
   * Produces an O1 image with safe constant, copy, liveness, and fusion rewrites.
   * Nested function images are optimized recursively, and rewrites preserve
   * address-taken registers and operations that may invoke language hooks.
   *
   * @param image validated prototype to optimize
   * @return optimized prototype with immutable metadata
   */
  public static Prototype optimize(Prototype image) {
    Prototype[] children = new Prototype[image.functionCount()];
    for (int i = 0; i < children.length; i++) {
      children[i] = optimize(image.function(i));
    }
    Assembler out = image.metadata(children);
    InstructionFlow flow = new InstructionFlow(image, out.callSites);
    int[] constants = new int[flow.registers];
    Arrays.fill(constants, -1);
    for (Node n : flow.nodes) {
      if (flow.targets.get(n.pc)) {
        Arrays.fill(constants, -1);
      }
      Op op = n.op();
      int a = n.a(), b = n.b(), c = n.c(), known = -1;
      if (op == Op.LOADK) {
        known = Instr.index(n.words[0]);
      } else if (op == Op.MOVE) {
        known = constants[b];
      } else if (binary(op) && constants[b] >= 0 && constants[c] >= 0) {
        int l = constants[b], r = constants[c];
        if (out.symbols[l] == null && out.symbols[r] == null && !out.linked[l] && !out.linked[r]) {
          ConstantFold.Value value = ConstantFold.fold(op, n.words.length > 1 ? n.words[1] : 0,
              new ConstantFold.Value(Type.fromTag(out.types[l]), out.constants[l]),
              new ConstantFold.Value(Type.fromTag(out.types[r]), out.constants[r]));
          if (value != null) {
            known = out.constant(value.type(), value.bits());
            n.words = new int[] {Instr.upper(Op.LOADK, a, known)};
          }
        }
      }
      if ((op == Op.ADD || op == Op.SUB || op == Op.GETTABLE) && constants[c] >= 0
          && !flow.addressed.get(c)) {
        Op fused = op == Op.ADD ? Op.ADDK : op == Op.SUB ? Op.SUBK : Op.GETFIELD;
        if (known < 0) {
          int index = immediateIndex(out, constants[c]);
          n.words = op == Op.GETTABLE && index >= 0
              ? new int[] {Instr.header(Op.GETI, a, b, index)}
              : new int[] {Instr.header(fused, a, b, 0), constants[c]};
        }
      }
      if (op == Op.SETTABLE && constants[b] >= 0 && !flow.addressed.get(b)) {
        int index = immediateIndex(out, constants[b]);
        if (index >= 0) n.words = new int[] {Instr.header(Op.SETI, a, c, index)};
      }
      var effect = flow.effect(n);
      for (int w = effect.writes().nextSetBit(0); w >= 0; w = effect.writes().nextSetBit(w + 1)) {
        constants[w] = -1;
      }
      if (effect.barrier() || n.target >= 0 || InstructionFlow.terminal(op)) {
        Arrays.fill(constants, -1);
      }
      if (known >= 0 && !flow.addressed.get(a)) {
        constants[a] = known;
      }
    }
    hoistConstants(flow, out);
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      propagateCopies(flow);
      flow.unreachable();
      flow.liveness();
      boolean changed = false;
      for (int i = 0; i < flow.nodes.size(); i++) {
        Node n = flow.nodes.get(i);
        if (n.removed) {
          continue;
        }
        var effect = flow.effect(n);
        if (n.op() == Op.CALL || n.op() == Op.CALL_PACKED || n.op() == Op.CALL_NAMED) {
          int capacity = n.op() == Op.CALL ? Instr.resultCount(n.words[1])
              : n.op() == Op.CALL_NAMED ? out.callSites.get(n.words[1]).resultCapacity() : n.words[1] & 255;
          BitSet needed = (BitSet) flow.liveOut[i].clone();
          needed.or(flow.addressed);
          int count = capacity;
          while (count > 0 && !needed.get(n.a() + count - 1)) {
            count--;
          }
          if (count != capacity) {
            if (n.op() == Op.CALL_NAMED) {
              CallSite site = out.callSites.get(n.words[1]);
              n.words[1] = out.callSite(new CallSite(site.names(), site.kinds(), count));
            } else {
              n.words[1] = n.op() == Op.CALL ? Instr.callWindow(Instr.argumentCount(n.words[1]), count)
                  : n.words[1] & ~255 | count;
            }
            changed = true;
          }
        }
        if (effect.removable()) {
          BitSet observable = (BitSet) effect.writes().clone();
          observable.and(flow.addressed);
          BitSet needed = (BitSet) effect.writes().clone();
          needed.and(flow.liveOut[i]);
          if (observable.isEmpty() && needed.isEmpty()) {
            n.removed = true;
            changed = true;
            continue;
          }
        }
        int next = flow.next(i);
        if (next < 0) {
          continue;
        }
        Node following = flow.nodes.get(next);
        if (flow.targets.get(following.pc)) {
          continue;
        }
        if (n.op() == Op.GETENV && following.op() == Op.GETFIELD && following.b() == n.a()
            && !flow.liveOut[next].get(n.a()) && !flow.addressed.get(n.a())) {
          following.words = new int[] {Instr.upper(Op.GETGLOBAL, following.a(), following.words[1])};
          n.removed = true;
          changed = true;
        } else if (n.op() == Op.COMPARE && (following.op() == Op.BR_TRUE || following.op() == Op.BR_FALSE)
            && following.a() == n.a() && !flow.liveOut[next].get(n.a()) && !flow.addressed.get(n.a())) {
          int control = n.words[1] | (following.op() == Op.BR_FALSE ? Instr.BRANCH_FALSE : 0);
          n.words = new int[] {Instr.header(Op.BR_COMPARE, n.a(), n.b(), n.c()), control, 0};
          n.target = following.target;
          following.removed = true;
          changed = true;
        } else if (writesOne(n.op()) && following.op() == Op.MOVE && following.b() == n.a()
            && !flow.liveOut[next].get(n.a()) && !flow.addressed.get(n.a())) {
          n.header(n.op(), following.a(), n.b(), n.c());
          following.removed = true;
          changed = true;
        }
      }
      if (!changed) {
        break;
      }
    }
    return flow.emit(out);
  }

  private static int immediateIndex(Assembler pool, int constant) {
    // Hooks observe the key's exact type, so a long/byte/etc. must retain its slot.
    if (pool.symbols[constant] != null || pool.linked[constant] || Type.fromTag(pool.types[constant]) != Type.INT)
      return -1;
    long value = pool.constants[constant];
    return value >= 0 && value <= 255 ? (int)value : -1;
  }

  private static void hoistConstants(InstructionFlow flow, Assembler pool) {
    BitSet loops = new BitSet();
    for (Node n : flow.nodes) {
      if (n.target >= 0 && n.target <= n.pc) {
        loops.set(n.target, n.pc + 1);
      }
    }
    int[] slots = new int[pool.constants.length];
    Arrays.fill(slots, -1);
    for (Node n : flow.nodes) {
      if (n.op() != Op.LOADK || !loops.get(n.pc)) {
        continue;
      }
      int constant = Instr.index(n.words[0]);
      if (pool.symbols[constant] != null || pool.linked[constant]
          || !Type.fromTag(pool.types[constant]).numeric()) {
        continue;
      }
      int register = slots[constant];
      if (register < 0) {
        if (flow.registers == Instr.MAX_REGISTERS) {
          continue;
        }
        register = flow.registers++;
        slots[constant] = register;
        flow.prelude.add(Instr.upper(Op.LOADK, register, constant));
      }
      n.words = new int[] {Instr.header(Op.MOVE, n.a(), register, 0)};
    }
  }

  // Bindings represent snapshots. A write invalidates every alias of its old value.
  // Only address-taken slots can change indirectly while an operator/call executes.
  private static void propagateCopies(InstructionFlow flow) {
    int[] aliases = new int[flow.registers];
    Arrays.fill(aliases, -1);
    for (Node n : flow.nodes) {
      if (flow.targets.get(n.pc)) {
        Arrays.fill(aliases, -1);
      }
      if (n.removed) {
        continue;
      }
      Op op = n.op();
      int a = n.a(), b = n.b(), c = n.c();
      boolean readB = switch (op) {
        case MOVE, TYPE, CONVERT, NEG, POS, BNOT, NOT, INC, DEC, TOSTRING, LEN, GETREF,
             ADDI, ADDK, SUBK, GETFIELD, GETI, HASH, GETMETA, ADD, SUB, MUL, DIV, MOD,
             BAND, BOR, BXOR, SHL, SHR, USHR, COMPARE, RAWEQUAL, GETTABLE, RAWGET,
             INDEXREF, SETMETA, BR_COMPARE, SETREF, APPEND, SETFIELD, SETI, SETTABLE,
             RAWSET, CALL, CALL_PACKED, CALL_NAMED, CLOSURE, UNPACK, APPEND_UNPACK,
             GLOBALREF -> true;
        default -> false;
      };
      boolean readC = switch (op) {
        case ADD, SUB, MUL, DIV, MOD, BAND, BOR, BXOR, SHL, SHR, USHR, COMPARE,
             RAWEQUAL, GETTABLE, RAWGET, INDEXREF, SETMETA, BR_COMPARE, SETTABLE, RAWSET,
             CALL_PACKED -> true;
        default -> false;
      };
      boolean readA = switch (op) {
        case SETREF, APPEND, SETFIELD, SETI, SETTABLE, RAWSET, SETUPVAL, SETGLOBAL,
             BR_TRUE, BR_FALSE, BR_PRESENT, BR_VOID, RETURN1, RETURN_UNPACK, APPEND_UNPACK -> true;
        default -> false;
      };
      if (readA && aliases[a] >= 0) {
        n.words[0] = n.words[0] & ~0xFF00 | aliases[a] << 8;
      }
      if (readB && aliases[b] >= 0) {
        n.words[0] = n.words[0] & ~0xFF0000 | aliases[b] << 16;
      }
      if (readC && aliases[c] >= 0) {
        n.words[0] = n.words[0] & 0xFFFFFF | aliases[c] << 24;
      }
      if (op == Op.MOVE && n.a() == n.b()) {
        n.removed = true;
        continue;
      }
      var effect = flow.effect(n);
      for (int r = 0; r < aliases.length; r++) {
        if (effect.writes().get(r) || aliases[r] >= 0 && (effect.writes().get(aliases[r])
            || effect.barrier() && flow.addressed.get(aliases[r]))) {
          aliases[r] = -1;
        }
      }
      if (op == Op.MOVE && !flow.addressed.get(n.a()) && !flow.addressed.get(n.b())) {
        aliases[n.a()] = n.b();
      }
      if (n.target >= 0 || InstructionFlow.terminal(op)) {
        Arrays.fill(aliases, -1);
      }
    }
  }

  private static boolean binary(Op op) {
    return switch (op) {
      case ADD, SUB, MUL, DIV, MOD, BAND, BOR, BXOR, SHL, SHR, USHR, COMPARE -> true;
      default -> false;
    };
  }

  private static boolean writesOne(Op op) {
    return binary(op) || switch (op) {
      case MOVE, ADDK, SUBK, GETFIELD, CONVERT, TYPE, NEG, POS, BNOT, NOT, INC, DEC -> true;
      default -> false;
    };
  }
}
