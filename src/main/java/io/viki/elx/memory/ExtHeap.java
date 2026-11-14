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

package io.viki.elx.memory;

import io.viki.elx.Type;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * External Java boundary, physically separate from ELX byte storage. Negative
 * handles identify host resources or field locations. Java references exist only
 * here; they are never encoded as JVM addresses. Implementations supply member
 * access/calls without imposing reflection. Thread-confined; shares internal Memory.
 */
public abstract class ExtHeap extends ManagedHeap<@Nullable Object> {
  private final ElxHeap internal;
  private final Memory memory;
  private final ArrayList<@Nullable Object> objects = new ArrayList<>();
  private final Reference2LongOpenHashMap<Object> identities = new Reference2LongOpenHashMap<>();
  private long[] owners = new long[INITIAL_CAPACITY], members = new long[INITIAL_CAPACITY];
  private long serial;

  /**
   * Creates an external heap sharing the internal heap's memory and arena.
   *
   * <p>External handles are negative and remain valid only while this heap is
   * alive. The supplied internal heap is borrowed and must outlive this
   * instance.</p>
   *
   * @param internal internal ELX heap whose memory and strings back this heap
   */
  protected ExtHeap(ElxHeap internal) {
    this.internal = internal;
    this.memory = internal.memory();
  }

  /**
   * Returns the shared raw memory used for slot conversion.
   *
   * @return borrowed internal memory
   */
  protected final Memory memory() {
    return memory;
  }

  /**
   * Decodes an ELX string pointer used as a host member name.
   *
   * @param ptr ELX string pointer
   * @return decoded member name
   * @throws IllegalArgumentException if the pointer is not a string
   * @throws IllegalStateException if the pointer is dead or the internal heap is closed
   */
  protected final String memberName(long ptr) {
    return internal.exportString(ptr);
  }

  /**
   * Adds or reuses a host object handle using reference identity semantics.
   *
   * <p>Repeated calls for the same Java object return the same external handle;
   * equality is deliberately not based on {@link Object#equals(Object)}.</p>
   *
   * @param value host object to expose
   * @return negative external handle
   * @throws NullPointerException if {@code value} is null
   * @throws IllegalStateException if this heap is closed
   */
  public final long add(Object value) {
    checkOpen();
    long previous = identities.getLong(value);
    if (previous != 0) {
      return previous;
    }
    long ptr = Long.MIN_VALUE | ++serial;
    int at = register(ptr);
    while (objects.size() <= at) {
      objects.add(null);
    }
    objects.set(at, value);
    owners[at] = 0;
    members[at] = 0;
    identities.put(value, ptr);
    return ptr;
  }

  /**
   * Creates a GC-visible field location; no raw Java address is exposed.
   *
   * <p>The returned handle keeps both the host object and member-name string
   * reachable while it is retained. A field location cannot itself be used as
   * the owner of another field location.</p>
   *
   * @param object external object handle
   * @param memberNamePtr ELX string pointer naming the member
   * @return negative field-location handle
   * @throws IllegalArgumentException if {@code object} is already a field handle
   * @throws IllegalStateException if either handle is dead or this heap is closed
   */
  public final long naddress(long object, long memberNamePtr) {
    int owner = entry(object);
    if (owners[owner] != 0) {
      throw new IllegalArgumentException("Dereference a field location before taking a nested field address");
    }
    long ptr = Long.MIN_VALUE | ++serial;
    int at = register(ptr);
    while (objects.size() <= at) {
      objects.add(null);
    }
    objects.set(at, null);
    owners[at] = object;
    members[at] = memberNamePtr;
    return ptr;
  }

  /**
   * Reads a host member or dereferences a field location.
   *
   * @param ptr external object or field handle
   * @param memberNamePtr imported member-name string, or zero to dereference a field handle
   * @param result destination slot
   * @throws IllegalArgumentException if a field handle is given a second member name
   * @throws IllegalStateException if a handle is dead or the heap is closed
   */
  public final void nget(long ptr, long memberNamePtr, long result) {
    int at = entry(ptr);
    if (owners[at] != 0) {
      if (memberNamePtr != 0) {
        throw new IllegalArgumentException("A field pointer must be dereferenced before further member access");
      }
      memberNamePtr = members[at];
      at = entry(owners[at]);
    }
    readField(object(at), memberNamePtr, result);
  }

