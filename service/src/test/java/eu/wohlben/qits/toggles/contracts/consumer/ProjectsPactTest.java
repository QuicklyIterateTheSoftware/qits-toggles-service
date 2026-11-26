package eu.wohlben.qits.toggles.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.toggles.retirement.PeerHttp;
import eu.wohlben.qits.toggles.retirement.ProjectsTickets;
import eu.wohlben.qits.toggles.retirement.Tickets;
import java.net.http.HttpClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>What this service asks qits-projects-service</b> (qits-2714): the retirement run's removal
 * tickets ({@link ProjectsTickets}).
 *
 * <ul>
 *   <li>reading a held ticket's status ({@code getWork}), state {@code a maintenance ticket in
 *       detail};
 *   <li>commenting on a ticket whose toggle is used again ({@code addWorkComment}), state {@code a
 *       ticket with a comment} — the client sends any text, so the row sends the recorded one.
 * </ul>
 *
 * <p><b>Two calls are not pacted yet</b>, for want of a recording whose request this client sends:
 * filing the MAINTENANCE ticket ({@code createWork}; qits-projects records only BUG and IMPROVEMENT
 * creates, none with a {@code description}), and dropping it OBSOLETE ({@code setWorkStatus} to
 * DROPPED/OBSOLETE; no state records that target). A pact's request body is the recording's, exactly,
 * so neither can be written until qits-projects records them.
 *
 * <p>The pact is {@code pacts/qits-toggles-service_qits-projects-service.json}; {@code
 * -Dgolden.update=true} rewrites it.
 */
class ProjectsPactTest {

  static final GoldenMasters PROJECTS = GoldenMasters.of("qits-projects-service", "qits-projects");

  static final GoldenInteraction TICKET_READ =
      GoldenInteraction.of(
              MaintenancePactTest.RETIREMENT_RUN, "a maintenance ticket in detail", "getWork")
          .consumes("status");

  static final GoldenInteraction TICKET_COMMENTED =
      GoldenInteraction.of(
          MaintenancePactTest.RETIREMENT_RUN, "a ticket with a comment", "addWorkComment");

  static final ConsumerPact PACT =
      ConsumerPact.of(MaintenancePactTest.CONSUMER, PROJECTS, TICKET_READ, TICKET_COMMENTED);

  static ProjectsTickets over(String url) {
    return ProjectsTickets.over(
        new PeerHttp(HttpClient.newHttpClient(), url + "/projects", () -> Optional.of("contract")));
  }

  @Test
  void aHeldTicketsStatusIsRead() {
    PACT.run(
        TICKET_READ,
        (url, recorded) -> {
          String ref = PROJECTS.params(TICKET_READ.state()).get("qualifiedId");
          Optional<Tickets.State> state = over(url).state(ref);
          assertTrue(state.isPresent());
          assertEquals("REPORTED", state.get().status());
        });
  }

  @Test
  void aTicketWhoseToggleIsUsedAgainIsCommentedOn() {
    PACT.run(
        TICKET_COMMENTED,
        (url, recorded) ->
            over(url)
                .comment(
                    PROJECTS.params(TICKET_COMMENTED.state()).get("qualifiedId"),
                    recorded.body().path("body").asText()));
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    assertTrue(PACT.unrecorded().isEmpty(), "every row is recorded: " + PACT.unrecorded());
  }
}
