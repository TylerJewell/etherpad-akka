package io.akka.etherpad.domain;

/**
 * Merges consecutive ops of the same opcode, ignores no-ops, and (on
 * {@link #endDocument()}) drops a final pure keep. Does not re-order operations. Ported
 * from ether/etherpad {@code MergingOpAssembler.ts}; the source keys the merge test on
 * opcode *and* attribute string, but every op in this port carries an empty attribute
 * string (see SPEC-001 §4), so the attribute half of that comparison is always true and
 * is dropped here.
 */
final class MergingOpAssembler {
  private final OpAssembler assem = new OpAssembler();
  private final Op bufOp = new Op();
  private int bufOpAdditionalCharsAfterNewline = 0;

  void flush(boolean isEndDocument) {
    if (bufOp.opcode == Op.NONE) return;
    if (isEndDocument && bufOp.opcode == Op.KEEP) {
      // final merged keep, leave it implicit
    } else {
      assem.append(bufOp);
      if (bufOpAdditionalCharsAfterNewline != 0) {
        bufOp.chars = bufOpAdditionalCharsAfterNewline;
        bufOp.lines = 0;
        assem.append(bufOp);
        bufOpAdditionalCharsAfterNewline = 0;
      }
    }
    bufOp.opcode = Op.NONE;
  }

  void append(Op op) {
    if (op.chars <= 0) return;
    if (bufOp.opcode == op.opcode) {
      if (op.lines > 0) {
        bufOp.chars += bufOpAdditionalCharsAfterNewline + op.chars;
        bufOp.lines += op.lines;
        bufOpAdditionalCharsAfterNewline = 0;
      } else if (bufOp.lines == 0) {
        bufOp.chars += op.chars;
      } else {
        bufOpAdditionalCharsAfterNewline += op.chars;
      }
    } else {
      flush(false);
      bufOp.copyFrom(op);
    }
  }

  void endDocument() {
    flush(true);
  }

  void clear() {
    assem.clear();
    bufOp.clear();
    bufOpAdditionalCharsAfterNewline = 0;
  }

  @Override
  public String toString() {
    flush(false);
    return assem.toString();
  }
}
