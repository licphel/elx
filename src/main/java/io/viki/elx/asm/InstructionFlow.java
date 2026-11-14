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

import java.util.*;

/**
 * Compiler-only control-flow and register effects. No allocation on the VM execution path.
 */
final class InstructionFlow {
  final List<Node> nodes = new ArrayList<>();
  final int[] indices;
  final BitSet targets = new BitSet(), addressed = new BitSet();
  final BitSet[] liveOut;
  final int registers, parameters;

  InstructionFlow(Prototype image) {
    registers = image.registerCount();
    parameters = image.parameterCount();
    indices = new int[image.wordCount()];
    Arrays.fill(indices, -1);
    for (int pc = 0; pc < image.wordCount(); pc += Instr.op(image.word(pc)).format().words()) {
      Node node = new Node(image, pc);
      indices[pc] = nodes.size();
      nodes.add(node);
      if (node.target >= 0) {
        targets.set(node.target);
      }
      if (node.op() == Op.CX_LOCAL_PTR) {
        addressed.set(node.b());
      }
    }
    liveOut = new BitSet[nodes.size()];
    for (int i = 0; i < liveOut.length; i++) {
      liveOut[i] = new BitSet(registers);
    }
  }

  static boolean terminal(Op op) {
    return op == Op.CX_RETURN || op == Op.CX_RETURN_INDIRECT || op == Op.CX_RETURN_DEREF || op == Op.TRAP;
  }

  Effect effect(Node n) {
    BitSet use = new BitSet(registers), def = new BitSet(registers);
    boolean pure = false, barrier = false;
    int a = n.a(), b = n.b(), c = n.c();
    switch (n.op()) {
      case NOP -> pure = true;
      case LC -> {
        def.set(a);
        pure = true;
      }
      case MOVE, CX_TYPE, CX_BITS -> {
        use.set(b);
        def.set(a);
        pure = true;
      }
      case CX_LEN, CX_IADD_CONST, LOAD8, LOAD16, LOAD32, LOAD64, CX_LOCAL_PTR -> {
        use.set(b);
        def.set(a);
      }
      case CX_CONVERT, CX_GUARD, INEG, FNEG, BNOT, CX_BOOL_NOT, CX_INCR, CX_DECR, CX_POS -> {
        use.set(n.op() == Op.CX_GUARD ? a : b);
        if (n.op() != Op.CX_GUARD) {
          def.set(a);
        }
        barrier = true;
      }
      case IADD, ISUB, IMUL, IDIV, UDIV, IREM, UREM, FADD, FSUB, FMUL, FDIV, FREM, BAND, BOR, BXOR, SHL, SHR, USHR,
           ICMP, UCMP, FCMP, EQ64, CX_PACK, LEA,
           CX_ADD, CX_SUB, CX_MUL, CX_DIV, CX_REM, CX_AND, CX_OR, CX_XOR, CX_SHL, CX_SHR, CX_USHR, CX_CMP, CX_EQUAL,
           CX_OPERATOR -> {
        use.set(b);
        use.set(c);
        def.set(a);
        barrier = n.op().integrated();
      }
      case STORE8, STORE16, STORE32, STORE64, CX_WRITE_PTR -> {
        use.set(a);
        use.set(b);
        barrier = true;
      }
      case JUMP, TRAP -> {
      }
      case CX_BR_TRUE, CX_BR_FALSE, CX_BR_PRESENT -> use.set(a);
      case CX_BR_ICMP -> {
        use.set(a);
        use.set(b);
      }
      case CX_BR_CMP -> {
        use.set(b);
        use.set(c);
        def.set(a);
        barrier = true;
      }
      case CX_GET_GLOBAL_CONST -> {
        def.set(a);
        barrier = true;
      }
      case CX_ADD_CONST, CX_SUB_CONST, CX_GET_INDEX_CONST -> {
        use.set(b);
        def.set(a);
        barrier = true;
      }
      case CX_RETURN -> {
        if (b > 0) {
          use.set(a, a + b);
        }
      }
      case CX_CALL, CX_EX_CALL -> {
        int control = n.words[1];
        use.set(b);
        if (n.op() == Op.CX_EX_CALL) {
          use.set(c);
        }
        int first = n.op() == Op.CX_CALL ? c : Instr.argumentBase(control), count = Instr.argumentCount(control);
        if (count > 0) {
          use.set(first, first + count);
        }
        if (Instr.resultCount(control) > 0) {
          def.set(a, a + Instr.resultCount(control));
        }
        barrier = true;
      }
      default -> {
        use.set(0, registers);
        barrier = true;
      }
    }
    return new Effect(use, def, pure, barrier);
  }

