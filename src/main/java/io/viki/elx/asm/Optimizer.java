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

import java.util.Arrays;
import java.util.BitSet;

import static io.viki.elx.asm.InstructionFlow.Effect;
import static io.viki.elx.asm.InstructionFlow.Node;

/**
 * O1 control-flow and register optimizer. The optimizer is stateless and
 * transforms immutable prototypes without executing user code. It recursively
 * optimizes nested functions, propagates aliases and constants, removes dead
 * instructions, and fuses common comparison/branch patterns while preserving
 * source-line metadata and the VM calling convention.
 */
public final class Optimizer {
  private Optimizer() {
  }

  /**
     * Produces an optimized copy of a compiled function image.
     *
     * @param image validated function prototype to transform
     * @return a new prototype with O1 rewrites applied
   */
  public static Prototype optimize(Prototype image) {
    Prototype[] children = new Prototype[image.functionCount()];
    for (int i = 0; i < children.length; i++) {
      children[i] = optimize(image.function(i));
    }
    Assembler out = image.metadata(children);
    InstructionFlow flow = new InstructionFlow(image);
    propagate(flow, out);
    // Recompute liveness after each rewrite: deleting copies can expose earlier dead copies.
    for (int pass = 0; pass < 4; pass++) {
      flow.unreachable();
      flow.liveness();
      boolean changed = false;
      for (int i = 0; i < flow.nodes.size(); i++) {
        Node n = flow.nodes.get(i);
        if (n.removed) {
          continue;
        }
        Effect effect = flow.effect(n);
        if (effect.removable()) {
          BitSet needed = (BitSet) effect.writes().clone();
          needed.and(flow.liveOut[i]);
          BitSet observable = (BitSet) effect.writes().clone();
          observable.and(flow.addressed);
          if (needed.isEmpty() && observable.isEmpty()) {
            n.removed = true;
            changed = true;
            continue;
          }
        }
        int next = flow.next(i);
        if (next >= 0 && fuse(flow, i, next)) {
          changed = true;
        }
      }
      if (!changed) {
        break;
      }
    }
    return flow.emit(out);
  }

  private static void reset(int[] aliases, int[] constants) {
    for (int i = 0; i < aliases.length; i++) {
      aliases[i] = i;
    }
    Arrays.fill(constants, -1);
  }

  private static boolean unary(Op op) {
    return switch (op) {
      case CX_CONVERT, INEG, FNEG, BNOT, CX_BOOL_NOT, CX_INCR, CX_DECR, CX_POS -> true;
      default -> false;
    };
  }

  private static boolean scalarBinary(Op op) {
    return switch (op) {
      case IADD, ISUB, IMUL, IDIV, UDIV, IREM, UREM, FADD, FSUB, FMUL, FDIV, FREM, BAND, BOR, BXOR, SHL, SHR, USHR,
           ICMP, UCMP, FCMP,
           CX_ADD, CX_SUB, CX_MUL, CX_DIV, CX_REM, CX_AND, CX_OR, CX_XOR, CX_SHL, CX_SHR, CX_USHR, CX_CMP, CX_EQUAL ->
          true;
      default -> false;
    };
  }

