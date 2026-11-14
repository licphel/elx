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

package io.viki.elx.lex;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable prefix index generated from fixed token spellings. Construction uses
 * maps; matching uses only primitive transition arrays and enum references.
 * Matching is allocation-free and O(m log d), for matched length m and branch
 * degree d. Instances are safe to share between tokenizer instances/threads.
 */
final class FixedTokenMatcher {
  private final int[] starts;
  private final char[] characters;
  private final int[] targets;
  private final Token[] terminals;

  FixedTokenMatcher(Map<String, Token> spellings) {
    var states = new ArrayList<Builder>();
    states.add(new Builder());
    int edges = 0;
    for (var entry : spellings.entrySet()) {
      String spelling = entry.getKey();
      int state = 0;
      for (int i = 0; i < spelling.length(); i++) {
        char character = spelling.charAt(i);
        Builder parent = states.get(state);
        @Nullable Integer next = parent.edges.get(character);
        if (next == null) {
          next = states.size();
          parent.edges.put(character, next);
          states.add(new Builder());
          edges++;
        }
        state = next;
      }
      states.get(state).terminal = entry.getValue();
    }
    starts = new int[states.size() + 1];
    characters = new char[edges];
    targets = new int[edges];
    terminals = new Token[states.size()];
    int edge = 0;
    for (int state = 0; state < states.size(); state++) {
      starts[state] = edge;
      Builder node = states.get(state);
      terminals[state] = node.terminal;
      for (var entry : node.edges.entrySet()) {
        characters[edge] = entry.getKey();
        targets[edge++] = entry.getValue();
      }
    }
    starts[states.size()] = edge;
  }

  /**
   * Returns the longest whole fixed token, or Token.NONE if this position starts a variable token.
   */
  Token match(CharSequence source, int start) {
    if (start < 0 || start > source.length()) {
      throw new IndexOutOfBoundsException(start);
    }
    int state = 0;
    Token result = Token.NONE;
    for (int position = start; position < source.length(); position++) {
      int edge = Arrays.binarySearch(characters, starts[state], starts[state + 1], source.charAt(position));
      if (edge < 0) {
        break;
      }
      state = targets[edge];
      Token kind = terminals[state];
      if (kind == Token.NONE) {
        continue;
      }
      boolean word = Character.isJavaIdentifierStart(kind.spelling().charAt(0));
      if (word && ((start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1)))
          || (position + 1 < source.length() && Character.isJavaIdentifierPart(source.charAt(position + 1))))) {
        continue;
      }
      result = kind;
    }
    return result;
  }

  private static final class Builder {
    final Map<Character, Integer> edges = new TreeMap<>();
    Token terminal = Token.NONE;
  }
}