  int next(int index) {
    while (++index < nodes.size()) {
      if (!nodes.get(index).removed) {
        return index;
      }
    }
    return -1;
  }

  int destination(Node n) {
    int at = indices[n.target];
    if (!nodes.get(at).removed) {
      return at;
    }
    return next(at);
  }

  int[] successors(int i) {
    Node n = nodes.get(i);
    int next = next(i);
    if (terminal(n.op())) {
      return new int[0];
    }
    if (n.op() == Op.JUMP) {
      return new int[] {destination(n)};
    }
    if (n.target >= 0) {
      return next < 0 ? new int[] {destination(n)} : new int[] {destination(n), next};
    }
    return next < 0 ? new int[0] : new int[] {next};
  }

  void liveness() {
    int size = nodes.size();
    BitSet[] in = new BitSet[size];
    Effect[] effects = new Effect[size];
    List<List<Integer>> predecessors = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      in[i] = new BitSet(registers);
      effects[i] = effect(nodes.get(i));
      predecessors.add(new ArrayList<>());
      liveOut[i].clear();
    }
    for (int i = 0; i < size; i++) {
      if (!nodes.get(i).removed) {
        for (int to : successors(i)) {
          if (to >= 0) {
            predecessors.get(to).add(i);
          }
        }
      }
    }
    ArrayDeque<Integer> work = new ArrayDeque<>();
    boolean[] queued = new boolean[size];
    for (int i = size - 1; i >= 0; i--) {
      if (!nodes.get(i).removed) {
        work.add(i);
        queued[i] = true;
      }
    }
    while (!work.isEmpty()) {
      int i = work.removeFirst();
      queued[i] = false;
      BitSet out = new BitSet(registers);
      for (int to : successors(i)) {
        if (to >= 0) {
          out.or(in[to]);
        }
      }
      liveOut[i] = out;
      BitSet input = (BitSet) out.clone();
      input.andNot(effects[i].writes());
      input.or(effects[i].reads());
      if (!input.equals(in[i])) {
        in[i] = input;
        for (int from : predecessors.get(i)) {
          if (!queued[from]) {
            queued[from] = true;
            work.add(from);
          }
        }
      }
    }
  }

  void unreachable() {
    BitSet reached = new BitSet(nodes.size());
    ArrayDeque<Integer> work = new ArrayDeque<>();
    work.add(0);
    while (!work.isEmpty()) {
      int i = work.removeFirst();
      if (i < 0 || reached.get(i)) {
        continue;
      }
      reached.set(i);
      for (int to : successors(i)) {
        work.add(to);
      }
    }
    for (int i = 0; i < nodes.size(); i++) {
      if (!reached.get(i)) {
        nodes.get(i).removed = true;
      }
    }
  }

  Prototype emit(Assembler out) {
    int[] labels = new int[indices.length];
    Arrays.fill(labels, -1);
    for (Node n : nodes) {
      labels[n.pc] = out.label();
    }
    for (Node n : nodes) {
      out.bind(labels[n.pc]);
      if (n.removed) {
        continue;
      }
      out.line(n.line);
      if (n.op().format() == Format.J) {
        out.jump(labels[n.target]);
      } else if (n.op().format() == Format.B) {
        out.branch(n.op(), n.a(), labels[n.target]);
      } else if (n.op().format() == Format.BRX) {
        out.compareBranch(n.words[0], n.words[1], labels[n.target]);
      } else {
        for (int word : n.words) {
          out.word(word);
        }
      }
    }
    return out.build(registers, parameters);
  }

  static final class Node {
    final int pc, line;
    int[] words;
    int target = -1;
    boolean removed;

    Node(Prototype image, int pc) {
      this.pc = pc;
      line = image.line(pc);
      Op op = Instr.op(image.word(pc));
      words = new int[op.format().words()];
      for (int i = 0; i < words.length; i++) {
        words[i] = image.word(pc + i);
      }
      if (op.format().branch()) {
        target = pc + op.format().offset(image, pc) / Instr.WORD_BYTES;
      }
    }

    Op op() {
      return Instr.op(words[0]);
    }

    int a() {
      return Instr.a(words[0]);
    }

    int b() {
      return Instr.b(words[0]);
    }

    int c() {
      return Instr.c(words[0]);
    }

    void header(Op op, int a, int b, int c) {
      words[0] = Instr.header(op, a, b, c);
    }
  }

  record Effect(BitSet reads, BitSet writes, boolean removable, boolean barrier) {
  }
}
