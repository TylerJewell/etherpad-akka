package io.akka.etherpad.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The operational-transform engine: applying a changeset to a document, composing two
 * sequential changesets into one, and rebasing ({@code follow}) one changeset to apply
 * after another that started from the same document. Ported from ether/etherpad {@code
 * src/static/js/Changeset.ts}. See SPEC-001 for the deterministic contract (R1-R5) and
 * the evidence each rule was checked against.
 *
 * <p>Attribute spans (formatting) are out of scope — see SPEC-001 §4 — so every op here
 * carries no attributes, and the attribute-pool arguments the source threads through
 * {@code slicerZipperFunc}/{@code follow} are dropped rather than ported unused.
 */
public final class Changeset {
  private Changeset() {}

  public static String pack(int oldLen, int newLen, String opsStr, String bank) {
    return ChangesetCodec.pack(oldLen, newLen, opsStr, bank);
  }

  public static ChangesetCodec.Unpacked unpack(String cs) {
    return ChangesetCodec.unpack(cs);
  }

  public static int oldLen(String cs) {
    return unpack(cs).oldLen();
  }

  public static int newLen(String cs) {
    return unpack(cs).newLen();
  }

  public static String identity(int n) {
    return pack(n, n, "", "");
  }

  private static int countNewlines(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
    return n;
  }

  interface ZipFunc {
    Op apply(Op op1, Op op2);
  }

  private static String applyZip(String in1, String in2, ZipFunc func) {
    List<Op> ops1 = ChangesetCodec.deserializeOps(in1);
    List<Op> ops2 = ChangesetCodec.deserializeOps(in2);
    int i1 = 0;
    int i2 = 0;
    Op cur1 = i1 < ops1.size() ? ops1.get(i1) : null;
    Op cur2 = i2 < ops2.size() ? ops2.get(i2) : null;
    SmartOpAssembler assem = new SmartOpAssembler();
    while (cur1 != null || cur2 != null) {
      if (cur1 != null && cur1.opcode == Op.NONE) {
        i1++;
        cur1 = i1 < ops1.size() ? ops1.get(i1) : null;
      }
      if (cur2 != null && cur2.opcode == Op.NONE) {
        i2++;
        cur2 = i2 < ops2.size() ? ops2.get(i2) : null;
      }
      Op a = cur1 != null ? cur1 : new Op();
      Op b = cur2 != null ? cur2 : new Op();
      if (a.opcode == Op.NONE && b.opcode == Op.NONE) break;
      Op opOut = func.apply(a, b);
      if (opOut != null && opOut.opcode != Op.NONE) assem.append(opOut);
    }
    assem.endDocument();
    return assem.toString();
  }

  public static String applyToText(String cs, String str) {
    ChangesetCodec.Unpacked u = unpack(cs);
    if (str.length() != u.oldLen()) {
      throw new IllegalArgumentException("mismatched apply: " + str.length() + " / " + u.oldLen());
    }
    StringIterator bankIter = new StringIterator(u.charBank());
    StringIterator strIter = new StringIterator(str);
    StringBuilder out = new StringBuilder();
    for (Op op : ChangesetCodec.deserializeOps(u.ops())) {
      switch (op.opcode) {
        case Op.INSERT:
          if (op.lines != countNewlines(bankIter.peek(op.chars))) {
            throw new IllegalArgumentException("newline count is wrong in op +; cs:" + cs + " and text:" + str);
          }
          out.append(bankIter.take(op.chars));
          break;
        case Op.DELETE:
          if (op.lines != countNewlines(strIter.peek(op.chars))) {
            throw new IllegalArgumentException("newline count is wrong in op -; cs:" + cs + " and text:" + str);
          }
          strIter.skip(op.chars);
          break;
        case Op.KEEP:
          if (op.lines != countNewlines(strIter.peek(op.chars))) {
            throw new IllegalArgumentException("newline count is wrong in op =; cs:" + cs + " and text:" + str);
          }
          out.append(strIter.take(op.chars));
          break;
        default:
          throw new IllegalArgumentException("Invalid changeset: Unknown opcode: " + op.opcode);
      }
    }
    out.append(strIter.take(strIter.remaining()));
    return out.toString();
  }

