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
 *   <li>filing a removal ticket ({@code createWork}): a MAINTENANCE ticket with a {@code
 *       description}, state {@code a project exists} (qits-2723) — the client sends any title,
 *       impetus and description, so the row sends the recorded ones;
 *   <li>reading a held ticket's status ({@code getWork}), state {@code a maintenance ticket in
 *       detail};
 *   <li>commenting on a ticket whose toggle is used again ({@code addWorkComment}), state {@code a
 *       ticket with a comment} — the client sends any text, so the row sends the recorded one;
 *   <li>dropping that ticket OBSOLETE ({@code setWorkStatus} to DROPPED/OBSOLETE), state {@code a
 *       maintenance ticket in detail} (qits-2723).
 * </ul>
 *
 * <p>The pact is {@code pacts/qits-toggles-service_qits-projects-service.json}; {@code
 * -Dgolden.update=true} rewrites it.
 */
class ProjectsPactTest {

  static final GoldenMasters PROJECTS = GoldenMasters.of("qits-projects-service", "qits-projects");

  static final GoldenInteraction TICKET_FILED =
      GoldenInteraction.of(MaintenancePactTest.RETIREMENT_RUN, "a project exists", "createWork")
          .consumes("id", "qualifiedId");

  static final GoldenInteraction TICKET_READ =
      GoldenInteraction.of(
              MaintenancePactTest.RETIREMENT_RUN, "a maintenance ticket in detail", "getWork")
          .consumes("status");

  static final GoldenInteraction TICKET_COMMENTED =
      GoldenInteraction.of(
          MaintenancePactTest.RETIREMENT_RUN, "a ticket with a comment", "addWorkComment");

  static final GoldenInteraction TICKET_DROPPED =
      GoldenInteraction.of(
          MaintenancePactTest.RETIREMENT_RUN, "a maintenance ticket in detail", "setWorkStatus");

  static final ConsumerPact PACT =
      ConsumerPact.of(
          MaintenancePactTest.CONSUMER,
          PROJECTS,
          TICKET_FILED,
          TICKET_READ,
          TICKET_COMMENTED,
          TICKET_DROPPED);

  static ProjectsTickets over(String url) {
    return ProjectsTickets.over(
        new PeerHttp(HttpClient.newHttpClient(), url + "/projects", () -> Optional.of("contract")));
  }

  @Test
  void anUnusedToggleGetsItsRemovalTicket() {
    PACT.run(
        TICKET_FILED,
        (url, recorded) -> {
          Tickets.Filed filed =
              over(url)
                  .file(
                      recorded.params().get("projectId"),
                      recorded.body().path("title").asText(),
                      recorded.body().path("impetus").asText(),
                      recorded.body().path("description").asText());
          assertEquals(
              PROJECTS.json(TICKET_FILED.state(), TICKET_FILED.operationId())
                  .path("qualifiedId")
                  .asText(),
              filed.ref());
        });
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
  void aTicketWhoseToggleIsUsedAgainIsDroppedObsolete() {
    PACT.run(
        TICKET_DROPPED,
        (url, recorded) -> over(url).drop(PROJECTS.params(TICKET_DROPPED.state()).get("qualifiedId")));
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