  private static void propagate(InstructionFlow flow, Assembler out) {
    int[] aliases = new int[flow.registers], constants = new int[flow.registers];
    reset(aliases, constants);
    for (Node n : flow.nodes) {
      if (flow.targets.get(n.pc)) {
        reset(aliases, constants);
      }
      Op op = n.op();
      int a = n.a(), b = n.b(), c = n.c();
      if (op == Op.MOVE || op == Op.CX_TYPE || op == Op.CX_BITS || unary(op) || scalarBinary(op)) {
        b = aliases[b];
        if (scalarBinary(op)) {
          c = aliases[c];
        }
        n.header(op, a, b, c);
      } else if (op == Op.CX_BR_TRUE || op == Op.CX_BR_FALSE) {
        a = aliases[a];
        n.words[0] = Instr.branch(op, a, 0);
      }
      int known = -1, alias = -1;
      if (op == Op.LC) {
        known = Instr.index(n.words[0]);
      } else if (op == Op.MOVE) {
        known = constants[b];
        alias = b;
      } else if ((unary(op) || op == Op.CX_TYPE || op == Op.CX_BITS) && constants[b] >= 0) {
        int k = constants[b];
        if (out.symbols[k] == null && !out.linked[k]) {
          var value = ConstantFold.unary(op, n.words.length > 1 ? n.words[1] : 0, new ConstantFold.Value(Type.fromTag(out.types[k]), out.constants[k]));
          if (value != null) {
            known = out.constant(value.type(), value.bits());
            n.words = new int[] {Instr.upper(Op.LC, a, known)};
            op = Op.LC;
          }
        }
      } else if (scalarBinary(op) && constants[b] >= 0 && constants[c] >= 0) {
        int l = constants[b], r = constants[c];
        if (out.symbols[l] == null && out.symbols[r] == null && !out.linked[l] && !out.linked[r]) {
          var value = ConstantFold.fold(op, n.words.length > 1 ? n.words[1] : 0, new ConstantFold.Value(Type.fromTag(out.types[l]), out.constants[l]), new ConstantFold.Value(Type.fromTag(out.types[r]), out.constants[r]));
          if (value != null) {
            known = out.constant(value.type(), value.bits());
            n.words = new int[] {Instr.upper(Op.LC, a, known)};
            op = Op.LC;
          }
        }
      }
      if (op == Op.IADD && constants[c] >= 0) {
        int k = constants[c];
        Type kind = Instr.type(n.words[1]);
        long bits = out.constants[k];
        if (kind.integer() && out.symbols[k] == null && !out.linked[k] && Type.fromTag(out.types[k]) == kind && bits == (int) bits) {
          n.words = new int[] {Instr.header(Op.CX_IADD_CONST, a, b, kind.ordinal()), (int) bits};
          op = Op.CX_IADD_CONST;
        }
      }
      if (op == Op.CX_GET_GLOBAL && constants[b] >= 0 && !flow.addressed.get(b)) {
        n.words = new int[] {Instr.upper(Op.CX_GET_GLOBAL_CONST, a, constants[b])};
        op = Op.CX_GET_GLOBAL_CONST;
      }
      if ((op == Op.CX_ADD || op == Op.CX_SUB) && constants[c] >= 0 && !flow.addressed.get(c)) {
        op = op == Op.CX_ADD ? Op.CX_ADD_CONST : Op.CX_SUB_CONST;
        n.words = new int[] {Instr.header(op, a, b, 0), constants[c]};
      }
      if (op == Op.CX_GET_INDEX && constants[c] >= 0 && !flow.addressed.get(c)) {
        n.words = new int[] {Instr.header(Op.CX_GET_INDEX_CONST, a, b, 0), constants[c]};
        op = Op.CX_GET_INDEX_CONST;
      }
      if ((op == Op.CX_BR_TRUE || op == Op.CX_BR_FALSE) && constants[a] >= 0) {
        int k = constants[a];
        if (out.symbols[k] == null && Type.fromTag(out.types[k]) == Type.BOOL) {
          boolean taken = (out.constants[k] != 0) == (op == Op.CX_BR_TRUE);
          if (taken) {
            n.words = new int[] {Instr.jump(0)};
          } else {
            n.removed = true;
          }
        }
      }
      Effect effect = flow.effect(n);
      for (int written = effect.writes().nextSetBit(0); written >= 0;
           written = effect.writes().nextSetBit(written + 1)) {
        for (int i = 0; i < aliases.length; i++) {
          if (aliases[i] == written) {
            aliases[i] = i;
          }
        }
        aliases[written] = written;
        constants[written] = -1;
      }
      if (known >= 0) {
        constants[a] = known;
      }
      if (alias >= 0 && alias != a) {
        aliases[a] = alias;
      }
      if (effect.barrier() || n.target >= 0 || InstructionFlow.terminal(op)) {
        reset(aliases, constants);
      }
    }
  }

  private static boolean writesOne(Node n) {
    return n.op() == Op.CX_ADD_CONST || n.op() == Op.CX_SUB_CONST || scalarBinary(n.op()) || unary(n.op()) || n.op() == Op.CX_CONVERT || n.op() == Op.CX_TYPE || n.op() == Op.CX_BITS || n.op() == Op.MOVE;
  }

  private static boolean fuse(InstructionFlow flow, int index, int next) {
    Node n = flow.nodes.get(index), following = flow.nodes.get(next);
    if (flow.targets.get(following.pc)) {
      return false;
    }
    if (writesOne(n) && following.op() == Op.MOVE && following.b() == n.a() && !flow.liveOut[next].get(n.a()) && !flow.addressed.get(n.a())) {
      n.header(n.op(), following.a(), n.b(), n.c());
      following.removed = true;
      return true;
    }
    if (n.op() == Op.ICMP && (following.op() == Op.CX_BR_TRUE || following.op() == Op.CX_BR_FALSE) && following.a() == n.a() && !flow.liveOut[next].get(n.a()) && !flow.addressed.get(n.a())) {
      int control = n.words[1];
      Instr.Relation relation = Instr.relation(control);
      if (following.op() == Op.CX_BR_FALSE) {
        relation = switch (relation) {
          case EQ -> Instr.Relation.NE;
          case NE -> Instr.Relation.EQ;
          case LT -> Instr.Relation.GE;
          case LE -> Instr.Relation.GT;
          case GT -> Instr.Relation.LE;
          case GE -> Instr.Relation.LT;
        };
      }
      n.words = new int[] {Instr.header(Op.CX_BR_ICMP, n.b(), n.c(), 0), Instr.control(Instr.type(control), relation), 0};
      n.target = following.target;
      following.removed = true;
      return true;
    }
    if (n.op() == Op.CX_CMP && (following.op() == Op.CX_BR_TRUE || following.op() == Op.CX_BR_FALSE)
        && following.a() == n.a() && !flow.liveOut[next].get(n.a()) && !flow.addressed.get(n.a())) {
      int control = n.words[1] | (following.op() == Op.CX_BR_FALSE ? Instr.BRANCH_FALSE : 0);
      n.words = new int[] {Instr.header(Op.CX_BR_CMP, n.a(), n.b(), n.c()), control, 0};
      n.target = following.target;
      following.removed = true;
      return true;
    }
    if (n.op() == Op.MOVE && n.a() == n.b()) {
      n.removed = true;
      return true;
    }
    return false;
  }
}