  public static String checkRep(String cs) {
    ChangesetCodec.Unpacked u = unpack(cs);
    int oldLen = u.oldLen();
    int newLen = u.newLen();
    String charBank = u.charBank();
    SmartOpAssembler assem = new SmartOpAssembler();
    int oldPos = 0;
    int calcNewLen = 0;
    for (Op o : ChangesetCodec.deserializeOps(u.ops())) {
      switch (o.opcode) {
        case Op.KEEP:
          oldPos += o.chars;
          calcNewLen += o.chars;
          break;
        case Op.DELETE:
          oldPos += o.chars;
          if (oldPos > oldLen) {
            throw new IllegalArgumentException(oldPos + " > " + oldLen + " in " + cs);
          }
          break;
        case Op.INSERT: {
          if (charBank.length() < o.chars) {
            throw new IllegalArgumentException("Invalid changeset: not enough chars in charBank");
          }
          String chars = charBank.substring(0, o.chars);
          int nlines = countNewlines(chars);
          if (nlines != o.lines) {
            throw new IllegalArgumentException(
                "Invalid changeset: number of newlines in insert op does not match the charBank");
          }
          if (!(o.lines == 0 || chars.endsWith("\n"))) {
            throw new IllegalArgumentException(
                "Invalid changeset: multiline insert op does not end with a newline");
          }
          charBank = charBank.substring(o.chars);
          calcNewLen += o.chars;
          if (calcNewLen > newLen) {
            throw new IllegalArgumentException(calcNewLen + " > " + newLen + " in " + cs);
          }
          break;
        }
        default:
          throw new IllegalArgumentException("Invalid changeset: Unknown opcode: " + o.opcode);
      }
      assem.append(o);
    }
    calcNewLen += oldLen - oldPos;
    if (calcNewLen != newLen) {
      throw new IllegalArgumentException("Invalid changeset: claimed length does not match actual length");
    }
    if (!charBank.isEmpty()) {
      throw new IllegalArgumentException("Invalid changeset: excess characters in the charBank");
    }
    assem.endDocument();
    String normalized = pack(oldLen, calcNewLen, assem.toString(), u.charBank());
    if (!normalized.equals(cs)) {
      throw new IllegalArgumentException("Invalid changeset: not in canonical form");
    }
    return cs;
  }

  private static Op slicerZipperFunc(Op attOp, Op csOp) {
    Op opOut = new Op();
    if (attOp.opcode == Op.NONE) {
      opOut.copyFrom(csOp);
      csOp.opcode = Op.NONE;
    } else if (csOp.opcode == Op.NONE) {
      opOut.copyFrom(attOp);
      attOp.opcode = Op.NONE;
    } else if (attOp.opcode == Op.DELETE) {
      opOut.copyFrom(attOp);
      attOp.opcode = Op.NONE;
    } else if (csOp.opcode == Op.INSERT) {
      opOut.copyFrom(csOp);
      csOp.opcode = Op.NONE;
    } else {
      if (attOp.opcode != Op.INSERT && attOp.opcode != Op.KEEP) {
        throw new IllegalStateException("unexpected opcode in op: " + attOp);
      }
      if (csOp.opcode != Op.DELETE && csOp.opcode != Op.KEEP) {
        throw new IllegalStateException("unexpected opcode in op: " + csOp);
      }
      char outOpcode =
          attOp.opcode == Op.INSERT
              ? (csOp.opcode == Op.DELETE ? Op.NONE : Op.INSERT)
              : (csOp.opcode == Op.DELETE ? Op.DELETE : Op.KEEP);
      Op fullyConsumed;
      Op partiallyConsumed;
      if (attOp.chars <= csOp.chars) {
        fullyConsumed = attOp;
        partiallyConsumed = csOp;
      } else {
        fullyConsumed = csOp;
        partiallyConsumed = attOp;
      }
      opOut.opcode = outOpcode;
      opOut.chars = fullyConsumed.chars;
      opOut.lines = fullyConsumed.lines;
      partiallyConsumed.chars -= fullyConsumed.chars;
      partiallyConsumed.lines -= fullyConsumed.lines;
      if (partiallyConsumed.chars == 0) partiallyConsumed.opcode = Op.NONE;
      fullyConsumed.opcode = Op.NONE;
    }
    return opOut;
  }

