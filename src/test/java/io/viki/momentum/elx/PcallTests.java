package io.viki.momentum.elx;

import io.viki.elx.Context;
import io.viki.elx.Results;
import io.viki.elx.Type;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.Call;
import io.viki.elx.memory.HostAccess;
import io.viki.elx.memory.Pin;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.Metamethod;

import java.util.Objects;

/** Executable checks for the built-in assertion, throwing, and protected-call contracts. */
public final class PcallTests {
  private static final Compiler COMPILER = new Compiler();
  private static int checks;

  private PcallTests() { }

  public static void main(String[] args) {
    run();
  }

  public static void run() {
    try (Context context = Context.open()) {
      try (Results values = execute(context, "return assert(true), assert(true, 'unused')")) {
        equal(values.size(), 2);
        equal(values.type(0), Type.BOOL);
        equal(value(values, 0), true);
        equal(value(values, 1), true);
      }

      AssertionError defaultAssertion = expect(AssertionError.class,
          () -> execute(context, "assert(false)"));
      equal(defaultAssertion.getMessage(), "assertion failed");
      AssertionError voidAssertion = expect(AssertionError.class,
          () -> execute(context, "assert(bool(void))"));
      equal(voidAssertion.getMessage(), "assertion failed");
      AssertionError describedAssertion = expect(AssertionError.class,
          () -> execute(context, "assert(false, 'assert detail')"));
      equal(describedAssertion.getMessage(), "assert detail");
      RuntimeException thrown = expect(RuntimeException.class,
          () -> execute(context, "throw('throw detail')"));
      equal(thrown.getMessage(), "throw detail");
      expect(IllegalArgumentException.class,
          () -> execute(context, "pcall(function() { return 1 })"));
      expect(IllegalArgumentException.class,
          () -> execute(context, "pcall(function() {}, function() {}, function() {})"));
      IllegalArgumentException invalidHandler = expect(IllegalArgumentException.class,
          () -> execute(context, "pcall(function() { return 1 }, {})"));
      equal(invalidHandler.getMessage(), "pcall argument 1 must be callable");

      try (Results values = execute(context,
          "local handlerCalls=0; " +
              "local ok,value,calls=pcall(" +
              "function() { return 7 }, " +
              "function(error) { handlerCalls+=1; return 8 }); " +
              "return ok,value,handlerCalls")) {
        equal(values.size(), 3);
        equal(value(values, 0), true);
        equal(value(values, 1), 7L);
        equal(value(values, 2), 0L);
      }

      try (Results values = execute(context,
          "local ok,a,b,c,d,e,f,g,h,i,j,k,l,m,n,o=" +
              "pcall(function() { return 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16 }, " +
              "function(error) { return -1 }); " +
              "return ok,a,b,c,d,e,f,g,h,i,j,k,l,m,n,o")) {
        equal(values.size(), 16);
        equal(value(values, 0), true);
        for (int index = 1; index < 16; index++) {
          equal(value(values, index), (long) index);
        }
      }

      try (Results values = execute(context,
          "local handlerCalls=0; " +
              "local ok,kind,cause,trace,calls=pcall(" +
              "function() { throw('target failure') }, " +
              "function(error) { handlerCalls+=1; " +
              "return type(error),error.cause,type(error.stacktrace),handlerCalls }); " +
              "return ok,kind,cause,trace,calls")) {
        equal(values.size(), 5);
        equal(value(values, 0), false);
        equal(value(values, 1), "table");
        equal(value(values, 2), "RuntimeException: target failure");
        equal(value(values, 3), "string");
        equal(value(values, 4), 1L);
      }

      try (Results values = execute(context,
          "local ok,cause=pcall(function() { assert(false,'assert from target') }, " +
              "function(error) { return error.cause }); return ok,cause")) {
        equal(values.size(), 2);
        equal(value(values, 0), false);
        equal(value(values, 1), "AssertionError: assert from target");
      }

      hostCallable(context);

      RuntimeException handlerFailure = expect(RuntimeException.class,
          () -> execute(context,
              "pcall(function() { throw('target failure') }, " +
                  "function(error) { throw('handler failure') })"));
      equal(handlerFailure.getMessage(), "handler failure");
    }
    System.out.println("ELX pcall/assert/throw: " + checks + " checks passed");
  }

  private static void hostCallable(Context context) {
    HostCallable callback = new HostCallable();
    long slot = context.vm().heap().arena().allocate(Slot.SIZE);
    try {
      context.vm().external().importJava(slot, callback);
      context.vm().setGlobal("callback", slot);
      try (Results values = execute(context,
          "local ok,value=pcall(callback,function(error) { return 0 }); return ok,value")) {
        equal(value(values, 0), true);
        equal(value(values, 1), 31L);
      }
      try (Results values = execute(context,
          "local ok,value=pcall(function() { throw('host target') },callback); return ok,value")) {
        equal(value(values, 0), false);
        equal(value(values, 1), "host handled");
      }
      equal(callback.calls, 1);
    } finally {
      try {
        context.vm().external().importJava(slot, io.viki.elx.VoidValue.INSTANCE);
        context.vm().setGlobal("callback", slot);
        context.vm().collect(true);
      } finally {
        context.vm().heap().arena().free(slot);
      }
    }
  }

  private static Results execute(Context context, String source) {
    return context.execute(COMPILER.compile(source));
  }

  private static final class HostCallable implements HostAccess {
    private int calls;

    @Override
    public void elx_handle(Metamethod method, Call args) {
      if (method != Metamethod.CALL) return;
      if (args.size() == 1) {
        args.rl(31);
        return;
      }
      if (args.size() != 2 || args.type(1) != Type.TABLE) {
        throw new AssertionError("Expected self and an error table");
      }
      calls++;
      args.rs("host handled");
    }
  }

  private static Object value(Results results, int index) {
    try (Pin<Object> pin = results.get(index)) {
      return pin.get();
    }
  }

  private static <T extends Throwable> T expect(Class<T> type, Runnable action) {
    checks++;
    try {
      action.run();
    } catch (Throwable failure) {
      if (type.isInstance(failure)) return type.cast(failure);
      throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
    }
    throw new AssertionError("Expected " + type.getSimpleName());
  }

  private static void equal(Object actual, Object expected) {
    checks++;
    if (!Objects.equals(actual, expected)) {
      throw new AssertionError("Expected " + expected + ", got " + actual);
    }
  }
}
