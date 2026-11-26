package eu.wohlben.qits.toggles.retirement;

import java.util.List;
import java.util.Map;

/**
 * The interactions deployed consumers' pacts hold against one provider — qits-maintenance's {@code
 * GET /maintenance/api/deployed-pacts/interactions?provider=} (qits-2702), {@link
 * MaintenanceInteractions} in the service.
 */
public interface PactInteractions {

  /** One interaction: its method, its concrete path and its provider states' params. */
  record Interaction(String method, String path, String operationId, List<Map<String, String>> params) {
    public Interaction {
      params = List.copyOf(params);
    }
  }

  /** A read that did not answer the interactions: the run that asked files nothing. */
  class ReadFailed extends RuntimeException {
    public ReadFailed(String message) {
      super(message);
    }
  }

  /**
   * Every interaction of every deployed consumer's pact on {@code provider}.
   *
   * @throws ReadFailed when qits-maintenance did not answer them, whatever the reason
   */
  List<Interaction> read(String provider);
}
