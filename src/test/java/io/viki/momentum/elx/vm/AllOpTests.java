package io.viki.elx.vm;

import io.viki.elx.asm.Assembler;
import io.viki.elx.asm.Instr;
import io.viki.elx.asm.Op;
import io.viki.elx.asm.Prototype;
import io.viki.elx.memory.ElxHeap;
import io.viki.elx.memory.ExtHeap;
import io.viki.elx.memory.Memory;
import io.viki.elx.memory.Slot;
import io.viki.elx.value.ElxList;
import io.viki.elx.value.ElxTable;
import io.viki.elx.value.ValueOperations;
import io.viki.elx.vm.VM;

import io.viki.elx.Type;

/** Value-library and remaining opcode contracts, exercised on both storage backends. */
final class AllOpTests {
  private static int checks;
  private AllOpTests() { }
  static void run(VM vm, ElxHeap heap, ExtHeap host){
    int before=checks;
    Memory m=vm.memory();long args=heap.arena().allocate(8* Slot.SIZE);
    try{
      integers(vm);
      floats(vm);
      Assembler a=new Assembler();int end=a.label();
      a.r(Op.NOP,0,0,0).constant(0,a.constant(Type.BOOL,1)).r(Op.MOVE,1,0,0).branch(Op.CX_BR_TRUE,1,end).immediate(Op.TRAP,0,0,1).bind(end).r(Op.CX_RETURN,1,1,0);
      vm.execute(a.build(2,0));eq(bits(vm),1);
      for(Op op:new Op[]{Op.ICMP,Op.UCMP}){
        a=new Assembler();a.constant(0,a.constant(Type.LONG,-1)).constant(1,a.constant(Type.LONG,1)).rx(op,2,0,1, Instr.control(Type.LONG,Instr.Relation.LT)).r(Op.CX_RETURN,2,1,0);
        vm.execute(a.build(3,0));eq(bits(vm),op==Op.ICMP?1:0);
      }
      long raw=heap.allocate(ElxHeap.Layout.BYTES,32);
      Slot.write(m,args,Type.PTR,raw);Slot.write(m,args+Slot.SIZE,Type.LONG,8);
      a=new Assembler();a.r(Op.LEA,2,0,1).constant(3,a.constant(Type.LONG,0x12345678)).immediate(Op.STORE16,3,2,0).immediate(Op.LOAD16,4,0,8).immediate(Op.STORE32,3,2,4).immediate(Op.LOAD32,5,0,12).r(Op.EQ64,6,0,2).r(Op.CX_RETURN,4,3,0);
      vm.execute(a.build(7,2),args,2);eq(bits(vm),0x5678);eq(Slot.bits(m,vm.results()+Slot.SIZE),0x12345678);eq(Slot.bits(m,vm.results()+2L*Slot.SIZE),0);
      m.store64(raw,71);m.store64(raw+8,99);
      Slot.write(m,args,Type.PTR,raw);Slot.write(m,args+Slot.SIZE,Type.LONG,1);
      a=new Assembler();a.r(Op.CX_LOAD64_INDEXED,2,0,1).r(Op.CX_RETURN,2,1,0);vm.execute(a.build(3,2),args,2);eq(bits(vm),99);
      Slot.write(m,args,Type.PTR,raw+4);Slot.write(m,args+Slot.SIZE,Type.PTR,raw);Slot.write(m,args+2L*Slot.SIZE,Type.LONG,16);
      a=new Assembler();a.r(Op.CX_COPY_MEMORY,0,1,2).immediate(Op.LOAD64,3,0,0).r(Op.CX_RETURN,3,1,0);vm.execute(a.build(4,3),args,3);eq(bits(vm),71);eq(m.load64(raw+12),99);

      long table= ElxTable.create(vm.heap()),list= ElxList.create(vm.heap());
      // Force both backings to grow, including collisions and numeric key normalization.
      for(int i=0;i<1000;i++){
        Slot.write(m,args,Type.LONG,i);Slot.write(m,args+Slot.SIZE,Type.LONG,i*3L);
        ElxTable.set(vm.heap(), table,args,args+Slot.SIZE);ElxList.append(vm.heap(), list,args);
      }
      eq(Slot.getInt(vm.memory(), table),1000);eq(Slot.getInt(vm.memory(), list),1000);
      for(int i=0;i<1000;i++){
        Slot.write(m,args,Type.DOUBLE,Double.doubleToRawLongBits(i));ElxTable.get(vm.memory(), table,args,args+Slot.SIZE);
        eq(Slot.bits(m,args+Slot.SIZE),i*3L);ElxList.get(vm.memory(), list,i,args+Slot.SIZE);eq(Slot.bits(m,args+Slot.SIZE),i);
      }
      Slot.write(m,args,Type.NULL,0);Slot.write(m,args+Slot.SIZE,Type.LONG,123);ElxTable.set(vm.heap(), table,args,args+Slot.SIZE);
      ElxTable.get(vm.memory(), table,args,args);eq(Slot.bits(m,args),123);
      Slot.write(m,args,Type.LONG,10001);ElxTable.get(vm.memory(), table,args,args);eq(Slot.type(m,args),Type.NULL);
      Slot.write(m,args,Type.TABLE,table);Slot.write(m,args+Slot.SIZE,Type.LONG,1001);Slot.write(m,args+2L*Slot.SIZE,Type.LONG,81);
      a=new Assembler();a.r(Op.CX_SET_TABLE,0,1,2).r(Op.CX_GET_TABLE,3,0,1).r(Op.CX_RETURN,3,1,0);vm.execute(a.build(4,3),args,3);eq(bits(vm),81);
      // The descriptor, backing storage, string key/value and aliases survive collection.
      long key=heap.importString("key"),text=heap.importString("你好");
      Slot.write(m,args+Slot.SIZE,Type.STRING,key);Slot.write(m,args+2L*Slot.SIZE,Type.STRING,text);
      vm.execute(a.build(4,3),args,3);
      a=new Assembler();a.r(Op.CX_SAFEPOINT,0,0,0).r(Op.CX_GET_TABLE,2,0,1).r(Op.CX_RETURN,2,1,0);
      vm.requestGc(true);vm.execute(a.build(3,2),args,2);eq(heap.exportString(bits(vm)),"你好");
      // list wasn't a root during that collection; create a fresh one.
      list=ElxList.create(vm.heap());Slot.write(m,args,Type.LIST,list);Slot.write(m,args+Slot.SIZE,Type.INT,42);
      a=new Assembler();a.r(Op.CX_LIST_APPEND,0,1,0).r(Op.CX_RETURN,0,1,0);vm.execute(a.build(2,2),args,2);eq(Slot.getInt(vm.memory(), list),1);
      Slot.write(m,args+Slot.SIZE,Type.LONG,0);
      a=new Assembler();a.r(Op.CX_GET_LIST_IV,2,0,1).r(Op.CX_RETURN,2,3,0);vm.execute(a.build(5,2),args,2);
      eq(bits(vm),0);eq(Slot.bits(m,vm.results()+Slot.SIZE),42);eq(Slot.bits(m,vm.results()+2L*Slot.SIZE),1);
      Slot.write(m,args+Slot.SIZE,Type.LONG,1);vm.execute(a.build(5,2),args,2);eq(Slot.type(m,vm.results()),Type.NULL);eq(Slot.bits(m,vm.results()+2L*Slot.SIZE),-1);
      // Table cursor visits each bucket once; order is deliberately unspecified.
      Slot.write(m,args,Type.TABLE,table);long cursor=0;int seen=0;
      a=new Assembler();a.r(Op.CX_GET_TABLE_IKV,2,0,1).r(Op.CX_RETURN,2,4,0);
      Prototype iterator=a.build(6,2);
      for(;;){Slot.write(m,args+Slot.SIZE,Type.LONG,cursor);vm.execute(iterator,args,2);cursor=Slot.bits(m,vm.results()+3L*Slot.SIZE);if(cursor==-1)break;eq(bits(vm),seen);seen++;}
      eq(seen,Slot.getInt(vm.memory(), table));
      Slot.write(m,args+Slot.SIZE,Type.LONG,-1);vm.execute(iterator,args,2);eq(Slot.bits(m,vm.results()+3L*Slot.SIZE),-1);
      // Byte-identical strings have equal hashes and compare by contents, not addresses.
      Slot.write(m,args,Type.STRING,heap.importString("abc"));Slot.write(m,args+Slot.SIZE,Type.STRING,heap.importString("abc"));
      a=new Assembler();a.r(Op.CX_HASH,2,0,0).r(Op.CX_HASH,3,1,0).r(Op.CX_EQUAL,0,0,1).r(Op.CX_RETURN,0,4,0);
      vm.execute(a.build(4,2),args,2);eq(bits(vm),1);eq(Slot.bits(m,vm.results()+2L*Slot.SIZE),Slot.bits(m,vm.results()+3L*Slot.SIZE));
      dynamic(vm,heap,args);
      // Retain/release and zero-argument external call use the same host boundary.
      long object=host.add(new Object());Slot.write(m,args,Type.EXTERNAL,object);
      a=new Assembler();a.r(Op.CX_EX_RETAIN,0,0,0).r(Op.CX_EX_RELEASE,0,0,0).r(Op.CX_RETURN,0,1,0);vm.execute(a.build(1,1),args,1);eq(bits(vm),object);
      Slot.write(m,args+Slot.SIZE,Type.STRING,heap.importString("zero"));
      a=new Assembler();a.r(Op.CX_EX_CALL0,0,0,1).r(Op.CX_RETURN,0,1,0);vm.execute(a.build(2,2),args,2);eq(bits(vm),42);
      System.out.println(m.getClass().getSimpleName()+": additional opcode/value checks: "+(checks-before));
    }finally{heap.arena().free(args);}
  }
  private static void integers(VM vm){
    for(Op op:new Op[]{Op.UDIV,Op.UREM,Op.UCMP}){
      Assembler a=new Assembler();a.constant(0,a.constant(Type.BYTE,-1)).constant(1,a.constant(Type.BYTE,2));
      a.rx(op,2,0,1,Instr.control(Type.BYTE,Instr.Relation.LT)).r(Op.CX_RETURN,2,1,0);
      vm.execute(a.build(3,0));eq(bits(vm),op==Op.UDIV?127:op==Op.UREM?1:0);
    }

    Op[] ops={Op.IADD,Op.ISUB,Op.IMUL,Op.IDIV,Op.UDIV,Op.IREM,Op.UREM,Op.BAND,Op.BOR,Op.BXOR,Op.SHL,Op.SHR,Op.USHR};
    long[] expected={15,9,36,4,4,0,0,0,15,15,96,1,1};
    for(Type type:new Type[]{Type.BYTE,Type.UBYTE,Type.SHORT,Type.USHORT,Type.INT,Type.UINT,Type.LONG,Type.ULONG}){
      for(int i=0;i<ops.length;i++){
        Assembler a=new Assembler();a.constant(0,a.constant(type,12)).constant(1,a.constant(type,3)).rx(ops[i],2,0,1,Instr.control(type)).r(Op.CX_RETURN,2,1,0);
        vm.execute(a.build(3,0));eq(bits(vm),expected[i]);eq(Slot.type(vm.memory(),vm.results()),type);
      }
      for(Op op:new Op[]{Op.INEG,Op.BNOT}){
        Assembler a=new Assembler();a.constant(0,a.constant(type,12)).rx(op,0,0,0,Instr.control(type)).r(Op.CX_RETURN,0,1,0);
        vm.execute(a.build(1,0));eq(bits(vm),Type.normalize(op==Op.INEG?-12:-13,type));
      }
    }
  }
  private static void floats(VM vm){
    Op[] ops={Op.FADD,Op.FSUB,Op.FMUL,Op.FDIV,Op.FREM};double[] expected={9,3,18,2,0};
    for(Type type:new Type[]{Type.FLOAT,Type.DOUBLE})for(int i=0;i<ops.length;i++){
      Assembler a=new Assembler();a.constant(0,a.constant(type,type==Type.FLOAT?Float.floatToRawIntBits(6):Double.doubleToRawLongBits(6)));
      a.constant(1,a.constant(type,type==Type.FLOAT?Float.floatToRawIntBits(3):Double.doubleToRawLongBits(3))).rx(ops[i],2,0,1,Instr.control(type)).r(Op.CX_RETURN,2,1,0);
      vm.execute(a.build(3,0));if(Type.asDouble(bits(vm),type)!=expected[i])throw new AssertionError(ops[i]);checks++;
    }
  }
  private static void dynamic(VM vm,ElxHeap heap,long args){
    Memory m=vm.memory();
    Slot.write(m,args,Type.STRING,heap.importString("ab"));Slot.write(m,args+Slot.SIZE,Type.STRING,heap.importString("cd"));
    operator(vm,args,Op.IADD);eq(heap.exportString(bits(vm)),"abcd");
    long left=ElxList.create(vm.heap()),right=ElxList.create(vm.heap());Slot.write(m,args,Type.INT,1);ElxList.append(vm.heap(), left,args);Slot.write(m,args,Type.INT,2);ElxList.append(vm.heap(), left,args);ElxList.append(vm.heap(), right,args);Slot.write(m,args,Type.INT,3);ElxList.append(vm.heap(), right,args);
    long[] expected={4,1,2,3,1};int i=0;
    for(Op op:new Op[]{Op.IADD,Op.ISUB,Op.BXOR,Op.BOR,Op.BAND}){
      Slot.write(m,args,Type.LIST,left);Slot.write(m,args+Slot.SIZE,Type.LIST,right);operator(vm,args,op);eq(Slot.getInt(vm.memory(), bits(vm)),expected[i++]);
    }
    Slot.write(m,args,Type.LIST,left);Slot.write(m,args+Slot.SIZE,Type.INT,5);operator(vm,args,Op.IMUL);eq(Slot.getInt(vm.memory(), bits(vm)),10);
    long x=ElxTable.create(vm.heap()),y=ElxTable.create(vm.heap());
    Slot.write(m,args,Type.INT,1);Slot.write(m,args+Slot.SIZE,Type.INT,11);ElxTable.set(vm.heap(), x,args,args+Slot.SIZE);
    Slot.write(m,args,Type.INT,2);ElxTable.set(vm.heap(), x,args,args+Slot.SIZE);ElxTable.set(vm.heap(), y,args,args+Slot.SIZE);
    Slot.write(m,args,Type.INT,3);ElxTable.set(vm.heap(), y,args,args+Slot.SIZE);
    i=0;long[] sizes={3,1,2,3,1};
    for(Op op:new Op[]{Op.IADD,Op.ISUB,Op.BXOR,Op.BOR,Op.BAND}){
      Slot.write(m,args,Type.TABLE,x);Slot.write(m,args+Slot.SIZE,Type.TABLE,y);operator(vm,args,op);eq(Slot.getInt(vm.memory(), bits(vm)),sizes[i++]);
    }
    // Independently built cyclic graphs compare without recursion overflow.
    long c=ElxList.create(vm.heap()),d=ElxList.create(vm.heap());Slot.write(m,args,Type.LIST,c);ElxList.append(vm.heap(), c,args);Slot.write(m,args+Slot.SIZE,Type.LIST,d);ElxList.append(vm.heap(), d,args+Slot.SIZE);
    eq(ValueOperations.equal(vm.memory(), args,args+Slot.SIZE),true);eq(ValueOperations.hash(vm.memory(), args),ValueOperations.hash(vm.memory(), args+Slot.SIZE));
    Slot.write(m,args,Type.LONG,9007199254740993L);Slot.write(m,args+Slot.SIZE,Type.DOUBLE,Double.doubleToRawLongBits(9007199254740992d));eq(ValueOperations.equal(vm.memory(), args,args+Slot.SIZE),false);
    Slot.write(m,args,Type.INT,4);Slot.write(m,args+Slot.SIZE,Type.DOUBLE,Double.doubleToRawLongBits(0.5));operator(vm,args,Op.IADD);eq(Type.asDouble(bits(vm),Slot.type(m,vm.results()))==4.5,true);
    Slot.write(m,args,Type.SHORT,12);Slot.write(m,args+Slot.SIZE,Type.NULL,0);
    operator(vm,args,Op.INEG);eq(bits(vm),-12);eq(Slot.type(m,vm.results()),Type.SHORT);
    operator(vm,args,Op.BNOT);eq(bits(vm),-13);
    Slot.write(m,args,Type.BOOL,1);operator(vm,args,Op.CX_BOOL_NOT);eq(bits(vm),0);
    Slot.write(m,args,Type.FLOAT,Float.floatToRawIntBits(0));operator(vm,args,Op.FNEG);eq(bits(vm),0x80000000L);
    long empty=ElxList.create(vm.heap());Slot.write(m,args,Type.LIST,empty);Slot.write(m,args+Slot.SIZE,Type.LONG,Long.MAX_VALUE);
    operator(vm,args,Op.IMUL);eq(Slot.getInt(vm.memory(), bits(vm)),0);
    Slot.write(m,args+Slot.SIZE,Type.LONG,-1);expect(IllegalArgumentException.class,()->operator(vm,args,Op.IMUL));
    Slot.write(m,args,Type.TABLE,x);Slot.write(m,args+Slot.SIZE,Type.STRING,heap.importString("invalid"));expect(IllegalArgumentException.class,()->operator(vm,args,Op.IADD));
    Slot.write(m,args,Type.DOUBLE,Double.doubleToRawLongBits(Double.NaN));expect(IllegalArgumentException.class,()->ElxTable.set(vm.heap(), x,args,args));
    Slot.write(m,args,Type.INVALID,0);expect(IllegalArgumentException.class,()->ElxTable.set(vm.heap(), x,args,args));
    Slot.write(m,args,Type.NULL,99);Slot.write(m,args+Slot.SIZE,Type.NULL,0);eq(ValueOperations.equal(vm.memory(), args,args+Slot.SIZE),true);eq(ValueOperations.hash(vm.memory(), args),ValueOperations.hash(vm.memory(), args+Slot.SIZE));
    Slot.write(m,args,Type.FLOAT,0x80000000L);Slot.write(m,args+Slot.SIZE,Type.DOUBLE,0);eq(ValueOperations.equal(vm.memory(), args,args+Slot.SIZE),true);eq(ValueOperations.hash(vm.memory(), args),ValueOperations.hash(vm.memory(), args+Slot.SIZE));
    long selfA=ElxTable.create(vm.heap()),selfB=ElxTable.create(vm.heap());
    Slot.write(m,args,Type.INT,1);Slot.write(m,args+Slot.SIZE,Type.TABLE,selfA);ElxTable.set(vm.heap(), selfA,args,args+Slot.SIZE);
    Slot.write(m,args+Slot.SIZE,Type.TABLE,selfB);ElxTable.set(vm.heap(), selfB,args,args+Slot.SIZE);
    Slot.write(m,args,Type.TABLE,selfA);eq(ValueOperations.equal(vm.memory(), args,args+Slot.SIZE),true);

  }
  private static void operator(VM vm,long args,Op operator){Assembler a=new Assembler();a.rx(Op.CX_OPERATOR,0,0,1,operator.ordinal()).r(Op.CX_RETURN,0,1,0);vm.execute(a.build(2,2),args,2);}
  private static void expect(Class<? extends Throwable> type,Runnable action){checks++;try{action.run();}catch(Throwable error){if(type.isInstance(error))return;throw new AssertionError("Expected "+type,error);}throw new AssertionError("Expected "+type);}
  static int checks(){return checks;}
  private static long bits(VM vm){return Slot.bits(vm.memory(),vm.results());}
  private static void eq(long actual,long expected){checks++;if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
  private static void eq(Object actual,Object expected){checks++;if(!actual.equals(expected))throw new AssertionError(actual+" != "+expected);}
}