  /**
   * Writes a host member or dereferences a previously created field location.
   *
   * @param ptr external object or field handle
   * @param memberNamePtr imported member-name string, or zero to dereference a field handle
   * @param value source slot
   * @throws IllegalArgumentException if a field handle is given a second member name
   * @throws IllegalStateException if a handle is dead or the heap is closed
   */
  public final void nset(long ptr, long memberNamePtr, long value) {
    int at = entry(ptr);
    if (owners[at] != 0) {
      if (memberNamePtr != 0) {
        throw new IllegalArgumentException("A field pointer must be dereferenced before further member access");
      }
      memberNamePtr = members[at];
      at = entry(owners[at]);
    }
    writeField(object(at), memberNamePtr, value);
  }

  /**
   * Invokes a host member callback using the VM slot-window ABI.
   *
   * @param ptr external object or field handle
   * @param memberNamePtr imported member-name string, or zero for a field handle
   * @param args input slot window
   * @param argCount number of input slots
   * @param results output slot window
   * @param resultCapacity available output slots
   * @return number of values produced
   * @throws IllegalArgumentException if a field handle is given a second member name,
   *                                  or a window count is negative/out of range
   * @throws IllegalStateException if a handle is dead, the provider exceeds the
   *                               result capacity, or the heap is closed
   */
  public final int ncall(long ptr, long memberNamePtr, long args, int argCount, long results, int resultCapacity) {
    if (argCount < 0 || resultCapacity < 0 || resultCapacity > 4) {
      throw new IllegalArgumentException("Invalid external call window");
    }
    int at = entry(ptr);
    if (owners[at] != 0) {
      if (memberNamePtr != 0) {
        throw new IllegalArgumentException("A callable field pointer already identifies its member");
      }
      memberNamePtr = members[at];
      at = entry(owners[at]);
    }
    Object target = object(at);
    int count = target instanceof BoundCall function ? function.invoke(memory, args, argCount, results, resultCapacity) : invoke(target, memberNamePtr, args, argCount, results, resultCapacity);
    if (count > resultCapacity) {
      throw new IllegalStateException("Provider returned more values than requested: " + count);
    }
    return count;
  }

  /**
   * Reads a host member into an ELX slot.
   *
   * @param target host object
   * @param memberNamePtr ELX string pointer naming the member
   * @param result destination slot address
   */
  protected abstract void readField(Object target, long memberNamePtr, long result);

  /**
   * Writes an ELX slot into a host member.
   *
   * @param target host object
   * @param memberNamePtr ELX string pointer naming the member
   * @param value source slot address
   */
  protected abstract void writeField(Object target, long memberNamePtr, long value);

  /**
   * Invokes a host member using raw slot windows.
   *
   * @param target host object
   * @param memberNamePtr ELX string pointer naming the member
   * @param args argument-window base address
   * @param argCount number of argument slots
   * @param results result-window base address
   * @param resultCapacity result-window capacity
   * @return number of result slots written
   */
  protected abstract int invoke(Object target, long memberNamePtr, long args, int argCount, long results, int resultCapacity);

  /**
   * Visits ELX references retained by a host resource.
   *
   * <p>Java object graphs are intentionally not scanned reflectively; a
   * provider must override this hook when its resource stores ELX handles.</p>
   *
   * @param target host resource being traced
   * @param visitor callback for each retained ELX reference
   */
  protected void traceObject(Object target, HeapVisitor visitor) {
  }

  /**
   * Releases provider-owned resources for one external object.
   *
   * <p>The default implementation only drops the heap's handle. Providers
   * may override it when disposing a handle must close an underlying resource.</p>
   *
   * @param target host resource being disposed
   */
  protected void disposeObject(Object target) {
  }

  private Object object(int index) {
    @Nullable Object object = objects.get(index);
    if (object == null) {
      throw new IllegalStateException("Field descriptor is not a host object");
    }
    return object;
  }

  @Override
  protected long base(long ptr) {
    return ptr;
  }

  @Override
  protected void grow(int capacity) {
    owners = Arrays.copyOf(owners, capacity);
    members = Arrays.copyOf(members, capacity);
  }

  @Override
  protected void dispose(int index, long ptr) {
    if (owners[index] == 0) {
      Object object = object(index);
      identities.removeLong(object);
      if (!(object instanceof BoundCall)) {
        disposeObject(object);
      }
    }
    objects.set(index, null);
    owners[index] = 0;
    members[index] = 0;
  }

