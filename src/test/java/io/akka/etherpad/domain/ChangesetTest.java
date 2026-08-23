package io.akka.etherpad.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests ported from the hand-built examples run against the real source in
 * {@code probes/probe_examples.mjs} — see SPEC-001 R1, R2, R5 and question-log rows 1-4.
 */
class ChangesetTest {

  private static final String BASE = "hello world\n";

  @Test
  void applyRejectsLengthMismatch() {
    String cs = Changeset.identity(BASE.length());
    assertThatThrownBy(() -> Changeset.applyToText(cs, "short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mismatched apply");
  }

  @Test
  void checkRepRejectsShortBank() {
    // Claims to insert 2 chars from the bank but the bank ("x") holds only 1.
    assertThatThrownBy(() -> Changeset.checkRep("Z:5>1+2=1$x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not enough chars in charBank");
  }

  @Test
  void identityLeavesTextUnchanged() {
    String id = Changeset.identity(BASE.length());
    assertThat(Changeset.applyToText(id, BASE)).isEqualTo(BASE);
  }

  @Test
  void nonOverlappingConcurrentEditsConverge() {
    String a = Changeset.makeSplice(BASE, 5, 0, "!!");
    String b = Changeset.makeSplice(BASE, 6, 0, "big ");

    String afb = Changeset.follow(a, b, false);
    String bfa = Changeset.follow(b, a, true);

    String mergedFromA = Changeset.applyToText(Changeset.compose(a, afb), BASE);
    String mergedFromB = Changeset.applyToText(Changeset.compose(b, bfa), BASE);

    assertThat(mergedFromA).isEqualTo(mergedFromB);
    assertThat(mergedFromA).isEqualTo("hello!! big world\n");
  }

  @Test
  void sameSpanConflictConcatenatesBoth() {
    // Both edits replace "world" (chars 6-11) with different text.
    String c = Changeset.makeSplice(BASE, 6, 5, "earth");
    String d = Changeset.makeSplice(BASE, 6, 5, "moon!");

    String cfd = Changeset.follow(c, d, false);
    String dfc = Changeset.follow(d, c, true);

    String mergedC = Changeset.applyToText(Changeset.compose(c, cfd), BASE);
    String mergedD = Changeset.applyToText(Changeset.compose(d, dfc), BASE);

    assertThat(mergedC).isEqualTo(mergedD);
    // Neither replacement is discarded: both survive, concatenated.
    assertThat(mergedC).isEqualTo("hello earthmoon!\n");
  }
}
