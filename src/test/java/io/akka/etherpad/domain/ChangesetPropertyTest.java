package io.akka.etherpad.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Random changeset generation ported from ether/etherpad's own property test
 * ({@code src/tests/frontend/easysync-helper.js}'s {@code randomTestChangeset}, run
 * live against the current source as {@code probes/probe_follow.mjs} — see the
 * question log row 1 and SPEC-001 R3/R4). Attributes are dropped throughout, matching
 * this port's plain-text scope.
 */
class ChangesetPropertyTest {

  private static String randomInlineString(Random r, int len) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) sb.append((char) ('a' + r.nextInt(26)));
    return sb.toString();
  }

  private static String randomMultiline(Random r, int approxMaxLines, int approxMaxCols) {
    int numParts = r.nextInt(approxMaxLines * 2) + 1;
    StringBuilder sb = new StringBuilder();
    sb.append(r.nextInt(2) != 0 ? "\n" : "");
    for (int i = 0; i < numParts; i++) {
      if (i % 2 == 0) {
        if (r.nextInt(10) != 0) {
          sb.append(randomInlineString(r, r.nextInt(approxMaxCols) + 1));
        } else {
          sb.append("\n");
        }
      } else {
        sb.append("\n");
      }
    }
    return sb.toString();
  }

  private record StringOp(String insert, Integer remove, Integer skip) {}

  private static StringOp randomStringOperation(Random r, int numCharsLeft) {
    StringOp result;
    switch (r.nextInt(11)) {
      case 0: result = new StringOp(randomInlineString(r, 1), null, null); break;
      case 1: result = new StringOp(null, 1, null); break;
      case 2: result = new StringOp(null, null, 1); break;
      case 3: result = new StringOp(randomInlineString(r, r.nextInt(4) + 1), null, null); break;
      case 4: result = new StringOp(null, r.nextInt(4) + 1, null); break;
      case 5: result = new StringOp(null, null, r.nextInt(4) + 1); break;
      case 6: result = new StringOp(randomMultiline(r, 5, 20), null, null); break;
      case 7: result = new StringOp(null, (int) Math.round(numCharsLeft * r.nextDouble() * r.nextDouble()), null); break;
      case 8: result = new StringOp(null, null, (int) Math.round(numCharsLeft * r.nextDouble() * r.nextDouble())); break;
      case 9: result = new StringOp(null, numCharsLeft, null); break;
      default: result = new StringOp(null, null, numCharsLeft); break;
    }
    int maxOrig = numCharsLeft - 1;
    if (result.remove() != null) return new StringOp(null, Math.min(result.remove(), maxOrig), null);
    if (result.skip() != null) return new StringOp(null, null, Math.min(result.skip(), maxOrig));
    return result;
  }

  private static String randomTestChangeset(Random r, String origText) {
    StringBuilder charBank = new StringBuilder();
    String[] textLeft = {origText};
    StringBuilder outText = new StringBuilder();
    SmartOpAssemblerHarness opAssem = new SmartOpAssemblerHarness();
    int oldLen = origText.length();

    Runnable doOp = () -> {
      StringOp o = randomStringOperation(r, textLeft[0].length());
      if (o.insert() != null) {
        String txt = o.insert();
        charBank.append(txt);
        outText.append(txt);
        opAssem.appendText(Op.INSERT, txt);
      } else if (o.skip() != null) {
        String txt = textLeft[0].substring(0, o.skip());
        textLeft[0] = textLeft[0].substring(o.skip());
        outText.append(txt);
        opAssem.appendText(Op.KEEP, txt);
      } else if (o.remove() != null) {
        String txt = textLeft[0].substring(0, o.remove());
        textLeft[0] = textLeft[0].substring(o.remove());
        opAssem.appendText(Op.DELETE, txt);
      }
    };

    while (textLeft[0].length() > 1) doOp.run();
    for (int i = 0; i < 5; i++) doOp.run();
    String outTextFinal = outText + "\n";
    String cs = Changeset.pack(oldLen, outTextFinal.length(), opAssem.toString(), charBank.toString());
    Changeset.checkRep(cs);
    return cs;
  }

  /** Reuses the real assembler (package-private) through the same package. */
  private static final class SmartOpAssemblerHarness {
    private final SmartOpAssembler assem = new SmartOpAssembler();

    void appendText(char opcode, String text) {
      int lastNewline = text.lastIndexOf('\n');
      if (lastNewline < 0) {
        Op op = new Op(opcode);
        op.chars = text.length();
        op.lines = 0;
        assem.append(op);
      } else {
        Op op = new Op(opcode);
        op.chars = lastNewline + 1;
        int lines = 0;
        for (int i = 0; i <= lastNewline; i++) if (text.charAt(i) == '\n') lines++;
        op.lines = lines;
        assem.append(op);
        Op op2 = new Op(opcode);
        op2.chars = text.length() - (lastNewline + 1);
        op2.lines = 0;
        assem.append(op2);
      }
    }

    @Override
    public String toString() {
      assem.endDocument();
      return assem.toString();
    }
  }

  @RepeatedTest(30)
  void followComposeConverges() {
    Random r = new Random();
    String startText = randomMultiline(r, 10, 20) + "\n";

    String cs1 = randomTestChangeset(r, startText);
    String cs2 = randomTestChangeset(r, startText);

    String afb = Changeset.checkRep(Changeset.follow(cs1, cs2, false));
    String bfa = Changeset.checkRep(Changeset.follow(cs2, cs1, true));

    String merge1 = Changeset.checkRep(Changeset.compose(cs1, afb));
    String merge2 = Changeset.checkRep(Changeset.compose(cs2, bfa));

    assertThat(Changeset.applyToText(merge1, startText))
        .isEqualTo(Changeset.applyToText(merge2, startText));
  }

  @Test
  void composeMatchesSequentialApply() {
    Random r = new Random();
    for (int i = 0; i < 30; i++) {
      String startText = randomMultiline(r, 10, 20) + "\n";
      String cs1 = randomTestChangeset(r, startText);
      String mid = Changeset.applyToText(cs1, startText);
      String cs2 = randomTestChangeset(r, mid);
      String composed = Changeset.checkRep(Changeset.compose(cs1, cs2));
      assertThat(Changeset.applyToText(composed, startText))
          .isEqualTo(Changeset.applyToText(cs2, mid));
    }
  }
}
