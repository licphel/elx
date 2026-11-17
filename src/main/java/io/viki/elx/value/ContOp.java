package io.viki.elx.value;

import io.viki.elx.lib.Span;

/**
 * VM-owned raw list/table access without GET/SET metamethods. Values are copied
 * as tagged payloads into caller-provided rooted slots, without boxing.
 * List indexed reads/writes are O(1), table access is expected O(1), and
 * traversal has no per-entry allocation. Structural changes invalidate table
 * cursors; replacing an existing value does not. Thread-confined.
 */
public interface ContOp {
  /**
   * Creates a list value with an initial capacity hint.
   *
   * @param destination destination span
   * @param index       destination slot index
   * @param capacity    initial capacity hint
   */
  void newList(Span destination, int index, int capacity);

  /**
   * Creates a table value with an initial capacity hint.
   *
   * @param destination destination span
   * @param index       destination slot index
   * @param capacity    initial capacity hint
   */
  void newTable(Span destination, int index, int capacity);

  /**
   * Returns the number of elements in a list.
   *
   * @param values    span containing the list
   * @param listIndex list slot index
   * @return list size
   */
  int listSize(Span values, int listIndex);

  /**
   * Copies one list element into a destination slot.
   *
   * @param values           span containing the list
   * @param listIndex        list slot index
   * @param elementIndex     element index
   * @param destination      destination span
   * @param destinationIndex destination slot index
   */
  void listGet(Span values, int listIndex, int elementIndex, Span destination, int destinationIndex);

  /**
   * Replaces one list element.
   *
   * @param values       span containing the list
   * @param listIndex    list slot index
   * @param elementIndex element index
   * @param source       source span
   * @param sourceIndex  source slot index
   */
  void listSet(Span values, int listIndex, int elementIndex, Span source, int sourceIndex);

  /**
   * Appends one value to a list.
   *
   * @param values      span containing the list
   * @param listIndex   list slot index
   * @param source      source span
   * @param sourceIndex source slot index
   */
  void listAppend(Span values, int listIndex, Span source, int sourceIndex);

  /**
   * Removes one list element and shifts later elements toward the removed slot.
   *
   * @param values       span containing the list
   * @param listIndex    list slot index
   * @param elementIndex element index
   */
  void listRemove(Span values, int listIndex, int elementIndex);

  /**
   * Returns the number of entries in a table.
   *
   * @param values     span containing the table
   * @param tableIndex table slot index
   * @return table size
   */
  int tableSize(Span values, int tableIndex);

  /**
   * Reads a table entry without invoking GET.
   *
   * @param values           span containing the table
   * @param tableIndex       table slot index
   * @param keys             span containing the key
   * @param keyIndex         key slot index
   * @param destination      destination span
   * @param destinationIndex destination slot index
   * @return {@code true} when the key is present; a missing key writes void to the destination
   */
  boolean tableGet(Span values, int tableIndex, Span keys, int keyIndex, Span destination, int destinationIndex);

  /**
   * Replaces or inserts a table entry without invoking SET.
   *
   * @param values      span containing the table
   * @param tableIndex  table slot index
   * @param keys        span containing the key
   * @param keyIndex    key slot index
   * @param source      source span
   * @param sourceIndex source slot index
   */
  void tableSet(Span values, int tableIndex, Span keys, int keyIndex, Span source, int sourceIndex);

  /**
   * Removes a table entry without invoking SET.
   *
   * @param values     span containing the table
   * @param tableIndex table slot index
   * @param keys       span containing the key
   * @param keyIndex   key slot index
   * @return {@code true} when an entry was removed
   */
  boolean tableRemove(Span values, int tableIndex, Span keys, int keyIndex);

  /**
   * Removes every entry from a table.
   *
   * @param values     span containing the table
   * @param tableIndex table slot index
   */
  void tableClear(Span values, int tableIndex);

  /**
   * Borrows a cursor rooted until close; nested cursors are independent.
   * Structural table changes invalidate the cursor.
   *
   * @param values     span containing the table
   * @param tableIndex table slot index
   * @return cursor over the table's current entries
   */
  TableCursor tableCursor(Span values, int tableIndex);

  /**
   * Raw table iterator. Keys are copied out and cannot be overwritten in-place.
   * A cursor borrows one fixed state window, roots its table, and reacquires
   * backing storage after script calls. Close releases its roots.
   */
  interface TableCursor extends AutoCloseable {
    /**
     * Advances to the next entry.
     *
     * @return {@code true} when a current entry is available
     */
    boolean next();

    /**
     * Copies the current key to a destination slot.
     *
     * @param destination destination span
     * @param index       destination slot index
     */
    void key(Span destination, int index);

    /**
     * Copies the current value to a destination slot.
     *
     * @param destination destination span
     * @param index       destination slot index
     */
    void value(Span destination, int index);

    /**
     * Replaces the current value without changing table structure.
     *
     * @param source source span
     * @param index  source slot index
     */
    void setValue(Span source, int index);

    /** Releases the cursor's rooted table and borrowed state. */
    @Override
    void close();
  }
}
