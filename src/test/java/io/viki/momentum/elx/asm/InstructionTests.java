package io.viki.elx.asm;

import io.viki.elx.Type;
import java.util.Objects;

/** O1 encoding/relocation and rejection of malformed constant, branch and call windows. */
public final class InstructionTests {
  private static int checks;
  private InstructionTests() {}
  public static void run() {
    for(Op op:Op.values()) {
      int word=Instr.header(op,255,128,193);
      equal(Instr.op(word),op);equal(Instr.a(word),255);equal(Instr.b(word),128);equal(Instr.c(word),193);
    }
    for(int offset:new int[]{-32768,-4,0,4,32764})
      equal(Instr.branchOffset(Instr.branch(Op.BR_TRUE,255,offset)),offset);
    for(int offset:new int[]{-8388608,-4,0,4,8388604}) equal(Instr.jumpOffset(Instr.jump(offset)),offset);
    equal(Instr.index(Instr.upper(Op.LOADK,255,65535)),65535);
    for(Instr.Relation relation:Instr.Relation.values()) {
      equal(Instr.relation(Instr.control(relation)),relation);
      equal(Instr.control(relation,true)&Instr.BRANCH_FALSE,Instr.BRANCH_FALSE);
    }
    int call=Instr.callWindow(50,4);
    equal(Instr.argumentCount(call),50);equal(Instr.resultCount(call),4);
    expect(()->Instr.header(Op.MOVE,256,0,0));
    expect(()->Instr.branch(Op.BR_TRUE,0,2));
    expect(()->Instr.branch(Op.BR_TRUE,0,32768));
    expect(()->Instr.jump(-8388612));
    expect(()->Instr.upper(Op.LOADK,0,65536));
    expect(()->Instr.callWindow(1,17));
    Assembler a=new Assembler();int loop=a.label(),exit=a.label();
    a.constant(0,a.constant(Type.LONG,42)).bind(loop).r(Op.ADD,1,0,0);
    a.branch(Op.BR_FALSE,2,exit).jump(loop).bind(exit).r(Op.RETURN1,1,0,0);
    Prototype image=a.build(3,0);
    equal(image.wordCount(),5);equal(Instr.branchOffset(image.word(2)),8);
    equal(Instr.jumpOffset(image.word(3)),-8);
    equal(image.constantBits(0),42L);
    String text=Disassembler.dump(image);
    equal(text.contains("ADD"),true);
    a.r(Op.RETURN0,0,0,0);equal(image.wordCount(),5);
    Assembler strings=new Assembler();int key=strings.string("hello 世界\n\"\\\0");
    strings.constant(0,key).r(Op.RETURN1,0,0,0);
    equal(strings.build(1,0).string(key),"hello 世界\n\"\\\0");
    Assembler missing=new Assembler();missing.jump(missing.label());expect(()->missing.build(1,0));
    Assembler rebound=new Assembler();int label=rebound.label();rebound.bind(label);expect(()->rebound.bind(label));
    Assembler badRegister=new Assembler();badRegister.r(Op.MOVE,3,0,0);expect(()->badRegister.build(3,0));
    Assembler badConstant=new Assembler();badConstant.constant(0,0).r(Op.RETURN1,0,0,0);expect(()->badConstant.build(1,0));
    Assembler truncated=new Assembler();truncated.rx(Op.COMPARE,0,0,0,0);truncated.words--;expect(()->truncated.build(1,0));
    Assembler middle=new Assembler();middle.rx(Op.COMPARE,0,0,0,0).r(Op.RETURN0,0,0,0);
    middle.code[2]=Instr.branch(Op.BR_TRUE,0,-4);expect(()->middle.build(1,0));
    Assembler returns=new Assembler();returns.r(Op.RETURN,0,17,0);expect(()->returns.build(18,0));
    Assembler window=new Assembler();window.rx(Op.CALL,0,0,1,Instr.callWindow(2,1));expect(()->window.build(2,0));
    Assembler bool=new Assembler();bool.r(Op.LOADBOOL,0,2,0);expect(()->bool.build(1,0));
    Assembler relation=new Assembler();relation.rx(Op.COMPARE,0,0,0,99);expect(()->relation.build(1,0));
    Assembler end=new Assembler();int after=end.label();end.jump(after).bind(after);expect(()->end.build(1,0));
    System.out.println("ELX O1 instruction encoding: "+checks+" checks passed");
  }
  private static void equal(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
  private static void expect(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError("Expected invalid instruction/image");}
}