  public static String compose(String cs1, String cs2) {
    ChangesetCodec.Unpacked u1 = unpack(cs1);
    ChangesetCodec.Unpacked u2 = unpack(cs2);
    int len1 = u1.oldLen();
    int len2 = u1.newLen();
    if (len2 != u2.oldLen()) {
      throw new IllegalArgumentException("mismatched composition of two changesets");
    }
    int len3 = u2.newLen();
    StringIterator bankIter1 = new StringIterator(u1.charBank());
    StringIterator bankIter2 = new StringIterator(u2.charBank());
    StringBuilder bankAssem = new StringBuilder();

    String newOps =
        applyZip(
            u1.ops(),
            u2.ops(),
            (op1, op2) -> {
              char op1code = op1.opcode;
              char op2code = op2.opcode;
              if (op1code == Op.INSERT && op2code == Op.DELETE) {
                bankIter1.skip(Math.min(op1.chars, op2.chars));
              }
              Op opOut = slicerZipperFunc(op1, op2);
              if (opOut.opcode == Op.INSERT) {
                if (op2code == Op.INSERT) {
                  bankAssem.append(bankIter2.take(opOut.chars));
                } else {
                  bankAssem.append(bankIter1.take(opOut.chars));
                }
              }
              return opOut;
            });

    return pack(len1, len3, newOps, bankAssem.toString());
  }

  public static String follow(String cs1, String cs2, boolean reverseInsertOrder) {
    ChangesetCodec.Unpacked u1 = unpack(cs1);
    ChangesetCodec.Unpacked u2 = unpack(cs2);
    if (u1.oldLen() != u2.oldLen()) {
      throw new IllegalArgumentException("mismatched follow - cannot transform cs1 on top of cs2");
    }
    StringIterator chars1 = new StringIterator(u1.charBank());
    StringIterator chars2 = new StringIterator(u2.charBank());

    int oldLen = u1.newLen();
    int[] oldPos = {0};
    int[] newLen = {0};

    String newOps =
        applyZip(
            u1.ops(),
            u2.ops(),
            (op1, op2) -> {
              Op opOut = new Op();
              if (op1.opcode == Op.INSERT || op2.opcode == Op.INSERT) {
                int whichToDo;
                if (op2.opcode != Op.INSERT) {
                  whichToDo = 1;
                } else if (op1.opcode != Op.INSERT) {
                  whichToDo = 2;
                } else {
                  // both insert. Attribute-driven insert-order overrides (`insertorder: first`)
                  // are not ported — see SPEC-001 §4, they never fire because attribs are
                  // always empty in this port.
                  char firstChar1 = chars1.peek(1).isEmpty() ? '\0' : chars1.peek(1).charAt(0);
                  char firstChar2 = chars2.peek(1).isEmpty() ? '\0' : chars2.peek(1).charAt(0);
                  if (firstChar1 == '\n' && firstChar2 != '\n') {
                    whichToDo = 2;
                  } else if (firstChar1 != '\n' && firstChar2 == '\n') {
                    whichToDo = 1;
                  } else if (reverseInsertOrder) {
                    whichToDo = 2;
                  } else {
                    whichToDo = 1;
                  }
                }
                if (whichToDo == 1) {
                  chars1.skip(op1.chars);
                  opOut.opcode = Op.KEEP;
                  opOut.lines = op1.lines;
                  opOut.chars = op1.chars;
                  op1.opcode = Op.NONE;
                } else {
                  chars2.skip(op2.chars);
                  opOut.copyFrom(op2);
                  op2.opcode = Op.NONE;
                }
              } else if (op1.opcode == Op.DELETE) {
                if (op2.opcode == Op.NONE) {
                  op1.opcode = Op.NONE;
                } else if (op1.chars <= op2.chars) {
                  op2.chars -= op1.chars;
                  op2.lines -= op1.lines;
                  op1.opcode = Op.NONE;
                  if (op2.chars == 0) op2.opcode = Op.NONE;
                } else {
                  op1.chars -= op2.chars;
                  op1.lines -= op2.lines;
                  op2.opcode = Op.NONE;
                }
              } else if (op2.opcode == Op.DELETE) {
                opOut.copyFrom(op2);
                if (op1.opcode == Op.NONE) {
                  op2.opcode = Op.NONE;
                } else if (op2.chars <= op1.chars) {
                  op1.chars -= op2.chars;
                  op1.lines -= op2.lines;
                  op2.opcode = Op.NONE;
                  if (op1.chars == 0) op1.opcode = Op.NONE;
                } else {
                  opOut.lines = op1.lines;
                  opOut.chars = op1.chars;
                  op2.lines -= op1.lines;
                  op2.chars -= op1.chars;
                  op1.opcode = Op.NONE;
                }
              } else if (op1.opcode == Op.NONE) {
                opOut.copyFrom(op2);
                op2.opcode = Op.NONE;
              } else if (op2.opcode == Op.NONE) {
                // Do not copy op1 into opOut: attributes must not leak into the result
                // changeset (matches the source's own fix for EPL issue #1625).
                op1.opcode = Op.NONE;
              } else {
                // both keeps
                opOut.opcode = Op.KEEP;
                if (op1.chars <= op2.chars) {
                  opOut.chars = op1.chars;
                  opOut.lines = op1.lines;
                  op2.chars -= op1.chars;
                  op2.lines -= op1.lines;
                  op1.opcode = Op.NONE;
                  if (op2.chars == 0) op2.opcode = Op.NONE;
                } else {
                  opOut.chars = op2.chars;
                  opOut.lines = op2.lines;
                  op1.chars -= op2.chars;
                  op1.lines -= op2.lines;
                  op2.opcode = Op.NONE;
                }
              }
              switch (opOut.opcode) {
                case Op.KEEP:
                  oldPos[0] += opOut.chars;
                  newLen[0] += opOut.chars;
                  break;
                case Op.DELETE:
                  oldPos[0] += opOut.chars;
                  break;
                case Op.INSERT:
                  newLen[0] += opOut.chars;
                  break;
                default:
                  break;
              }
              return opOut;
            });
    newLen[0] += oldLen - oldPos[0];

    return pack(oldLen, newLen[0], newOps, u2.charBank());
  }

