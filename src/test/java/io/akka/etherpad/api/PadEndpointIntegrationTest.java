package io.akka.etherpad.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.etherpad.application.PadEntity;
import io.akka.etherpad.domain.Changeset;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R6 (and, incidentally, R1/R8): three callers submit edits through the real
 * HTTP endpoint against one pad, each based on revision 0, and every one of their edits
 * survives in the converged document — proving the entity's rebase loop runs over the
 * wire, not just against the entity object directly the way {@code PadEntityTest} does.
 */
public class PadEndpointIntegrationTest extends TestKitSupport {

  private PadEntity.PadView get(String padId) {
    return httpClient.GET("/pads/" + padId)
        .responseBodyAs(PadEntity.PadView.class)
        .invoke()
        .body();
  }

  private PadEntity.EditResult submit(String padId, String authorId, int baseRevision, String changeset) {
    var response = httpClient.POST("/pads/" + padId + "/edits")
        .withRequestBody(new PadEndpoint.SubmitRequest(authorId, baseRevision, changeset))
        .responseBodyAs(PadEntity.EditResult.class)
        .invoke();
    assertThat(response.status().isSuccess()).isTrue();
    return response.body();
  }

  @Test
  void threeConcurrentEditsFromRevisionZeroAllSurvive() {
    String padId = "integration-pad-1";
    String base = get(padId).text(); // "\n"

    String csAlice = Changeset.makeSplice(base, 0, 0, "alice\n");
    String csBob = Changeset.makeSplice(base, 0, 0, "bob\n");
    String csCarol = Changeset.makeSplice(base, 0, 0, "carol\n");

    submit(padId, "alice", 0, csAlice);
    submit(padId, "bob", 0, csBob);
    var last = submit(padId, "carol", 0, csCarol);

    assertThat(last.revision()).isEqualTo(3);
    assertThat(last.text()).contains("alice").contains("bob").contains("carol");
    assertThat(get(padId).text()).isEqualTo(last.text());
  }
}
