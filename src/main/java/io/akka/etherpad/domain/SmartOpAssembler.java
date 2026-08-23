package io.akka.etherpad.domain;

/**
 * Routes appended ops into per-opcode {@link MergingOpAssembler}s (so consecutive
 * inserts/deletes/keeps merge even when they weren't presented that way), reordering
 * consecutive {@code -}/{@code +} pairs to delete-before-insert and stripping the
 * changeset's final trailing keep. Ported from ether/etherpad {@code
 * SmartOpAssembler.ts}.
 */
final class SmartOpAssembler {
  private final MergingOpAssembler minusAssem = new MergingOpAssembler();
  private final MergingOpAssembler plusAssem = new MergingOpAssembler();
  private final MergingOpAssembler keepAssem = new MergingOpAssembler();
  private final StringBuilder out = new StringBuilder();
  private char lastOpcode = Op.NONE;

  private void flushKeeps() {
    out.append(keepAssem.toString());
    keepAssem.clear();
  }

  private void flushPlusMinus() {
    out.append(minusAssem.toString());
    minusAssem.clear();
    out.append(plusAssem.toString());
    plusAssem.clear();
  }

  void append(Op op) {
    if (op.opcode == Op.NONE || op.chars == 0) return;

    if (op.opcode == Op.DELETE) {
      if (lastOpcode == Op.KEEP) flushKeeps();
      minusAssem.append(op);
    } else if (op.opcode == Op.INSERT) {
      if (lastOpcode == Op.KEEP) flushKeeps();
      plusAssem.append(op);
    } else if (op.opcode == Op.KEEP) {
      if (lastOpcode != Op.KEEP) flushPlusMinus();
      keepAssem.append(op);
    }
    lastOpcode = op.opcode;
  }

  void endDocument() {
    keepAssem.endDocument();
  }

  @Override
  public String toString() {
    flushPlusMinus();
    flushKeeps();
    return out.toString();
  }
}
