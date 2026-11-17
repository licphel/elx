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
  final List<CallSite> callSites;
  final BitSet targets = new BitSet(), addressed = new BitSet();
  final BitSet[] liveOut;
  final int parameters;
  final List<Integer> prelude = new ArrayList<>();
  int registers;

  InstructionFlow(Prototype image, List<CallSite> callSites) {
    this.callSites = callSites;
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
      if (node.op() == Op.LOCALREF) {
        addressed.set(node.b());
      }
    }
    liveOut = new BitSet[nodes.size()];
    for (int i = 0; i < liveOut.length; i++) {
      liveOut[i] = new BitSet(registers);
    }
  }

  static boolean terminal(Op op) {
    return op == Op.RETURN || op == Op.RETURN0 || op == Op.RETURN1 || op == Op.RETURN_UNPACK;
  }

  private static int remap(int word, int[] registers) {
    Op op = Instr.op(word);
    int mask = op.registerMask();
    if ((mask & 1) != 0) {
      word = word & ~0xFF00 | registers[Instr.a(word)] << 8;
    }
    if ((mask & 2) != 0 || op == Op.LOCALREF) {
      word = word & ~0xFF0000 | registers[Instr.b(word)] << 16;
    }
    if ((mask & 4) != 0) {
      word = word & 0xFFFFFF | registers[Instr.c(word)] << 24;
    }
    return word;
  }

  Effect effect(Node n) {
    BitSet use = new BitSet(registers), def = new BitSet(registers);
    boolean pure = false, barrier = false;
    int a = n.a(), b = n.b(), c = n.c();
    switch (n.op()) {
      case LOADK, LOADNULL, LOADVOID, LOADBOOL -> {
        def.set(a);
        pure = true;
      }
      case MOVE, TYPE -> {
        use.set(b);
        def.set(a);
        pure = true;
      }
      case LOCALREF -> {
        use.set(b);
        def.set(a);
      }
      case CONVERT, NEG, POS, BNOT, NOT, INC, DEC, TOSTRING, LEN, GETREF,
           ADDI, ADDK, SUBK, GETFIELD, GETI, HASH, GETMETA, GLOBALREF -> {
        use.set(b);
        def.set(a);
        barrier = true;
      }
      case ADD, SUB, MUL, DIV, MOD, BAND, BOR, BXOR, SHL, SHR, USHR,
           COMPARE, RAWEQUAL, GETTABLE, RAWGET, INDEXREF, SETMETA -> {
        use.set(b);
        use.set(c);
        def.set(a);
        barrier = true;
      }
      case GETENV -> {
        def.set(a);
        pure = true;
      }
      case NEWLIST, NEWTABLE, GETGLOBAL, GETUPVAL, SELF, UPVALREF, CONSTREF -> {
        def.set(a);
        barrier = true;
      }
      case SETGLOBAL, SETUPVAL -> {
        use.set(a);
        barrier = true;
      }
      case SETREF, APPEND, APPEND_UNPACK, SETFIELD, SETI -> {
        use.set(a);
        use.set(b);
        barrier = true;
      }
      case SETTABLE, RAWSET -> {
        use.set(a);
        use.set(b);
        use.set(c);
        barrier = true;
      }
      case JMP, RETURN0 -> {
      }
      case SAFEPOINT -> barrier = true;
      case BR_TRUE, BR_FALSE, BR_PRESENT, BR_VOID -> use.set(a);
      case BR_COMPARE -> {
        use.set(b);
        use.set(c);
        def.set(a);
        barrier = true;
      }
      case RETURN -> {
        if (b > 0) {
          use.set(a, a + b);
        }
      }
      case RETURN1, RETURN_UNPACK -> use.set(a);
      case CALL -> {
        use.set(b);
        int count = Instr.argumentCount(n.words[1]);
        if (count > 0) {
          use.set(c, c + count);
        }
        int results = Instr.resultCount(n.words[1]);
        if (results > 0) {
          def.set(a, a + results);
        }
        barrier = true;
      }
      case CALL_PACKED -> {
        use.set(b);
        use.set(c);
        int count = n.words[1] & 255;
        if (count > 0) {
          def.set(a, a + count);
        }
        barrier = true;
      }
      case CALL_NAMED -> {
        CallSite site = callSites.get(n.words[1]);
        use.set(b);
        if (!site.names().isEmpty()) {
          use.set(c, c + site.names().size());
        }
        if (site.resultCapacity() > 0) {
          def.set(a, a + site.resultCapacity());
        }
        barrier = true;
      }
      case CLOSURE -> {
        use.set(b);
        if (n.words[1] > 0) {
          use.set(c, c + n.words[1]);
        }
        def.set(a);
        barrier = true;
      }
      case UNPACK -> {
        use.set(b);
        if (n.words[1] > 0) {
          def.set(a, a + n.words[1]);
        }
        barrier = true;
      }
      default -> throw new IllegalStateException("Missing register effects for " + n.op());
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
    if (n.op() == Op.JMP) {
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
    // Remove unused gaps left by narrowed call windows. Every bulk operand marks
    // its complete interval, so ascending compaction preserves contiguous windows.
    BitSet used = new BitSet(registers);
    used.set(0, parameters);
    for (int word : prelude) {
      used.set(Instr.a(word));
    }
    for (Node n : nodes) {
      if (n.removed) {
        continue;
      }
      Effect effect = effect(n);
      used.or(effect.reads());
      used.or(effect.writes());
      int mask = n.op().registerMask();
      if ((mask & 1) != 0) {
        used.set(n.a());
      }
      if ((mask & 2) != 0) {
        used.set(n.b());
      }
      if ((mask & 4) != 0) {
        used.set(n.c());
      }
    }
    int[] registers = new int[this.registers];
    int count = 0;
    for (int r = used.nextSetBit(0); r >= 0; r = used.nextSetBit(r + 1)) {
      registers[r] = count++;
    }
    for (Node n : nodes) {
      if (!n.removed) {
        n.words[0] = remap(n.words[0], registers);
      }
    }
    int[] labels = new int[indices.length];
    Arrays.fill(labels, -1);
    for (Node n : nodes) {
      labels[n.pc] = out.label();
    }
    for (int word : prelude) {
      out.word(remap(word, registers));
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
    return out.build(Math.max(1, count), parameters);
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
