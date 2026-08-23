package io.akka.etherpad.domain;

import akka.javasdk.annotations.TypeName;

/** What happened to a pad's document. SPEC-001 R6, R7. */
public sealed interface PadEvent {

  /**
   * A caller's edit was accepted as the new head revision, already rebased against
   * every revision committed ahead of the base it was submitted against.
   *
   * @param revision the new head revision number
   * @param authorId who submitted the edit that produced this revision
   * @param submittedChangeset the changeset as the caller sent it, before rebasing —
   *     kept so a later resubmission of the same edit can be recognised (SPEC-001 R7)
   * @param appliedChangeset the changeset actually applied — after rebasing against
   *     every revision committed ahead of the base it was submitted against
   */
  @TypeName("edit-applied")
  record EditApplied(
      int revision, String authorId, String submittedChangeset, String appliedChangeset)
      implements PadEvent {}
}
