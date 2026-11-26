package eu.wohlben.qits.toggles.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import eu.wohlben.qits.toggles.retirement.MaintenanceInteractions;
import eu.wohlben.qits.toggles.retirement.PactInteractions;
import eu.wohlben.qits.toggles.retirement.PeerHttp;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>What this service asks qits-maintenance-service</b> (qits-2714): the retirement run's read of
 * the deployed consumers' pact interactions on qits-toggles-service ({@code GET
 * /maintenance/api/deployed-pacts/interactions?provider=}, {@link MaintenanceInteractions}), state
 * {@code a provider whose deployed consumers call it} (qits-2702). The run reads each interaction's
 * method, path and operationId; the number of consumers and interactions is not pinned.
 *
 * <p>The pact is {@code pacts/qits-toggles-service_qits-maintenance-service.json}; {@code
 * -Dgolden.update=true} rewrites it.
 */
class MaintenancePactTest {

  static final String CONSUMER = "qits-toggles-service";

  static final GoldenMasters MAINTENANCE =
      GoldenMasters.of("qits-maintenance-service", "qits-maintenance");

  /** qits-orchestrator's gc step, through this service's door. */
  static final Trigger RETIREMENT_RUN = Trigger.operation("runToggleRetirement");

  static final GoldenInteraction INTERACTIONS_READ =
      GoldenInteraction.of(
              RETIREMENT_RUN,
              "a provider whose deployed consumers call it",
              "listDeployedPactInteractions")
          .consumes(
              "pacts[].interactions[].method",
              "pacts[].interactions[].path",
              "pacts[].interactions[].operationId")
          .anyLength("pacts", "pacts[].interactions");

  static final ConsumerPact PACT = ConsumerPact.of(CONSUMER, MAINTENANCE, INTERACTIONS_READ);

  @Test
  void theRunReadsEveryDeployedConsumersInteractions() {
    PACT.run(
        INTERACTIONS_READ,
        (url, recorded) -> {
          String provider = MAINTENANCE.params(INTERACTIONS_READ.state()).get("provider");
          List<PactInteractions.Interaction> read =
              MaintenanceInteractions.over(
                      new PeerHttp(
                          HttpClient.newHttpClient(),
                          url + "/maintenance",
                          () -> Optional.of("contract")))
                  .read(provider);
          assertEquals(1, read.size(), read.toString());
          assertEquals("GET", read.getFirst().method());
          assertTrue(read.getFirst().path().startsWith("/"), read.toString());
        });
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
