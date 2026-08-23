package io.akka.etherpad.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.etherpad.domain.Changeset;
import io.akka.etherpad.domain.PadEvent;
import io.akka.etherpad.domain.PadState;

/**
 * A shared document. Concurrent edits converge because the entity serializes commands
 * to one pad id (the platform's own guarantee — SPEC-001 §4 open decision) and rebases
 * every incoming edit against everything committed since the revision it was submitted
 * against, the same loop the source runs in {@code PadMessageHandler.ts:964-981} —
 * SPEC-001 R6, R7, R8.
 */
@Component(id = "pad")
public class PadEntity extends EventSourcedEntity<PadState, PadEvent> {

  public PadEntity(EventSourcedEntityContext context) {}

  public record SubmitEdit(String authorId, int baseRevision, String changeset) {}

  public record EditResult(int revision, String text, String appliedChangeset) {}

  public record PadView(int revision, String text) {}

  @Override
  public PadState emptyState() {
    return PadState.empty();
  }

  public Effect<EditResult> submitEdit(SubmitEdit command) {
    PadState state = currentState();
    if (command.baseRevision() < 0 || command.baseRevision() > state.revision()) {
      return effects().error(
          "base revision " + command.baseRevision() + " does not exist (head is "
              + state.revision() + ")");
    }

    PadState.Rebased rebased =
        state.rebaseFrom(command.baseRevision(), command.authorId(), command.changeset());
    if (!rebased.oldLenMatches()) {
      return effects().error(
          "can't apply changeset with oldLen " + Changeset.oldLen(rebased.appliedChangeset())
              + " to document of length " + state.text().length());
    }

    int newRevision = state.revision() + 1;
    String appliedChangeset = rebased.appliedChangeset();
    var event = new PadEvent.EditApplied(
        newRevision, command.authorId(), command.changeset(), appliedChangeset);
    return effects()
        .persist(event)
        .thenReply(newState -> new EditResult(newRevision, newState.text(), appliedChangeset));
  }

  public ReadOnlyEffect<PadView> get() {
    PadState state = currentState();
    return effects().reply(new PadView(state.revision(), state.text()));
  }

  @Override
  public PadState applyEvent(PadEvent event) {
    return switch (event) {
      case PadEvent.EditApplied e -> currentState().with(e);
    };
  }
}
