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

package io.viki.elx.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves source files named by {@code #include}. Resolvers are called once for
 * each active include and can read files, classpath resources, or application
 * managed source. A compilation shares one macro table across its include tree.
 */
@FunctionalInterface
public interface IncludeResolver {
  /**
   * Creates a filesystem resolver. Relative paths first resolve beside the
   * including source, then relative to the process working directory.
   *
   * @return filesystem include resolver using UTF-8
   */
  static IncludeResolver fileSystem() {
    return (includingSource, requestedName) -> {
      Path requested = Path.of(requestedName);
      Path candidate = requested;
      if (!requested.isAbsolute()) {
        Path parent = Path.of(includingSource).getParent();
        if (parent != null) {
          Path beside = parent.resolve(requested).normalize();
          if (Files.isRegularFile(beside)) {
            candidate = beside;
          }
        }
      }
      if (!Files.isRegularFile(candidate)) {
        throw new IOException("Included source not found: " + requestedName);
      }
      Path absolute = candidate.toAbsolutePath().normalize();
      return new Source(absolute.toString(), Files.readString(absolute, StandardCharsets.UTF_8));
    };
  }

  /**
   * Loads one include relative to the source that requested it.
   *
   * @param includingSource source name containing the directive
   * @param requestedName   path or resource name from the directive
   * @return included source and its diagnostic name
   * @throws IOException if the source cannot be resolved or read
   */
  Source resolve(String includingSource, String requestedName) throws IOException;

  /** Immutable content returned by an include resolver. */
  record Source(String name, String text) {
    /**
     * Validates source metadata at the resolution boundary.
     *
     * @throws NullPointerException if a component is null
     */
    public Source {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(text, "text");
      if (name.isBlank()) {
        throw new IllegalArgumentException("Included source name is blank");
      }
    }
  }
}
