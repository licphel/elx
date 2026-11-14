package io.viki.elx;

import io.viki.elx.Type;
import io.viki.elx.asm.DecomposeRules;
import io.viki.elx.asm.Op;
import io.viki.elx.lex.NumericLiteral;
import io.viki.elx.lex.Token;
import io.viki.elx.memory.*;
import org.jspecify.annotations.Nullable;
import io.viki.elx.asm.*;

import java.util.Objects;

/** Executable storage/GC contracts across Java and FFM implementations. */
public final class MemoryTests {
  private static int checks;
  public static void main(String[] args) {
    InstructionTests.run();
    for(Type type:Type.values())equal(Type.fromTag(type.tag()),type);
    equal(Type.binary(Type.SHORT,Type.SHORT),Type.SHORT);
    equal(Type.binary(Type.LONG,Type.FLOAT),Type.DOUBLE);
    equal(Type.cast(Double.doubleToRawLongBits(1.0),Type.DOUBLE,Type.BOOL),1L);
    for(Op op:Op.values())equal(op.integrated(),!DecomposeRules.get(op).isEmpty());
    for(var entry: Token.FIXED.entrySet())equal(Token.matchFixed(entry.getKey(),0),entry.getValue());
    equal(NumericLiteral.scan("1us",0).type(),Type.USHORT);
    exercise(new JavaMemory(16));
    exercise(new NativeMemory(16));
    System.out.println("ELX memory/heap/regs/pins: " + checks + " checks passed across both backends");
  }
  private static final class Box { long child; int number = 7; }
  private static final class Host extends ExtHeap {
    Host(ElxHeap internal) { super(internal); }
    @Override protected void readField(Object target, long member, long result) {
      Box box = (Box)target;
      switch(memberName(member)) {
        case "number" -> importJava(result, box.number);
        case "child" -> Slot.write(memory(),result,Type.PTR, box.child);
        case "empty" -> importJava(result, null);
        default -> throw new IllegalArgumentException("Unknown field");
      }
    }
    @Override protected void writeField(Object target, long member, long value) {
      if (!memberName(member).equals("number")) throw new IllegalArgumentException("Unknown writable field");
      ((Box)target).number = Slot.getInt(memory(),value);
    }
    @Override protected int invoke(Object target, long member, long args, int count, long result, int capacity) {
      if (!memberName(member).equals("add") || count != 2 || capacity < 1) return -1;
      long a = Slot.getLong(memory(),args), b = Slot.getLong(memory(),Slot.address(args, 1));
      Slot.write(memory(),result, Type.LONG, a + b); return 1;
    }
    @Override protected void traceObject(Object target, HeapVisitor visitor) { visitor.visit(Type.PTR, ((Box)target).child); }
  }
  private static void exercise(Memory memory) {
    Arena arena = new Arena(memory);
    try (memory; ElxHeap heap = new ElxHeap(arena); Host host = new Host(heap); JavaRegs regs = new JavaRegs(arena)) {
      allocationStress(arena);
      bulkAllocationStress(arena);
      long bytes = arena.allocate(63);
      for(int i=0;i<63;i++)memory.store8(bytes+i,(byte)i);
      memory.store64(bytes+3,0x1122334455667788L);
      equal(memory.load64(bytes+3),0x1122334455667788L);
      long growth = arena.allocate(16384);
      equal(memory.load64(bytes+3),0x1122334455667788L);
      for(int i=0;i<40;i++)memory.store8(bytes+i,(byte)i);
      memory.copyBytes(bytes+3,bytes,32);
      for(int i=0;i<32;i++)equal(memory.load8(bytes+3+i),(byte)i);
      memory.copyBytes(bytes,bytes+3,32);
      for(int i=0;i<32;i++)equal(memory.load8(bytes+i),(byte)i);
      arena.free(growth);arena.free(bytes);
      expect(IllegalArgumentException.class,()->arena.free(bytes));
      long reused=arena.allocate(63);equal(reused,bytes);equal(memory.load64(reused),0L);arena.free(reused);

      long frame=regs.enter(8);
      equal(Slot.SIZE,16);equal(Slot.address(frame,1)-frame,16L);
      equal((Slot.address(frame,1)+Slot.VALUE_OFFSET)&7,0L);
      Slot.write(memory,frame,Type.ULONG,(byte)0xA5,0xFEDCBA9876543210L);
      equal(memory.load8(frame),Type.ULONG.tag());equal(memory.load8(frame+1),(byte)0xA5);
      equal(memory.load64(frame+Slot.VALUE_OFFSET),0xFEDCBA9876543210L);
      Slot.copy(memory,Slot.address(frame,1),frame,1);
      equal(Slot.type(memory,Slot.address(frame,1)),Type.ULONG);
      equal(Slot.flags(memory,Slot.address(frame,1)),(byte)0xA5);
      equal(Slot.bits(memory,Slot.address(frame,1)),0xFEDCBA9876543210L);
      Slot.write(memory,Slot.address(frame,1),Type.NULL,0);
      equal(regs.memory()==memory,true);
      Slot.write(memory,frame,Type.INT,123);
      regs.enter(frame,2);equal(regs.base(),frame);
      Slot.write(memory,regs.address(0),Type.INT,456);regs.leave();equal(Slot.getInt(memory,frame),456);
      long firstWindow=regs.enter(3);regs.leave();equal(regs.enter(3),firstWindow);regs.leave();
      equal(regs.depth(),1);
      expect(IllegalStateException.class,regs::close);
      Gc gc=new Gc(heap,host,regs);
      long numberNotPointer=heap.allocate(ElxHeap.Layout.BYTES,8);
      Slot.write(memory,frame,Type.LONG,(byte)0xFF,numberNotPointer);
      gc.collect(true);equal(heap.isAlive(numberNotPointer),false);
      Slot.write(memory,frame,Type.NULL,0);
      expect(IllegalArgumentException.class,()->Type.cast(42,Type.LONG,Type.PTR));
      expect(IllegalArgumentException.class,()->Type.cast(42,Type.PTR,Type.LONG));
      long text=heap.importString("hello 世界\0!");equal(heap.exportString(text),"hello 世界\0!");
      Slot.write(memory,frame,Type.STRING,text);gc.collect(false);equal(heap.isAlive(text),true);
      Slot.write(memory,frame,Type.NULL,0);gc.collect(false);equal(heap.isAlive(text),false);

      long pinned=heap.allocate(ElxHeap.Layout.VALUES,2);
      try(var pin=heap.pin(pinned, PinMode.STRONG)) {
        expect(IllegalStateException.class,()->heap.release(pinned));
        gc.collect(true);equal(pin.isAlive(),true);equal(pin.get(),pinned);
      }
      gc.collect(true);equal(heap.isAlive(pinned),false);
      long weak=heap.allocate(ElxHeap.Layout.BYTES,13);
      try(var pin=heap.pin(weak,PinMode.WEAK)) {
        gc.collect(false);equal(pin.isAlive(),true);gc.collect(true);equal(pin.isAlive(),false);
        expect(IllegalStateException.class,pin::get);
      }
      long stale=heap.allocate(ElxHeap.Layout.BYTES,13);
      try(var pin=heap.pin(stale,PinMode.NEVER)) {
        gc.collect(true);equal(pin.isAlive(),false);
        long replacement=heap.allocate(ElxHeap.Layout.BYTES,13);equal(replacement,stale);equal(pin.isAlive(),false);
      }
      long held=heap.allocate(ElxHeap.Layout.BYTES,9);heap.retain(held);gc.collect(true);equal(heap.isAlive(held),true);
      heap.release(held);gc.collect(true);equal(heap.isAlive(held),false);

      Box box=new Box();long object=host.add(box);equal(host.add(box),object);
      long name=heap.importString("number"),add=heap.importString("add");
      long field=host.naddress(object,name);
      Slot.write(memory,frame,Type.PTR,field);
      Slot.copy(memory,regs.address(5), frame, 1); Slot.write(memory,frame,Type.NULL,0);
      gc.collect(true);equal(host.isAlive(object),true);equal(heap.isAlive(name),true);
      equal(Slot.isReference(memory,regs.address(5)),true);
      Slot.copy(memory,frame,regs.address(5),1);Slot.write(memory,regs.address(5),Type.NULL,0);
      host.nget(field,0,regs.address(1));equal(Slot.getLong(memory,regs.address(1)),7L);
      Slot.write(memory,regs.address(1),Type.INT,31);host.nset(field,0,regs.address(1));equal(box.number,31);
      equal(host.exportJava(field),31L);
      try(var pin=host.pin(object,PinMode.STRONG)){equal(pin.get()==box,true);gc.collect(true);}
      // The add name needs its own root; it is not referenced by the number-field descriptor.
      add=heap.importString("add");Slot.write(memory,regs.address(2),Type.STRING,add);
      Slot.write(memory,regs.address(3),Type.LONG,10);Slot.write(memory,regs.address(4),Type.LONG,20);
      equal(host.ncall(object,add,regs.address(3),2,regs.address(3),1),1);equal(Slot.getLong(memory,regs.address(3)),30L);
      equal(host.ncall(object,add,regs.address(3),2,regs.address(3),0),-1);

      long node=heap.allocate(ElxHeap.Layout.VALUES,1);box.child=node;
      Slot.write(memory,node,Type.EXTERNAL,object);gc.collect(true);equal(heap.isAlive(node),true);
      // Only the field pointer is rooted, reaching object -> node -> object across both heaps.
      Slot.write(memory,frame,Type.NULL,0);Slot.write(memory,regs.address(2),Type.NULL,0);gc.collect(true);
      equal(host.isAlive(field),false);equal(host.isAlive(object),false);equal(heap.isAlive(node),false);
      long nullName=heap.importString("empty"),again=host.add(new Box()),nullable=host.naddress(again,nullName);
      equal(host.exportJava(nullable),null);
      long many=heap.allocate(ElxHeap.Layout.VALUES,300);
      for(int i=0;i<300;i++)Slot.write(memory,Slot.address(many,i),Type.STRING,heap.importString("root"+i));
      Slot.write(memory,frame,Type.PTR,many);gc.collect(true);
      for(int i=0;i<300;i++)equal(heap.isAlive(Slot.bits(memory,Slot.address(many,i))),true);
      Slot.write(memory,frame,Type.NULL,0);gc.collect(true);equal(heap.isAlive(many),false);
      regs.leave();equal(regs.depth(),0);
      expect(IllegalStateException.class,regs::leave);
      gc.collect(true);
    }
    expect(RuntimeException.class,()->memory.load8(16));
  }
  private static void allocationStress(Arena arena) {
    Memory memory = arena.memory();
    long[] addresses=new long[64];int[] sizes=new int[64],values=new int[64];
    long random=17;
    for(int round=0;round<1500;round++) {
      random=random*6364136223846793005L+1442695040888963407L;
      int slot=(int)(random>>>32)&63;
      if(addresses[slot]!=0){arena.free(addresses[slot]);addresses[slot]=0;}
      else {
        int size=16+((int)(random>>>16)&2047);long pointer=arena.allocate(size);
        addresses[slot]=pointer;sizes[slot]=size;values[slot]=round;
        memory.store32(pointer,round);memory.store32(pointer+size-4,round);
      }
      for(int i=0;i<64;i++)if(addresses[i]!=0){
        equal(memory.load32(addresses[i]),values[i]);equal(memory.load32(addresses[i]+sizes[i]-4),values[i]);
        equal(arena.owner(addresses[i]),addresses[i]);equal(arena.owner(addresses[i]+sizes[i]-1),addresses[i]);
      }
    }
    for(long pointer:addresses)if(pointer!=0)arena.free(pointer);
  }
  private static void bulkAllocationStress(Arena arena) {
    long[] blocks=new long[4096];
    for(int round=0;round<3;round++) {
      for(int i=0;i<blocks.length;i++) {
        blocks[i]=arena.allocate(16 << (i%5));
        equal(arena.owner(blocks[i]+(16 << (i%5))-1),blocks[i]);
      }
      // Removing interleaved nodes must preserve predecessor lookup for every surviving extent.
      for(int i=0;i<blocks.length;i+=2)arena.free(blocks[i]);
      for(int i=0;i<blocks.length;i++) {
        long address=blocks[i];
        if((i&1)==0) {
          expect(IllegalArgumentException.class,()->arena.owner(address));
          expect(IllegalArgumentException.class,()->arena.free(address));
        } else equal(arena.owner(address+7),address);
      }
      for(int i=blocks.length-1;i>=0;i--)if((i&1)!=0)arena.free(blocks[i]);
    }
  }
  private static void equal(@Nullable Object actual,@Nullable Object expected){checks++;if(!Objects.equals(actual,expected))throw new AssertionError(actual+" != "+expected);}
  private static void expect(Class<? extends Throwable> type,Runnable task){checks++;try{task.run();}catch(Throwable e){if(type.isInstance(e))return;throw new AssertionError("Expected "+type,e);}throw new AssertionError("Expected "+type);}
}
