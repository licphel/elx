package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.lib.Span;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.RawValues;
import io.viki.elx.value.ValueOperations;

/** VM-owned shared semantic engine. Primitive dispatch never constructs a span. */
final class Operations implements ValueOperations {
  private final VM vm;
  private final Memory memory;

  Operations(VM vm) {
    this.vm = vm;
    this.memory = vm.memory();
  }

  private long at(Span span, int index) {
    if (Slots.vm(span) != vm) {
      throw new IllegalArgumentException("Span belongs to another VM");
    }
    return Slots.address(span, index);
  }

  void execute(Op op, long out, long left, long right, int relation) {
    if (op == Op.COMPARE) {
      compare(out, left, right, relation);
      return;
    }
    if (RawValues.numericOperator(op, memory, out, left, right)) {
      return;
    }
    executeSlow(op, out, left, right, relation);
  }

  private void executeSlow(Op op, long out, long left, long right, int relation) {
    Type a = Slot.type(memory, left), b = Slot.type(memory, right);
    boolean unary = op == Op.NEG || op == Op.POS || op == Op.BNOT || op == Op.NOT || op == Op.INC || op == Op.DEC;
    if (!(a.numeric() && (unary || b.numeric())) && vm.operatorHook(op, relation, out, left, right)) {
      return;
    }
    if (op != Op.COMPARE && (a == Type.VOID || !unary && b == Type.VOID)) {
      throw new IllegalArgumentException("Operation " + op + " requires an explicit conversion from void");
    }
    if (op == Op.INC || op == Op.DEC) {
      RawValues.increment(memory, out, left, op == Op.INC ? 1 : -1);
    } else if (op == Op.ADD && (a == Type.STRING || b == Type.STRING)) {
      if (a == Type.STRING && b == Type.STRING) {
        Slot.write(memory, out, Type.STRING, vm.heap().strings().concat(Slot.bits(memory, left), Slot.bits(memory, right)));
      } else {
        String x = vm.format(left), y = vm.format(right);
        Slot.write(memory, out, Type.STRING, vm.heap().strings().importJava(x + y));
      }
    } else {
      RawValues.operator(vm.heap(), op.ordinal(), out, left, right);
    }
  }

  private void compare(long out, long left, long right, int relation) {
    boolean result = test(left, right, relation, out);
    Slot.write(memory, out, Type.BOOL, result ? 1 : 0);
  }

  boolean test(long left, long right, int relation, long scratch) {
    Type a = Slot.type(memory, left), b = Slot.type(memory, right);
    if (a.numeric() && b.numeric()) {
      return RawValues.numericTest(Instr.relation(relation), a, Slot.bits(memory, left), b, Slot.bits(memory, right));
    }
    if (!vm.operatorHook(Op.COMPARE, relation, scratch, left, right)) {
      RawValues.operator(vm.heap(), Op.COMPARE.ordinal() | relation << 8, scratch, left, right);
    }
    return Slot.bits(memory, scratch) != 0;
  }

  @Override
  public void unary(Op op, Span source, int index, Span out, int output) {
    switch (op) {
      case POS, NEG, BNOT, NOT, INC, DEC -> {
      }
      default -> throw new IllegalArgumentException("Not a unary value operator: " + op);
    }
    long value = at(source, index);
    execute(op, at(out, output), value, value, 0);
  }

  @Override
  public void binary(Op op, Span left, int li, Span right, int ri, Span out, int oi) {
    switch (op) {
      case ADD, SUB, MUL, DIV, MOD, BAND, BOR, BXOR, SHL, SHR, USHR -> {
      }
      default -> throw new IllegalArgumentException("Not a binary value operator: " + op);
    }
    execute(op, at(out, oi), at(left, li), at(right, ri), 0);
  }

  @Override
  public boolean test(Instr.Relation relation, Span left, int li, Span right, int ri) {
    return test(at(left, li), at(right, ri), relation.ordinal());
  }

  @Override
  public void get(Span target, int ti, Span key, int ki, Span out, int oi) {
    vm.runtimeReadIndex(at(out, oi), at(target, ti), at(key, ki));
  }

  @Override
  public void set(Span target, int ti, Span key, int ki, Span value, int vi) {
    vm.runtimeWriteIndex(at(target, ti), at(key, ki), at(value, vi));
  }

  @Override
  public void convert(Type type, Span source, int si, Span out, int oi) {
    vm.runtimeConvert(at(out, oi), at(source, si), type.ordinal());
  }

  @Override
  public int call(Span function, int fi, Span inputs, Span outputs) {
    long callable = at(function, fi);
    if (Slots.vm(inputs) != vm || Slots.vm(outputs) != vm) {
      throw new IllegalArgumentException("Call spans belong to another VM");
    }
    return vm.callSynchronously(callable, inputs.size() == 0 ? 0 : at(inputs, 0), inputs.size(),
        outputs.size() == 0 ? 0 : at(outputs, 0), outputs.size());
  }

  @Override
  public long hash(Span source, int index) {
    return hashValue(at(source, index));
  }

  @Override
  public String format(Span source, int index) {
    return vm.format(at(source, index));
  }

  boolean test(long left, long right, int relation) {
    Type a = Slot.type(memory, left), b = Slot.type(memory, right);
    if (a.numeric() && b.numeric()) {
      return RawValues.numericTest(Instr.relation(relation), a, Slot.bits(memory, left), b, Slot.bits(memory, right));
    }
    try (Slots.Scope out = vm.borrowSlots(1)) {
      return test(left, right, relation, Slots.address(out, 0));
    }
  }

  long hashValue(long value) {
    try (Slots.Scope out = vm.borrowSlots(1)) {
      long result = Slots.address(out, 0);
      if (vm.operatorHook(Op.HASH, 0, result, value, value)) {
        if (!out.type(0).integer()) {
          throw new IllegalArgumentException("hash metamethod must return an integer");
        }
        return out.i(0);
      }
      return Slot.type(memory, value) == Type.STRING
          ? vm.heap().strings().hash(Slot.bits(memory, value)) : RawValues.hash(memory, value);
    }
  }
}
