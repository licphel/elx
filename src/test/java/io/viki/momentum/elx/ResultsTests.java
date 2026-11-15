package io.viki.elx;

import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.SimpleLibrary;
import io.viki.elx.memory.Pin;
import io.viki.elx.memory.ExtHeap;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.HostAccess;
import org.jspecify.annotations.Nullable;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.ElxList;
import io.viki.elx.value.Metamethod;

import java.util.Objects;

public final class ResultsTests {
  private static final Compiler COMPILER = new Compiler();
  private static int checks;

  public static void main(String[] args) {
    try (Context context = Context.open()) {
      Results first = context.execute(COMPILER.compile("return 42, 'hello', [7], null"));
      equal(first.size(), 4);
      equal(first.type(0), Type.INT);
      equal(first.type(3), Type.NULL);
      try (Results later = context.execute(COMPILER.compile("return 99"))) {
        context.vm().collect(true);
        try (Pin<Long> number = first.get(0); Pin<String> text = first.get(1);
             Pin<Object> empty = first.get(3)) {
          equal(number.get(), 42L);
          equal(text.get(), "hello");
          equal(empty.get(), null);
        }
      }
      Pin<Long> list = first.get(2);
      first.close();
      first.close();
      expect(IllegalStateException.class, () -> first.get(0));
      try (Results later = context.execute(COMPILER.compile("return 0"))) {
        context.vm().collect(true);
        equal(ElxList.size(context.vm().memory(), Objects.requireNonNull(list.get())), 1);
      }
      list.close();
      equal(list.isAlive(), false);
      expect(IllegalStateException.class, list::get);
      try (Results empty = context.execute(COMPILER.compile("return"))) {
        equal(empty.size(), 0);
        expect(IndexOutOfBoundsException.class, () -> empty.get(0));
      }
      try (Results values = context.execute(COMPILER.compile("return 1.5f, 2d, true"))) {
        try (Pin<Float> f = values.get(0); Pin<Double> d = values.get(1);
             Pin<Boolean> b = values.get(2)) {
          equal(f.get(), 1.5f);
          equal(d.get(), 2.0);
          equal(b.get(), true);
        }
        expect(IndexOutOfBoundsException.class, () -> values.get(-1));
        expect(IndexOutOfBoundsException.class, () -> values.get(3));
      }
      Object host = new Object();
      var library = new SimpleLibrary().function("host", a -> a.ro(host)).freeze();
      Pin<Object> external;
      try (Results values = context.execute(COMPILER.compile("return host()", library))) {
        external = values.get(0);
      }
      try (Results later = context.execute(COMPILER.compile("return 0")); external) {
        context.vm().collect(true);
        equal(external.get() == host, true);
      }
      var reentrant = new SimpleLibrary().function("nested", a -> {
        try (Results nested = context.execute(COMPILER.compile("return 'nested'"));
             Pin<String> text = nested.get(0)) {
          a.rs(Objects.requireNonNull(text.get()));
        }
      }).freeze();
      try (Results values = context.execute(COMPILER.compile("return nested(), 8", reentrant));
           Pin<String> text = values.get(0); Pin<Long> number = values.get(1)) {
        equal(text.get(), "nested");
        equal(number.get(), 8L);
      }
      try (Results values = context.execute(COMPILER.compile("return [3]"))) {
        long handle;
        try (Pin<Long> pin = values.get(0)) {
          handle = Objects.requireNonNull(pin.get());
        }
        try (Results later = context.execute(COMPILER.compile("return"))) {
          values.close();
          context.vm().collect(true);
          equal(context.vm().heap().isAlive(handle), false);
        }
      }
    }
    nullableHostValues();
    hostAccessValues();
    Context context = Context.open();
    Results results = context.execute(COMPILER.compile("return 'held'"));
    Pin<String> pin = results.get(0);
    context.close();
    equal(pin.isAlive(), false);
    expect(IllegalStateException.class, () -> results.get(0));
    results.close();
    pin.close();
    System.out.println("ELX Results: " + checks + " checks passed");
  }

  private static final class Box {
    private @Nullable Object value;
  }

  private static final class Settings implements HostAccess {
    private @Nullable Object value;
    private int resets;
    private int collections;

