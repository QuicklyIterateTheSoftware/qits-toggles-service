package eu.wohlben.qits.toggles.retirement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PactInteractions} over qits-maintenance's {@code GET
 * /maintenance/api/deployed-pacts/interactions?provider=}: per deployed consumer its pact's
 * interactions ({@code pacts[].interactions[]}, each {@code method}, {@code path}, {@code
 * operationId} and {@code providerStates[].params}). Anything but a 2xx answering that shape is a
 * {@link PactInteractions.ReadFailed}: the retirement run fails closed on it.
 */
@ApplicationScoped
public class MaintenanceInteractions implements PactInteractions {

  static final String PATH = "/api/deployed-pacts/interactions?provider=";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject Peers peers;

  private PeerHttp over;

  /** The same reads over {@code http} — a pact mock server in the consumer test. */
  public static MaintenanceInteractions over(PeerHttp http) {
    MaintenanceInteractions interactions = new MaintenanceInteractions();
    interactions.over = http;
    return interactions;
  }

  @Override
  public List<Interaction> read(String provider) {
    PeerHttp http = over != null ? over : peers.maintenance();
    String path = PATH + URLEncoder.encode(provider, StandardCharsets.UTF_8);
    PeerHttp.Answer answer = http.get(path);
    if (!answer.ok()) {
      throw new ReadFailed(
          "qits-maintenance did not answer the deployed pacts of "
              + provider
              + ": GET "
              + http.url(path)
              + " answered "
              + answer.brief());
    }
    JsonNode pacts;
    try {
      pacts = JSON.readTree(answer.body()).get("pacts");
    } catch (Exception e) {
      throw new ReadFailed("qits-maintenance answered no JSON for " + provider + ": " + answer.brief());
    }
    if (pacts == null || !pacts.isArray()) {
      throw new ReadFailed("qits-maintenance answered no pacts[] for " + provider + ": " + answer.brief());
    }
    List<Interaction> out = new ArrayList<>();
    for (JsonNode pact : pacts) {
      for (JsonNode interaction : pact.path("interactions")) {
        List<Map<String, String>> params = new ArrayList<>();
        for (JsonNode state : interaction.path("providerStates")) {
          Map<String, String> values = new LinkedHashMap<>();
          state.path("params").properties().forEach(p -> values.put(p.getKey(), p.getValue().asText()));
          params.add(values);
        }
        out.add(
            new Interaction(
                interaction.path("method").asText(null),
                interaction.path("path").asText(null),
                interaction.path("operationId").asText(null),
                params));
      }
    }
    return out;
  }
}
