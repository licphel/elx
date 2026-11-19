package io.viki.elx.vm;

import io.viki.elx.Type;
import io.viki.elx.asm.Op;
import io.viki.elx.asm.Instr;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lib.*;
import io.viki.elx.memory.*;
import io.viki.elx.value.*;
import java.util.ConcurrentModificationException;

/** Container invariants, borrowed windows, mixed comparisons and host/script equivalence. */
final class AllOpTests {
  private static int checks;
  private AllOpTests() {}
  static int checks(){return checks;}
  static void run(VM vm,ElxHeap heap,ExtHeap host) {
    try(Slots.Scope values=vm.borrowSlots(4);Slots.Scope key=vm.borrowSlots(2)) {
      ContOp containers=vm.containers();
      containers.newTable(values,0,0);containers.newList(values,1,0);
      for(int i=0;i<1000;i++) {
        key.setLong(0,i);key.setLong(1,i*3L);
        containers.tableSet(values,0,key,0,key,1);containers.listAppend(values,1,key,0);
      }
      eq(containers.tableSize(values,0),1000);eq(containers.listSize(values,1),1000);
      for(int i=0;i<1000;i++) {
        key.setDouble(0,i);
        yes(containers.tableGet(values,0,key,0,values,2));eq(values.i(2),i*3L);
        containers.listGet(values,1,i,values,2);eq(values.i(2),i);
      }
      key.setNull(0);key.setNull(1);containers.tableSet(values,0,key,0,key,1);
      yes(containers.tableGet(values,0,key,0,values,2));yes(values.type(2)==Type.NULL);
      try(ContOp.TableCursor cursor=containers.tableCursor(values,0)) {
        yes(cursor.next());cursor.key(key,0);key.setInt(1,17);cursor.setValue(key,1);
        containers.tableGet(values,0,key,0,values,2);eq(values.i(2),17);
        vm.collect(true);cursor.value(values,2);eq(values.i(2),17);
        key.setString(0,"new-key");containers.tableSet(values,0,key,0,key,1);
        try{cursor.next();throw new AssertionError("Structural change accepted");}
        catch(ConcurrentModificationException expected){checks++;}
      }
      key.setLong(0,99);containers.listSet(values,1,0,key,0);
      containers.listGet(values,1,0,values,2);eq(values.i(2),99);
      containers.tableClear(values,0);eq(containers.tableSize(values,0),0);
      // Mixed integer/float comparison must not round a large integer first.
      values.setLong(2,9007199254740993L);values.setDouble(3,9007199254740992d);
      yes(vm.operations().test(Instr.Relation.GT,values,2,values,3));
      yes(!vm.operations().test(Instr.Relation.EQ,values,2,values,3));
      values.set(2,Type.ULONG,-1);values.setDouble(3,0x1p64);
      yes(vm.operations().test(Instr.Relation.LT,values,2,values,3));
      values.setDouble(2,Double.NaN);yes(!vm.operations().test(Instr.Relation.LE,values,2,values,3));
    }
    Compiler compiler=new Compiler();
    String[] scripts={
      "local a=1;local b=2;local list=[\na,\nb\n];return list[0]+list[1]",
      "local _value=3;return _value",
      "local t={};setmetatable(t,{['add']=function(a,b) {return b*3}});return t+1",
      "local t={};setmetatable(t,{get=function(self,key) {require('gc').collect();return key}});return len(t.abc)",
      "local n=[0];local function f(x) {n[0]+=x;return n[0]};return f(1)+f(1)"
    };
    for(String source:scripts){vm.execute(compiler.compile(source));eq(Slot.bits(vm.memory(),vm.results()),3);}
    SimpleLibrary library=new SimpleLibrary().function("host",call->{
      Span out=call.scratch(1);call.operations().binary(Op.ADD,call,0,call,1,out,0);
      call.rvalue(out,0);
    });
    vm.execute(compiler.compile("local t={};setmetatable(t,{['add']=function(a,b) {require('gc').collect();return b*3}});return host(t,7)",library));
    eq(Slot.bits(vm.memory(),vm.results()),21);
    vm.execute(compiler.compile("local t={};setmetatable(t,{['add']=function(a,b) {return b*3}});return t+7"));
    eq(Slot.bits(vm.memory(),vm.results()),21);
    System.out.println("ELX container/operator contracts: "+checks+" checks");
  }
  private static void eq(long a,long b){checks++;if(a!=b)throw new AssertionError(a+" != "+b);}
  private static void yes(boolean value){checks++;if(!value)throw new AssertionError("Contract check "+checks);}
}
