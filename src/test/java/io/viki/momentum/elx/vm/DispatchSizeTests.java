package io.viki.elx.vm;

import io.viki.elx.vm.VM;

import java.io.DataInputStream;
import java.io.IOException;

/** Guards the dispatcher's eligibility for HotSpot compilation, not elapsed test time. */
final class DispatchSizeTests {
  // HotSpot normally declines methods above 8,000 bytes; reserve space for growth.
  private static final int MAX_DISPATCH_BYTES = 7_000;

  private DispatchSizeTests() {}

  static void run() throws IOException {
    var resource = VM.class.getResourceAsStream("VM.class");
    if (resource == null) throw new IOException("VM class bytes are unavailable");
    try (DataInputStream input = new DataInputStream(resource)) {
      input.skipNBytes(8);
      String[] utf = new String[input.readUnsignedShort()];
      for (int i = 1; i < utf.length; i++) {
        switch (input.readUnsignedByte()) {
          case 1 -> utf[i] = input.readUTF();
          case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
          case 5, 6 -> { input.skipNBytes(8); i++; }
          case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
          case 15 -> input.skipNBytes(3);
          default -> throw new IOException("Unknown class-file constant tag");
        }
      }
      input.skipNBytes(6);
      input.skipNBytes(2L * input.readUnsignedShort());
      int fields = input.readUnsignedShort();
      for (int i = 0; i < fields; i++) {
        input.skipNBytes(6);
        skipAttributes(input);
      }
      int methods = input.readUnsignedShort();
      for (int i = 0; i < methods; i++) {
        input.readUnsignedShort();
        String name = utf[input.readUnsignedShort()];
        input.readUnsignedShort();
        int attributes = input.readUnsignedShort();
        for (int j = 0; j < attributes; j++) {
          String attribute = utf[input.readUnsignedShort()];
          long length = Integer.toUnsignedLong(input.readInt());
          if ("runUntil".equals(name) && "Code".equals(attribute)) {
            input.skipNBytes(4);
            int bytes = input.readInt();
            if (bytes > MAX_DISPATCH_BYTES) {
              throw new AssertionError("VM dispatch grew to " + bytes
                  + " bytes; extract instruction bodies to preserve JIT compilation");
            }
            System.out.println("ELX dispatch size: " + bytes + "/" + MAX_DISPATCH_BYTES + " bytes");
            return;
          }
          input.skipNBytes(length);
        }
      }
      throw new AssertionError("VM dispatch Code attribute was not found");
    }
  }

  private static void skipAttributes(DataInputStream input) throws IOException {
    int count = input.readUnsignedShort();
    for (int i = 0; i < count; i++) {
      input.readUnsignedShort();
      input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
    }
  }
}
