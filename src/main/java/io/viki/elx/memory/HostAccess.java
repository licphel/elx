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

import io.viki.elx.lib.Call;
import io.viki.elx.value.Metamethod;

/**
 * Script-facing access contract for one imported Java object.
 *
 * <p>Objects can implement this interface directly. The default external heap
 * detects that interface and routes every metamethod, tracing, and collection
 * callback to it. This keeps an application object independent of the ELX heap
 * implementation. {@link ExtHeap} remains available for applications that need
 * to control external handle storage or provide a different dispatch system.</p>
 *
 * <p>Implementations are thread-confined to the context invoking them. The
 * {@link Call} view is borrowed for the current operation and must not be retained.</p>
 */
public interface HostAccess {
  /**
   * Handles one ELX metamethod. The default is intentionally empty, allowing
   * an object to expose only the operations it needs. Result-producing hooks
   * append values through the supplied {@link Call} view.
   *
   * <p>GET receives self/key, SET receives self/key/value, and CALL receives
   * self followed by explicit arguments. Unary hooks receive their operand;
   * binary hooks receive left/right in the original expression order, even
   * when this object supplied the right-hand hook. STR/BOOL/GC receive self.
   * INIT receives self followed by constructor arguments.</p>
   * <p>An empty scalar-result hook means unavailable; returning null counts as
   * handled. CALL may return no values. Empty SET/INIT/GC hooks are no-ops.</p>
   *
   * @param method metamethod being requested
   * @param args   borrowed arguments and result window
   */
  default void elx_handle(Metamethod method, Call args) {
  }

  /**
   * Visits managed ELX references retained by this object.
   *
   * @param visitor visitor receiving retained references
   */
  default void elx_trace(HeapVisitor visitor) {
  }
}
