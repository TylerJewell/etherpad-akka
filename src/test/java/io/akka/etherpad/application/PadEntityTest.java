package io.akka.etherpad.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.etherpad.domain.Changeset;
import io.akka.etherpad.domain.PadEvent;
import io.akka.etherpad.domain.PadState;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R6, R7, R8: the entity rebases a late edit against every revision committed
 * ahead of it, treats an exact resubmission as a no-op, and rejects an edit whose
 * declared base length no longer matches the document.
 */
class PadEntityTest {

  private static EventSourcedTestKit<PadState, PadEvent, PadEntity> kit() {
    return EventSourcedTestKit.of("pad-1", PadEntity::new);
  }

  @Test
  void firstEditAgainstTheEmptyPadIsAppliedAsRevisionOne() {
    var testKit = kit();
    String base = testKit.getState().text();
    String cs = Changeset.makeSplice(base, base.length() - 1, 0, "hello");

    var result = testKit.method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit("alice", 0, cs));

    assertThat(result.getReply().revision()).isEqualTo(1);
    assertThat(result.getReply().text()).isEqualTo("hello\n");
  }

  @Test
  void rejectsAnEditWhoseOldLenNoLongerMatchesTheDocument() {
    var testKit = kit();
    // A changeset built against a 1-char document ("\n") but claiming a 99-char base.
    String staleChangeset = Changeset.pack(99, 99, "", "");

    var result = testKit.method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit("alice", 0, staleChangeset));

    assertThat(result.isError()).isTrue();
  }

  @Test
  void rejectsABaseRevisionThatDoesNotExistYet() {
    var testKit = kit();
    String cs = Changeset.identity(1);

    var result = testKit.method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit("alice", 5, cs));

    assertThat(result.isError()).isTrue();
  }

  @Test
  void twoConcurrentNonOverlappingEditsFromTheSameBaseBothSurvive() {
    var testKit = kit();
    String base = testKit.getState().text(); // "\n"
    String csAlice = Changeset.makeSplice(base, 0, 0, "hello\n");
    String csBob = Changeset.makeSplice(base, 0, 0, "world\n");

    testKit.method(PadEntity::submitEdit).invoke(new PadEntity.SubmitEdit("alice", 0, csAlice));
    // Bob's edit was also built against revision 0, but arrives after Alice's is head.
    var bobResult = testKit.method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit("bob", 0, csBob));

    assertThat(bobResult.getReply().revision()).isEqualTo(2);
    assertThat(bobResult.getReply().text()).contains("hello").contains("world");
  }

  /**
   * Companion to the test above, and the reason `bench/workloads.json` declares an
   * arrival-order experiment: the two edits tie (both submitted against revision 0), and
   * which one is committed first changes the answer — the later-committed edit's insert
   * is placed after the earlier-committed one's, not merged in a commit-order-independent
   * way. Same two edits, opposite commit order, different converged text.
   */
  @Test
  void commitOrderOfTiedConcurrentEditsChangesTheConvergedText() {
    var testKit = kit();
    String base = testKit.getState().text();
    String csAlice = Changeset.makeSplice(base, 0, 0, "alice\n");
    String csBob = Changeset.makeSplice(base, 0, 0, "bob\n");

    testKit.method(PadEntity::submitEdit).invoke(new PadEntity.SubmitEdit("alice", 0, csAlice));
    var aliceFirst = testKit.method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit("bob", 0, csBob));

    var reversedKit = kit();
    reversedKit.method(PadEntity::submitEdit).invoke(new PadEntity.SubmitEdit("bob", 0, csBob));
    var bobFirst = reversedKit.method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit("alice", 0, csAlice));

    assertThat(aliceFirst.getReply().text()).isNotEqualTo(bobFirst.getReply().text());
    assertThat(aliceFirst.getReply().text()).isEqualTo("alice\nbob\n\n");
    assertThat(bobFirst.getReply().text()).isEqualTo("bob\nalice\n\n");
  }

  @Test
  void resubmittingAnAlreadyCommittedEditIsANoOp() {
    var testKit = kit();
    String base = testKit.getState().text();
    String cs = Changeset.makeSplice(base, 0, 0, "hello\n");

    var first = testKit.method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit("alice", 0, cs));
    assertThat(first.getReply().revision()).isEqualTo(1);

    // Alice's client retransmits the same edit, still claiming base revision 0.
    var retransmit = testKit.method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit("alice", 0, cs));

    assertThat(retransmit.getReply().revision()).isEqualTo(2);
    assertThat(retransmit.getReply().text()).isEqualTo(first.getReply().text());
  }
}
