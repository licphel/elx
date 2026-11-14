package io.viki.elx.asm;

import io.viki.elx.Type;
import io.viki.elx.asm.*;

import java.util.Objects;

/** Compiler-side encoding, relocation and image verification, with malformed-image cases. */
public final class InstructionTests {
  private static int checks;
  private InstructionTests(){ }
  public static void run() {
    Assembler invalidBase=new Assembler();invalidBase.r(Op.CX_RETURN,0,0,0);invalidBase.baseOnly=true;
    expect(()->invalidBase.build(1,0));
    Assembler invalidRegister=new Assembler();invalidRegister.immediate(Op.REG_READ,0,0, MachineAbi.REGISTER_COUNT);
    expect(()->invalidRegister.build(1,0));
    for(Op op:Op.values()) {
      int word=Instr.header(op,255,128,193);
      equal(Instr.op(word),op);equal(Instr.a(word),255);equal(Instr.b(word),128);equal(Instr.c(word),193);
    }
    for(int offset:new int[]{-32768,-4,0,4,32764})equal(Instr.branchOffset(Instr.branch(Op.CX_BR_TRUE,255,offset)),offset);
    for(int offset:new int[]{-8388608,-4,0,4,8388604})equal(Instr.jumpOffset(Instr.jump(offset)),offset);
    equal(Instr.index(Instr.upper(Op.LC,255,65535)),65535);
    for(Type type:Type.values())for(Instr.Relation relation:Instr.Relation.values()) {
      int control=Instr.control(type,relation);equal(Instr.type(control),type);equal(Instr.relation(control),relation);
    }
    int call=Instr.externalWindow(200,50,4);
    equal(Instr.argumentBase(call),200);equal(Instr.argumentCount(call),50);equal(Instr.resultCount(call),4);
    expect(()->Instr.header(Op.MOVE,256,0,0));expect(()->Instr.branch(Op.CX_BR_TRUE,0,2));
    expect(()->Instr.branch(Op.CX_BR_TRUE,0,32768));expect(()->Instr.jump(-8388612));
    expect(()->Instr.upper(Op.LC,0,65536));expect(()->Instr.callWindow(1,5));

    Assembler a=new Assembler();int k=a.constant(Type.LONG,42),loop=a.label(),exit=a.label();
    a.constant(0,k).bind(loop).rx(Op.IADD,1,0,0,Instr.control(Type.LONG));
    a.branch(Op.CX_BR_FALSE,2,exit).jump(loop).bind(exit).r(Op.CX_RETURN,1,1,0);
    Prototype p=a.build(3,0);
    equal(p.wordCount(),6);equal(Instr.branchOffset(p.word(3)),8);equal(Instr.jumpOffset(p.word(4)),-12);
    equal(p.instructionStart(1),true);equal(p.instructionStart(2),false);
    equal(p.constantType(0),Type.LONG);equal(p.constantBits(0),42L);
    String listing= Disassembler.dump(p);
    equal(listing.contains("kind=LONG"),true);equal(listing.contains("@000014 (+8)"),true);
    equal(listing.contains("@000004 (-12)"),true);
    a.r(Op.CX_RETURN,0,0,0);a.constant(Type.INT,7);equal(p.wordCount(),6);equal(p.constantCount(),1);

    Assembler strings=new Assembler();int text=strings.string("hello 世界\n\"\\\0");
    strings.constant(0,text).r(Op.CX_RETURN,0,1,0);Prototype stringImage=strings.build(1,0);
    equal(stringImage.string(text),"hello 世界\n\"\\\0");
    equal(Disassembler.dump(stringImage).contains("\\n"),true);
    Assembler nested=new Assembler();int fn=nested.function(p);nested.constant(0,fn).r(Op.CX_RETURN,0,1,0);
    equal(nested.build(1,0).function(0)==p,true);

    Assembler calls=new Assembler();calls.rx(Op.CX_EX_CALL,4,0,1,Instr.externalWindow(2,2,2)).r(Op.CX_RETURN,4,2,0);
    equal(Disassembler.dump(calls.build(6,0)).contains("args=r2 argc=2 results=2"),true);
    Assembler fused=new Assembler();int target=fused.label();
    fused.compareBranch(0,1,Instr.control(Type.INT,Instr.Relation.LT),target);
    fused.immediate(Op.LOAD64,2,0,-8).bind(target).r(Op.CX_RETURN,2,1,0);
    equal(fused.build(3,0).word(2),20);

    Assembler missing=new Assembler();missing.jump(missing.label());expect(()->missing.build(1,0));
    Assembler rebound=new Assembler();int label=rebound.label();rebound.bind(label);expect(()->rebound.bind(label));
    Assembler badRegister=new Assembler();badRegister.r(Op.MOVE,3,0,0);expect(()->badRegister.build(3,0));
    Assembler badConstant=new Assembler();badConstant.constant(0,0).r(Op.CX_RETURN,0,1,0);expect(()->badConstant.build(1,0));
    Assembler truncated=new Assembler();truncated.rx(Op.IADD,0,0,0,Instr.control(Type.LONG));truncated.words--;expect(()->truncated.build(1,0));
    Assembler middle=new Assembler();middle.rx(Op.IADD,0,0,0,Instr.control(Type.LONG)).r(Op.NOP,0,0,0);
    middle.code[2]=Instr.branch(Op.CX_BR_TRUE,0,-4);expect(()->middle.build(1,0));
    Assembler returns=new Assembler();returns.r(Op.CX_RETURN,0,5,0);expect(()->returns.build(6,0));
    Assembler wrongWindow=new Assembler();wrongWindow.rx(Op.CX_EX_CALL,0,0,0,Instr.externalWindow(1,2,1));expect(()->wrongWindow.build(2,0));
    Assembler end=new Assembler();int after=end.label();end.jump(after).bind(after);expect(()->end.build(1,0));
    System.out.println("ELX instruction encoding: "+checks+" checks passed");
  }
  private static void equal(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
  private static void expect(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError("Expected invalid instruction/image to fail");}
}
