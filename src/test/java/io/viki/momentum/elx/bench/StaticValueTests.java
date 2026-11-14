package io.viki.elx.bench;

import io.viki.elx.Type;
import io.viki.elx.bench.Bench;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.ElxList;
import io.viki.elx.value.ElxTable;
import io.viki.elx.value.ValueOperations;

/** Static operations reuse comparison scratch across deep cycles, mismatches and independent heaps. */
public final class StaticValueTests {
  private StaticValueTests() {}
  public static void run(){
    int checks=0;
    for(boolean nativeMemory:new boolean[]{false,true})try(Bench.Runtime r=new Bench.Runtime(nativeMemory)){
      Memory memory=r.memory;
      ElxHeap heap=r.heap;long slots=heap.arena().allocate(4* Slot.SIZE);
      try {
        long[] a=graph(heap,slots,80,11),b=graph(heap,slots,80,12);
        Slot.write(memory,slots,Type.LIST,a[0]);Slot.write(memory,slots+Slot.SIZE,Type.LIST,b[0]);
        check(!ValueOperations.equal(memory,slots,slots+Slot.SIZE),"deep mismatch");checks++;
        Slot.write(memory,slots+2L*Slot.SIZE,Type.INT,11);
        ElxList.set(heap,b[1],1,slots+2L*Slot.SIZE);
        check(ValueOperations.equal(memory,slots,slots+Slot.SIZE),"resized comparison scratch");checks++;
        long table= ElxTable.create(heap);Slot.write(memory,slots+2L*Slot.SIZE,Type.INT,23);
        ElxTable.set(heap,table,slots,slots+2L*Slot.SIZE);ElxTable.get(memory,table,slots+Slot.SIZE,slots+3L*Slot.SIZE);
        check(Slot.getInt(memory,slots+3L*Slot.SIZE)==23,"cyclic structural key");checks++;
        check(ElxTable.remove(memory,table,slots+Slot.SIZE),"remove structural key");checks++;
        for(int round=0;round<50;round++){
          check(ValueOperations.equal(memory,slots,slots+Slot.SIZE),"comparison state leaked between calls");checks++;
        }
        long x=ElxTable.create(heap),y=ElxTable.create(heap);
        Slot.write(memory,slots,Type.TABLE,x);Slot.write(memory,slots+Slot.SIZE,Type.TABLE,y);
        ElxTable.set(heap,x,slots,slots+2L*Slot.SIZE);ElxTable.set(heap,y,slots+Slot.SIZE,slots+2L*Slot.SIZE);
        check(ValueOperations.equal(memory,slots,slots+Slot.SIZE),"table is its own key");checks++;
        // An invalid raw-memory read must unwind comparison bookkeeping before propagating the backend failure.
        long bad=ElxList.create(heap),good=ElxList.create(heap);
        Slot.write(memory,slots+2L*Slot.SIZE,Type.INT,1);ElxList.append(heap,bad,slots+2L*Slot.SIZE);ElxList.append(heap,good,slots+2L*Slot.SIZE);
        long backing=Slot.bits(memory,Slot.address(bad,2));
        Slot.write(memory,Slot.address(bad,2),Type.PTR,memory.capacity()+Slot.SIZE);
        Slot.write(memory,slots,Type.LIST,bad);Slot.write(memory,slots+Slot.SIZE,Type.LIST,good);
        boolean failed=false;
        try{ValueOperations.equal(memory,slots,slots+Slot.SIZE);}catch(IndexOutOfBoundsException error){failed=true;}
        finally{Slot.write(memory,Slot.address(bad,2),Type.PTR,backing);}
        check(failed,"invalid read was swallowed");checks++;
        check(ValueOperations.equal(memory,slots,slots+Slot.SIZE),"comparison state leaked after failure");checks++;
      } finally {heap.arena().free(slots);}
    }
    System.out.println("ELX static values: "+checks+" checks passed across both backends");
  }
  private static long[] graph(ElxHeap heap,long scratch,int depth,int value){
    Memory memory=heap.memory();long leaf=ElxList.create(heap);
    Slot.write(memory,scratch,Type.LIST,leaf);ElxList.append(heap,leaf,scratch);
    Slot.write(memory,scratch,Type.INT,value);ElxList.append(heap,leaf,scratch);
    long root=leaf;
    for(int i=0;i<depth;i++){long next=ElxList.create(heap);Slot.write(memory,scratch,Type.LIST,root);ElxList.append(heap,next,scratch);root=next;}
    return new long[]{root,leaf};
  }
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
