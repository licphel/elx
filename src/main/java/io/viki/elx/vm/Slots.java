package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.lib.Span;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.ContOp;
import io.viki.elx.value.ValueOperations;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Internal slot-window adapter. Public only to connect implementation packages;
 * application callbacks use Span/Call. Addresses are borrowed, never container
 * entry references, and must not survive their supplying scope.
 */
public class Slots implements Span {
  protected final VM owner;
  protected long base;
  protected int count;

  public Slots(VM vm, long base, int count) {
    this.owner = vm;
    this.base = base;
    this.count = count;
  }

  public static VM vm(Span span) {
    if (span instanceof Slots slots) {
      return slots.owner;
    }
    throw new IllegalArgumentException("Span was not supplied by an ELX VM");
  }

  public static long address(Span span, int index) {
    Slots slots = (Slots) span;
    return Slot.address(slots.base, Objects.checkIndex(index, slots.count));
  }

  public static Memory memory(Span span) {
    return vm(span).memory();
  }

  public static ElxHeap heap(Span span) {
    return vm(span).heap();
  }

  public static void same(Span left, Span right) {
    if (vm(left) != vm(right)) {
      throw new IllegalArgumentException("Spans belong to different ELX VMs");
    }
  }

  @Override
  public int size() {
    return count;
  }

  @Override
  public Type type(int index) {
    return Slot.type(owner.memory(), address(this, index));
  }

  @Override
  public long bits(int index) {
    return Slot.bits(owner.memory(), address(this, index));
  }

  @Override
  public void set(int index, Type type, long bits) {
    Slot.write(owner.memory(), address(this, index), type, bits);
  }

  @Override
  public String s(int index) {
    if (type(index) == Type.VOID) {
      return "";
    }
    if (type(index) != Type.STRING) {
      throw new IllegalArgumentException("Slot " + index + " must be STRING");
    }
    return owner.heap().strings().exportJava(bits(index));
  }

  @Override
  public @Nullable Object o(int index) {
    return owner.external().exportValue(address(this, index));
  }

  @Override
  public void setString(int index, String value) {
    set(index, Type.STRING, owner.heap().strings().importJava(value));
  }

  @Override
  public void setObject(int index, @Nullable Object value) {
    owner.external().importJava(address(this, index), value);
  }

  @Override
  public ValueOperations operations() {
    return owner.operations();
  }

  @Override
  public ContOp containers() {
    return owner.containers();
  }

  @Override
  public void copy(int index, Span source, int sourceIndex) {
    same(this, source);
    Slot.copy(owner.memory(), address(this, index), address(source, sourceIndex), 1);
  }

  /** Register-rooted scratch window; scopes must close in reverse borrow order. */
  public static final class Scope extends Slots implements AutoCloseable {
    private boolean closed;

    Scope(VM vm, long base, int count) {
      super(vm, base, count);
    }

    @Override
    public void close() {
      if (!closed) {
        owner.releaseSlots();
        closed = true;
        base = 0;
        count = 0;
      }
    }
  }
}