  private static List<Op> opsFromText(char opcode, String text) {
    List<Op> result = new ArrayList<>();
    int lastNewline = text.lastIndexOf('\n');
    if (lastNewline < 0) {
      Op op = new Op(opcode);
      op.chars = text.length();
      op.lines = 0;
      result.add(op);
    } else {
      Op op = new Op(opcode);
      op.chars = lastNewline + 1;
      op.lines = countNewlines(text.substring(0, lastNewline + 1));
      result.add(op);
      Op op2 = new Op(opcode);
      op2.chars = text.length() - (lastNewline + 1);
      op2.lines = 0;
      result.add(op2);
    }
    return result;
  }

  /**
   * Builds the changeset for replacing {@code ndel} characters of {@code orig} starting
   * at {@code start} with {@code ins}. Ported (attribute-free) from the shape of
   * ether/etherpad's {@code makeSplice} — a convenience for building changesets from an
   * edit description rather than hand-encoding the wire format.
   */
  public static String makeSplice(String orig, int start, int ndel, String ins) {
    int oldLen = orig.length();
    if (start < 0) throw new IllegalArgumentException("start must be >= 0");
    if (ndel < 0) throw new IllegalArgumentException("ndel must be >= 0");
    if (start > oldLen) start = oldLen;
    if (ndel > oldLen - start) ndel = oldLen - start;
    String deleted = orig.substring(start, start + ndel);
    int newLen = oldLen - ndel + ins.length();

    SmartOpAssembler assem = new SmartOpAssembler();
    if (start > 0) for (Op op : opsFromText(Op.KEEP, orig.substring(0, start))) assem.append(op);
    if (ndel > 0) for (Op op : opsFromText(Op.DELETE, deleted)) assem.append(op);
    if (!ins.isEmpty()) for (Op op : opsFromText(Op.INSERT, ins)) assem.append(op);
    if (start + ndel < oldLen) for (Op op : opsFromText(Op.KEEP, orig.substring(start + ndel))) assem.append(op);
    assem.endDocument();

    return pack(oldLen, newLen, assem.toString(), ins);
  }
}
