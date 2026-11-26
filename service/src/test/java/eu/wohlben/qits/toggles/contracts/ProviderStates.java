package eu.wohlben.qits.toggles.contracts;

import eu.wohlben.qits.toggles.support.ContractCallers;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * <b>qits-toggles' provider states</b>: each one stages what one consumer situation needs and hands
 * back its parameters, {@code authorization} among them: the bearer the consumer's pact sends. Two
 * callers: {@link GoldenMasterRecordingTest} runs a state before each operation that names it, and
 * {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p>Applying a state twice is the same state.
 */
@ApplicationScoped
public class ProviderStates {

  /** A platform service's own client in the suite: what a toggle's consumer evaluates as. */
  public static final String CONSUMER = "dev-qits-consumer";

  /** The bearer {@link #CONSUMER} presents. */
  public static final String CONSUMER_BEARER = "Bearer contract-qits-consumer";

  /** What a state hands back: its parameters, keys sorted, and no unique tokens. */
  public record Setup(Map<String, String> params) {
    public List<String> uniqueTokens() {
      return List.of();
    }
  }

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  /** Grant {@link #CONSUMER_BEARER} as a platform service's own client. */
  public static void grantConsumer() {
    ContractCallers.bearer(
        CONSUMER_BEARER,
        CONSUMER,
        Set.of("clients/" + CONSUMER, "qits:system"),
        Set.of("clients/" + CONSUMER, "qits:system"));
  }
}
