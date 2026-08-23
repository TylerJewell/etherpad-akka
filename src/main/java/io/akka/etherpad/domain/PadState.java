package io.akka.etherpad.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * A document and the ordered log of changesets that produced its current revision.
 * SPEC-001 §2. The empty pad (revision 0) is the string {@code "\n"}, matching the
 * source's own invariant that a pad's text always ends in a newline — read from
 * {@code PadMessageHandler.ts}/{@code Pad.ts}, not independently run (question-log,
 * "still open" — this is a boundary condition of an invariant that was run for the
 * general case).
 *
 * @param history {@code history.get(i)} is the revision-{@code i+1} record: the
 *     changeset that turned revision {@code i} into revision {@code i+1}, and who
 *     submitted it (before rebasing, this is what a resubmission is compared against —
 *     SPEC-001 R7)
 */
public record PadState(int revision, String text, List<Revision> history) {

  public record Revision(String authorId, String submittedChangeset, String appliedChangeset) {}

  public static PadState empty() {
    return new PadState(0, "\n", List.of());
  }

  public PadState with(PadEvent.EditApplied event) {
    List<Revision> next = new ArrayList<>(history);
    next.add(new Revision(event.authorId(), event.submittedChangeset(), event.appliedChangeset()));
    return new PadState(
        event.revision(), Changeset.applyToText(event.appliedChangeset(), text), next);
  }

  /** The changeset actually applied for revision {@code r} (1-indexed). */
  public Revision revisionAt(int r) {
    return history.get(r - 1);
  }

  /**
   * @param appliedChangeset the caller's edit, rebased against every revision committed
   *     since {@code baseRevision} — SPEC-001 R6, R7
   * @param oldLenMatches false when the rebased changeset's declared base length no
   *     longer matches this document — SPEC-001 R8; the caller should reject rather
   *     than apply
   */
  public record Rebased(String appliedChangeset, boolean oldLenMatches) {}

  /**
   * Rebases {@code submittedChangeset} — as {@code authorId} sent it, against
   * {@code baseRevision} — to apply cleanly on top of the current head. Ported (as a
   * loop over already-checked {@link Changeset#follow}) from the source's own rebase
   * loop, {@code PadMessageHandler.ts:964-981}.
   */
  public Rebased rebaseFrom(int baseRevision, String authorId, String submittedChangeset) {
    String rebased = submittedChangeset;
    for (int r = baseRevision + 1; r <= revision; r++) {
      Revision committed = revisionAt(r);
      if (submittedChangeset.equals(committed.submittedChangeset())
          && authorId.equals(committed.authorId())) {
        // A retransmission of an edit already committed as revision r: treat everything
        // from here on as a no-op rather than double-applying it. SPEC-001 R7.
        rebased = Changeset.identity(Changeset.oldLen(submittedChangeset));
      }
      rebased = Changeset.follow(committed.appliedChangeset(), rebased, false);
    }
    return new Rebased(rebased, Changeset.oldLen(rebased) == text.length());
  }
}
