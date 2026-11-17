package io.viki.elx.compile;

import io.viki.elx.SyntaxException;
import io.viki.elx.Type;
import io.viki.elx.asm.Disassembler;
import io.viki.elx.asm.Prototype;
import io.viki.elx.compile.Compiler;
import io.viki.elx.lex.Token;
import io.viki.elx.lex.Tokenizer;
import io.viki.elx.memory.*;
import io.viki.elx.vm.VM;

/** Source -> bytecode -> both VM backends, including malformed source and runtime types. */
public final class CompilerTests {
  private static final Compiler COMPILER=new Compiler();
  private static int checks,scripts;
  private CompilerTests() { }
  private static final class NoHost extends ExtHeap {
    NoHost(ElxHeap heap){super(heap);}
    @Override protected void readField(Object target,long member,long result){throw new UnsupportedOperationException();}
    @Override protected void writeField(Object target,long member,long value){throw new UnsupportedOperationException();}
    @Override protected int invoke(Object target,long member,long args,int count,long result,int capacity){throw new UnsupportedOperationException();}
  }
  public static void main(String[] args){run();}
  public static void run(){
    lexical();diagnostics();exercise(new JavaMemory(32));exercise(new NativeMemory(32));
    System.out.println("ELX scalar compiler: "+scripts+" script executions, "+checks+" checks passed");
  }
  private static void exercise(Memory memory){
    Arena arena=new Arena(memory);
    try(memory; ElxHeap heap=new ElxHeap(arena); NoHost host=new NoHost(heap); JavaRegs regs=new JavaRegs(arena); VM vm=new VM(heap,host,regs)){
      expect(vm,"""
          local total = 0
          for i = 0, i < 100, i += 1 {
            total += i
          }
          return total
          """,Type.INT,4950);
      expect(vm,"""
          local result = 1
          local n = 10
          while n > 1 {
            result *= n
            n -= 1
          }
          return result
          """,Type.INT,3628800);
      expect(vm,"""
          local sum = 0
          for (i = 0, i < 10, i += 1) {
            if i == 2 { continue }
            for j = 0, j < 10, j += 1 {
              if j == 4 { break }
              sum += i * 10 + j
            }
          }
          return sum
          """,Type.INT,1774);
      expect(vm,"""
          local value = 0
          {
            local x = 5
            value = x
            { local x = 9; value += x }
          }
          return value
          """,Type.INT,14);
      expect(vm,"""
          local output = 0
          for i = 0, i < 6, i += 1 {
            if i < 2 { output += 1
            } else if i < 4 { output += 10
            } else { output += 100 }
          }
          return output
          """,Type.INT,222);
      expect(vm,"""
          local i = 0
          local sum = 0
          while i < 10 {
            i += 1
            if i % 2 == 0 { continue }
            sum += i
          }
          return sum
          """,Type.INT,25);
      expect(vm,"""
          local yes = true; local no = false
          return yes || (1 / 0 == 0), no && (1 / 0 == 0), false || true && false
          """,new Type[]{Type.BOOL,Type.BOOL,Type.BOOL},new long[]{1,0,0});
      expect(vm,"return (1 | 2) & 7 ^ 8, 1 + 2 * 3, 128b << 1b, 32767s + 1s",
          new Type[]{Type.INT,Type.INT,Type.UBYTE,Type.SHORT},new long[]{11,7,0,-32768});
      expect(vm,"return b'1010, h'ff, 100'000'000, 18446744073709551615ul",
          new Type[]{Type.INT,Type.INT,Type.INT,Type.ULONG},new long[]{10,255,100000000,-1});
      expect(vm,"""
          local x = 1.25f; local y = 2s
          return x + y, 1l + 2f, int(3.9), float(7)
          """,new Type[]{Type.FLOAT,Type.DOUBLE,Type.INT,Type.FLOAT},new long[]{Float.floatToRawIntBits(3.25f),Double.doubleToRawLongBits(3),3,Float.floatToRawIntBits(7)});
      StringBuilder pointerOutput = new StringBuilder();
      vm.output(pointerOutput::append);
      vm.execute(COMPILER.compile("print 'bare', 7; print('call', 8)"));
      check(pointerOutput.toString().equals("bare, 7\ncall, 8\n"));
      pointerOutput.setLength(0);
      vm.execute(COMPILER.compile("""
          local v = int(5)
          local pointer = &v
          *pointer = 6
          assert(v == 6)
          assert(*pointer == 6)
          pointer = &print
          (*pointer)("I can print")
          assert(*pointer == print)
          local managedPointer = pointer
          assert(managedPointer == pointer)
          (*managedPointer)("I can print, too!")
          assert(*managedPointer == print)
          return str(*pointer)
          """));
      scripts++;
      check(pointerOutput.toString().equals("I can print" + System.lineSeparator() + "I can print, too!" + System.lineSeparator()));
      check(heap.exportString(Slot.bits(memory, vm.results())).startsWith("<function@"));
      vm.output(System.out::print);
      String changed="local value=3; value='ab'; local text=value+'cd'; value=4l; return text, value+2l, ulong(-1l), bool(0.0)";
      vm.execute(COMPILER.compile(changed));scripts++;
      check(heap.exportString(Slot.bits(memory,vm.results())).equals("abcd"));
      check(Slot.bits(memory,vm.results()+Slot.SIZE)==6);check(Slot.bits(memory,vm.results()+2L*Slot.SIZE)==-1);check(Slot.bits(memory,vm.results()+3L*Slot.SIZE)==0);
      expect(vm,"local x=18446744073709551615ul; return x>0, x<0, x/2ul, x%2ul",
          new Type[]{Type.BOOL,Type.BOOL,Type.ULONG,Type.ULONG},new long[]{1,0,Long.MAX_VALUE,1});
      expect(vm,"return -2147483648, -9223372036854775808l, -32768s, -0.0f",
          new Type[]{Type.INT,Type.LONG,Type.SHORT,Type.FLOAT},new long[]{Integer.MIN_VALUE,Long.MIN_VALUE,Short.MIN_VALUE,0x80000000L});
      expect(vm,"local x=1; x<<=3; x|=2; x^=1; x&=15; x>>=1; x>>>=1; x~=; return x",Type.INT,-3);
      expect(vm,"local i=50; for i=0, i<3, i+=1 { } return i",Type.INT,50);
      expect(vm,"local x; return x",Type.VOID,0);
      expect(vm,"return +1, -(-1), ~0, ubyte(256)",new Type[]{Type.INT,Type.INT,Type.INT,Type.UBYTE},new long[]{1,1,-1,0});
      expect(vm,"local x=8; return +x, -x, ~x, !bool(x)",new Type[]{Type.INT,Type.INT,Type.INT,Type.BOOL},new long[]{8,-8,-9,0});
      expect(vm,"return 'a'<'b', 'b'>='b', 'a'!='A', null==null",new Type[]{Type.BOOL,Type.BOOL,Type.BOOL,Type.BOOL},new long[]{1,1,1,1});
      expect(vm,"local x='a'; local y='ab'; return x<y, y>x, x>=y, x<=x",new Type[]{Type.BOOL,Type.BOOL,Type.BOOL,Type.BOOL},new long[]{1,1,0,1});
      expect(vm,"local x=0.0/0.0; return x<1.0, x>=1.0, x==x, x!=x",new Type[]{Type.BOOL,Type.BOOL,Type.BOOL,Type.BOOL},new long[]{0,0,0,1});
      expect(vm,"while true { break } return 3",Type.INT,3);
      expect(vm,"local x=0; while x<3 { x+=1 } return x",Type.INT,3);
      Prototype text=COMPILER.compile("return 'a\\n\\u4e2d\\0', \"quoted\\\"\"");vm.execute(text);scripts++;
      check(heap.exportString(Slot.bits(memory,vm.results())).equals("a\n中\0"));check(heap.exportString(Slot.bits(memory,vm.results()+Slot.SIZE)).equals("quoted\""));
      check(vm.execute(COMPILER.compile(""))==0);check(vm.execute(COMPILER.compile("return;"))==0);
      check(vm.execute(COMPILER.compile("local n = 1"))==0);scripts+=3;
      // Statement/scope temporaries don't accumulate as the script grows.
      StringBuilder many=new StringBuilder("local x=0;");
      for(int i=0;i<2000;i++)many.append("x+=1; { local shadow=1; } ");
      many.append("return x");Prototype large=COMPILER.compile(many.toString());check(large.registerCount()<16);
      vm.execute(large);check(Slot.bits(memory,vm.results())==2000);scripts++;
      vm.execute(large);check(Slot.bits(memory,vm.results())==2000);scripts++;
      failure(IllegalArgumentException.class,()->vm.execute(COMPILER.compile("local x=1; if x { return 1 }")));
      failure(IllegalArgumentException.class,()->vm.execute(COMPILER.compile("local x=1; return false||x")));
      failure(IllegalArgumentException.class,()->vm.execute(COMPILER.compile("local x=1.0; return x&2")));
      failure(IllegalArgumentException.class,()->vm.execute(COMPILER.compile("local x='s'; return int(x)")));
      failure(SyntaxException.class,()->COMPILER.compile("local x=1; return long(&x)"));
      failure(IllegalArgumentException.class,()->vm.execute(COMPILER.compile("return ptr(1l)")));
      failure(ArithmeticException.class,()->vm.execute(COMPILER.compile("local x=0; return 1/x")));
      check(regs.depth()==0);vm.budget(512);
      failure(IllegalStateException.class,()->vm.execute(COMPILER.compile("while true { continue }")));
      check(regs.depth()==0);vm.budget(100000000);expect(vm,"return 42",Type.INT,42);
      System.out.println(memory.getClass().getSimpleName()+": scalar source compiler passed");
    }
  }
  private static void lexical(){
    Tokenizer scan=new Tokenizer("local iffy = 1 /* hi */ // line\r\nreturn iffy >>>= 2",true);
    Token[] expected={Token.LOCAL,Token.IDENTIFIER,Token.EQUAL,Token.INT_LITERAL,Token.SEP,Token.RETURN,Token.IDENTIFIER,Token.SHIFT_UNSIGNED_RIGHT_EQUAL,Token.INT_LITERAL,Token.EOF};
    for(Token token:expected)check(scan.next().kind()==token);
    Tokenizer identifiers=new Tokenizer("if#x _end λ");for(int i=0;i<3;i++)check(identifiers.next().kind()==Token.IDENTIFIER);
    check(identifiers.next().kind()==Token.EOF);
    check(new Tokenizer("env").next().kind()==Token.ENV);
    try{COMPILER.compile("local x=1\r\n\r\nreturn @");throw new AssertionError("Missing diagnostic");}
    catch(SyntaxException error){check(error.span().line()==3);check(error.span().column()==8);}
  }
  private static void diagnostics(){
    String[] invalid={
      "local x=", "local x=1 local x=2", "break", "continue",
      "if true { return 1", "while true {", "if 1 { }", "return !1", "return 1.0 & 2",
      "return false||1", "return 2147483648",
      "return 256b", "return -32769s", "return 18446744073709551616ul", "return 1e999", "return 1e",
      "return 1.0u", "return b'102", "return 1'", "return 'open", "/* open", "return '\\q'", "return '\\u123'",
      "return 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17", "return 1 ? 2",
      "local x=1; x+=", "} else { }", "for i=0, true, i+=1 {", "return (1+2", "return true && null"
    };
    for(String source:invalid)failure(SyntaxException.class,()->COMPILER.compile(source));
    StringBuilder registers=new StringBuilder();for(int i=0;i<257;i++)registers.append("local x").append(i).append("=0;");
    failure(SyntaxException.class,()->COMPILER.compile(registers.toString()));
  }
  private static void expect(VM vm,String source,Type type,long bits){expect(vm,source,new Type[]{type},new long[]{bits});}
  private static void expect(VM vm,String source,Type[] types,long[] values){
    Prototype script=COMPILER.compile(source);check(vm.execute(script)==values.length);scripts++;
    for(int i=0;i<values.length;i++){
      long cell=Slot.address(vm.results(),i);check(Slot.type(vm.memory(),cell)==types[i]);
      if(Slot.bits(vm.memory(),cell)!=values[i])throw new AssertionError("Wrong result #"+i+" for:\n"+source+"\n"+ Disassembler.dump(script)+"actual="+Slot.bits(vm.memory(),cell)+" expected="+values[i]);
      checks++;
    }
  }
  private static void failure(Class<? extends Throwable> expected,Runnable task){
    checks++;try{task.run();}catch(Throwable error){if(expected.isInstance(error))return;throw new AssertionError("Expected "+expected,error);}throw new AssertionError("Expected "+expected);
  }
  private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("Compiler check #"+checks);}
}
