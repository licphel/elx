package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.lib.Call;
import io.viki.elx.lib.HostFunction;
import io.viki.elx.lib.Span;
import io.viki.elx.memory.*;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Internal native bridge. Each reentrant call borrows its own input/result state. */
public final class HostCalls {
  private HostCalls() {
  }

  public static ExtHeap.BoundCall bind(VM vm, HostFunction function) {
    return new ExtHeap.BoundCall() {
      private final ArrayDeque<Invocation> pool = new ArrayDeque<>();

      @Override
      public int invoke(Memory memory, long inputs, int count, long outputs, int capacity) {
        Invocation call = pool.pollFirst();
        if (call == null) {
          call = new Invocation(vm);
        }
        call.reset(inputs, count, outputs, capacity);
        try {
          function.invoke(call);
          return Math.min(call.returnedCount(), capacity);
        } finally {
          call.release();
          pool.addFirst(call);
        }
      }

      @Override
      public void trace(HeapVisitor visitor) {
        function.trace(visitor);
      }
    };
  }

  /** HostAccess uses the same invocation abstraction as registered functions. */
  public static int dispatch(VM vm, HostAccess host, io.viki.elx.value.Metamethod method,
                             long inputs, int count, long outputs, int capacity) {
    Invocation call = new Invocation(vm);
    call.reset(inputs, count, outputs, capacity);
    try {
      host.elx_handle(method, call);
      return call.returnedCount();
    } finally {
      call.release();
    }
  }

  private static final class Invocation extends Slots implements Call {
    private final ArrayList<Slots.Scope> scratch = new ArrayList<>();
    private long outputs;
    private int capacity, returned;

    Invocation(VM vm) {
      super(vm, 0, 0);
    }

    void reset(long inputs, int count, long outputs, int capacity) {
      this.base = inputs;
      this.count = count;
      this.outputs = outputs;
      this.capacity = capacity;
      this.returned = 0;
    }

    void release() {
      for (int i = scratch.size() - 1; i >= 0; i--) {
        scratch.get(i).close();
      }
      scratch.clear();
      base = 0;
      count = 0;
      outputs = 0;
    }

    @Override
    public void r(Type type, long bits) {
      if (returned >= Call.MAX_RESULTS) {
        throw new IllegalStateException("An ELX function may return at most sixteen values");
      }
      if (returned < capacity) {
        Slot.write(owner.memory(), Slot.address(outputs, returned), type, bits);
      }
      returned++;
    }

    @Override
    public void rs(String value) {
      r(Type.STRING, owner.heap().strings().importJava(value));
    }

    @Override
    public void ro(@Nullable Object value) {
      if (returned >= Call.MAX_RESULTS) {
        throw new IllegalStateException("An ELX function may return at most sixteen values");
      }
      if (returned < capacity) {
        owner.external().importJava(Slot.address(outputs, returned), value);
      }
      returned++;
    }

    @Override
    public int returnedCount() {
      return returned;
    }

    @Override
    public Span scratch(int size) {
      Slots.Scope window = owner.borrowSlots(size);
      scratch.add(window);
      return window;
    }

    @Override
    public void rvalue(Span source, int index) {
      same(this, source);
      r(source.type(index), source.bits(index));
    }
  }
}
