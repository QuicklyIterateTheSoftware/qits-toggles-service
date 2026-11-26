package eu.wohlben.qits.toggles.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running qits-toggles</b>, the way
 * qits-egress-service does.
 *
 * <p>Each consumer publishes its pact as a jar holding {@code
 * pacts/<consumer>_qits-toggles-service.json}; this repository pins the jar as a test dependency
 * and {@link ClasspathPactLoader} finds it. <b>No consumer pins one yet</b>, so this class carries
 * {@link IgnoreNoPactsToVerify} and {@link ClasspathPactLoader#REQUIRED} is false; drop the one and
 * flip the other when the first jar is pinned.
 *
 * <p>An interaction that carries its own {@code Authorization} — the state's {@code authorization}
 * — is verified as that caller; one that carries none runs as a platform service's own client
 * ({@link ProviderStates#CONSUMER}). Every {@code @State} method delegates to {@link
 * ProviderStates}; {@link #target} fails an unknown state, and an interaction without {@code
 * comments.references.qits-call} or {@code qits-trigger}.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-toggles-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // No pact: nothing to target.
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-toggles does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context, ClassicHttpRequest request) {
    if (context == null) {
      return;
    }
    if (request != null && !request.containsHeader("Authorization")) {
      ProviderStates.grantConsumer();
      request.setHeader("Authorization", ProviderStates.CONSUMER_BEARER);
    }
    context.verifyInteraction();
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.A_TOGGLE_IS_DECLARED_ASKED_BY_AN_ADMIN)
  Map<String, String> aToggleIsDeclaredAskedByAnAdmin() {
    return states.params(ProviderStates.A_TOGGLE_IS_DECLARED_ASKED_BY_AN_ADMIN);
  }

  // One per declared toggle, `toggle <slug> is declared` (ProviderStates.declared): a declaration
  // added without its line here fails ProviderStateCoverageTest, since its golden master is recorded.

  @State(ProviderStates.TOGGLE_TOGGLES_SMOKE_IS_DECLARED)
  Map<String, String> toggleTogglesSmokeIsDeclared() {
    return states.params(ProviderStates.TOGGLE_TOGGLES_SMOKE_IS_DECLARED);
  }

  @State(ProviderStates.A_TOGGLE_SLUG_IS_NOT_DECLARED)
  Map<String, String> aToggleSlugIsNotDeclared() {
    return states.params(ProviderStates.A_TOGGLE_SLUG_IS_NOT_DECLARED);
  }

  @State(ProviderStates.A_TOGGLE_IS_DECLARED_ASKED_BY_AN_AGENT)
  Map<String, String> aToggleIsDeclaredAskedByAnAgent() {
    return states.params(ProviderStates.A_TOGGLE_IS_DECLARED_ASKED_BY_AN_AGENT);
  }

  @State(ProviderStates.A_TOGGLE_IS_DECLARED_SENT_MALFORMED_RULES_BY_AN_ADMIN)
  Map<String, String> aToggleIsDeclaredSentMalformedRulesByAnAdmin() {
    return states.params(ProviderStates.A_TOGGLE_IS_DECLARED_SENT_MALFORMED_RULES_BY_AN_ADMIN);
  }

  @State(ProviderStates.A_TOGGLES_RULES_CHANGED_SINCE_AN_ADMIN_READ_THEM)
  Map<String, String> aTogglesRulesChangedSinceAnAdminReadThem() {
    return states.params(ProviderStates.A_TOGGLES_RULES_CHANGED_SINCE_AN_ADMIN_READ_THEM);
  }

  @State(ProviderStates.A_TOGGLE_NO_DEPLOYED_PACT_HAS_USED_FOR_THREE_DAYS)
  Map<String, String> aToggleNoDeployedPactHasUsedForThreeDays() {
    return states.params(ProviderStates.A_TOGGLE_NO_DEPLOYED_PACT_HAS_USED_FOR_THREE_DAYS);
  }
}