    @Override
    public void elx_handle(Metamethod method, io.viki.elx.lib.Args args) {
      switch (method) {
        case GET -> {
          if (!args.name().equals("value")) throw new IllegalArgumentException("Unknown Settings member");
          args.ro(value);
        }
        case SET -> {
          if (!args.name().equals("value")) throw new IllegalArgumentException("Unknown Settings member");
          value = args.o(0);
        }
        case CALL -> {
          if (!args.name().equals("reset")) throw new IllegalArgumentException("Unknown Settings member");
          value = null;
          resets++;
        }
        case ADD -> args.rl(42);
        case GC -> collections++;
        default -> { }
      }
    }
  }

  private static final class Host extends ExtHeap {
    Host(ElxHeap heap) {
      super(heap);
    }

    @Override
    protected void readField(Object target, long member, long result) {
      importJava(result, ((Box) target).value);
    }

    @Override
    protected void writeField(Object target, long member, long value) {
      ((Box) target).value = exportValue(value);
    }

    @Override
    protected int invoke(Object target, long member, long args, int count,
                         long results, int capacity) {
      throw new UnsupportedOperationException("Box has no methods");
    }
  }

  private static void nullableHostValues() {
    try (Context context = Context.open(Host::new)) {
      var vm = context.vm();
      long slot = vm.heap().arena().allocate(Slot.SIZE);
      try {
        int objects = vm.external().liveCount();
        vm.external().importJava(slot, null);
        equal(Slot.type(vm.memory(), slot), Type.NULL);
        equal(vm.external().exportValue(slot), null);
        equal(vm.external().liveCount(), objects);
        vm.setGlobal("empty", slot);
        try (Pin<Object> empty = context.pin("empty");
             Pin<Object> absent = context.pin("unknown")) {
          equal(empty.get(), null);
          equal(absent.get(), null);
          equal(empty.isPrimitive(), true);
        }
        Box box = new Box();
        vm.external().importJava(slot, box);
        equal(Slot.type(vm.memory(), slot), Type.EXTERNAL);
        vm.setGlobal("box", slot);
        try (Results values = context.execute(COMPILER.compile("return box.value"));
             Pin<Object> value = values.get(0)) {
          equal(values.type(0), Type.NULL);
          equal(value.get(), null);
        }
        try (Results values = context.execute(COMPILER.compile("box.value=42;box.value=null;return box.value"));
             Pin<Object> value = values.get(0)) {
          equal(box.value, null);
          equal(value.get(), null);
        }
        long member = vm.heap().importString("value");
        long field = vm.external().naddress(Slot.bits(vm.memory(), slot), member);
        equal(vm.external().exportJava(field), null);
        var library = new SimpleLibrary().function("echo", a -> {
          a.arity(1);
          a.ro(a.o(0));
        }).freeze();
        try (Results values = context.execute(COMPILER.compile("return echo(null)", library));
             Pin<Object> value = values.get(0)) {
          equal(values.size(), 1);
          equal(values.type(0), Type.NULL);
          equal(value.get(), null);
        }
      } finally {
        vm.heap().arena().free(slot);
      }
    }
  }

  private static void hostAccessValues() {
    try (Context context = Context.open()) {
      Settings settings = new Settings();
      long slot = context.vm().heap().arena().allocate(Slot.SIZE);
      try {
        context.vm().external().importJava(slot, settings);
        context.vm().setGlobal("settings", slot);
        try (Results values = context.execute(COMPILER.compile(
            "settings.value=42; return settings.value"));
             Pin<Object> value = values.get(0)) {
          equal(value.get(), 42L);
          equal(settings.resets, 0);
        }
        try (Results values = context.execute(COMPILER.compile("return settings + settings"));
             Pin<Long> sum = values.get(0)) {
          equal(sum.get(), 42L);
        }
        context.vm().external().importJava(slot, null);
        context.vm().setGlobal("settings", slot);
        context.vm().collect(true);
        equal(settings.collections, 1);
      } finally {
        context.vm().heap().arena().free(slot);
      }
    }
  }

  private static void equal(Object actual, Object expected) {
    if (!Objects.equals(actual, expected)) {
      throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    checks++;
  }

  private static void expect(Class<? extends Throwable> type, Runnable action) {
    try {
      action.run();
    } catch (Throwable failure) {
      if (!type.isInstance(failure)) {
        throw new AssertionError("Expected " + type.getName(), failure);
      }
      checks++;
      return;
    }
    throw new AssertionError("Expected " + type.getName());
  }
}
