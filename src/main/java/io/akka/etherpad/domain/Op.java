package io.akka.etherpad.domain;

/**
 * One operation in a changeset: keep, insert, or delete a run of characters from the
 * base document. Mutable and reused as a scratch value while two op sequences are
 * zipped together, matching the source's own consumption pattern (a caller drains an
 * op by shrinking {@code chars}/{@code lines} and clearing {@code opcode} once nothing
 * is left).
 *
 * <p>Ported from ether/etherpad {@code src/static/js/Op.ts}. Attribute spans
 * (formatting) are out of this port's slice — see SPEC-001 §4 — so there is no
 * {@code attribs} field.
 */
public final class Op {
  public static final char NONE = 0;
  public static final char KEEP = '=';
  public static final char INSERT = '+';
  public static final char DELETE = '-';

  public char opcode;
  public int chars;
  public int lines;

  public Op() {
    this(NONE);
  }

  public Op(char opcode) {
    this.opcode = opcode;
  }

  public void clear() {
    this.opcode = NONE;
    this.chars = 0;
    this.lines = 0;
  }

  public void copyFrom(Op src) {
    this.opcode = src.opcode;
    this.chars = src.chars;
    this.lines = src.lines;
  }

  public boolean isPresent() {
    return opcode != NONE;
  }

  @Override
  public String toString() {
    if (opcode == NONE) throw new IllegalStateException("null op");
    String l = lines != 0 ? "|" + ChangesetCodec.numToString(lines) : "";
    return l + opcode + ChangesetCodec.numToString(chars);
  }
}