  @Override
  protected Type rootType(int index) {
    return Type.EXTERNAL;
  }

  @Override
  public void trace(long ptr, HeapVisitor visitor) {
    int at = entry(ptr);
    if (owners[at] == 0) {
      Object target = object(at);
      if (target instanceof BoundCall function) {
        function.trace(visitor);
      } else {
        traceObject(target, visitor);
      }
    } else {
      visitor.visit(Type.EXTERNAL, owners[at]);
      visitor.visit(Type.STRING, members[at]);
    }
  }

  /**
   * Exports an external handle or dereferenced field as a Java value.
   *
   * <p>Field handles are read through a temporary slot and therefore do not
   * expose a Java address. The returned object is borrowed from the provider.</p>
   *
   * @param ptr external object or field handle
   * @return host object, or the value read from a field handle
   * @throws IllegalStateException if the handle is dead or the heap is closed
   */
  @Override
  public @Nullable Object exportJava(long ptr) {
    int at = entry(ptr);
    if (owners[at] == 0) {
      return object(at);
    }
    long slot = internal.arena().allocate(Slot.SIZE);
    try {
      nget(ptr, 0, slot);
      return exportValue(slot);
    } finally {
      internal.arena().free(slot);
    }
  }

  /**
   * Converts one slot value to a Java host value at the explicit boundary.
   *
   * @param address source slot
   * @return exported Java value, possibly {@code null}
   * @throws IllegalArgumentException if the slot contains an unsupported type
   * @throws IllegalStateException if a referenced handle is dead
   */
  public final @Nullable Object exportValue(long address) {
    Type type = Slot.type(memory, address);
    long bits = Slot.bits(memory, address);
    if (type == Type.PTR) {
      return bits;
    }
    if (type == Type.NULL) {
      return null;
    }
    if (type == Type.BOOL) {
      return bits != 0;
    }
    if (type == Type.FLOAT) {
      return Float.intBitsToFloat((int) bits);
    }
    if (type == Type.DOUBLE) {
      return Double.longBitsToDouble(bits);
    }
    if (type.integer()) {
      return Type.normalize(bits, type);
    }
    if (type == Type.STRING) {
      return internal.exportString(bits);
    }
    if (type == Type.EXTERNAL) {
      return exportJava(bits);
    }
    throw new UnsupportedOperationException("Java export of " + type + " is not implemented");
  }

  /**
   * Converts a Java value into one VM slot, allocating external/string handles as needed.
   *
   * @param destination destination slot
   * @param value Java value, or {@code null}
   * @throws IllegalStateException if this heap or its internal heap is closed
   */
  public final void importJava(long destination, @Nullable Object value) {
    if (value == null) {
      Slot.write(memory, destination, Type.NULL, 0);
    } else if (value instanceof Boolean b) {
      Slot.write(memory, destination, Type.BOOL, b ? 1 : 0);
    } else if (value instanceof Byte b) {
      Slot.write(memory, destination, Type.BYTE, b.longValue());
    } else if (value instanceof Short s) {
      Slot.write(memory, destination, Type.SHORT, s.longValue());
    } else if (value instanceof Integer i) {
      Slot.write(memory, destination, Type.INT, i.longValue());
    } else if (value instanceof Long l) {
      Slot.write(memory, destination, Type.LONG, l);
    } else if (value instanceof Float f) {
      Slot.write(memory, destination, Type.FLOAT, Integer.toUnsignedLong(Float.floatToRawIntBits(f)));
    } else if (value instanceof Double d) {
      Slot.write(memory, destination, Type.DOUBLE, Double.doubleToRawLongBits(d));
    } else if (value instanceof String s) {
      Slot.write(memory, destination, Type.STRING, internal.importString(s));
    } else {
      Slot.write(memory, destination, Type.EXTERNAL, add(value));
    }
  }

  /** VM-bound callback ABI used only by the linker and borrowed external-call window. */
  @FunctionalInterface
  public interface BoundCall {
    /**
     * Invokes the callback with raw argument/result slot addresses.
     *
     * @param memory shared VM memory
     * @param args input slot window
     * @param count input slot count
     * @param results output slot window
     * @param capacity output capacity
     * @return number of values produced
     */
    int invoke(Memory memory, long args, int count, long results, int capacity);

    /**
     * Visits ELX references retained by the callback.
     *
     * @param visitor reference visitor
     */
    default void trace(HeapVisitor visitor) {
    }
  }
}
