package eu.wohlben.qits.toggles.contracts;

import eu.wohlben.qits.toggles.declarations.Declarations;
import eu.wohlben.qits.toggles.declarations.Toggle;
import eu.wohlben.qits.toggles.store.Rule;
import eu.wohlben.qits.toggles.store.ToggleStore;
import eu.wohlben.qits.toggles.support.ContractCallers;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>qits-toggles' provider states</b>: each one stages what one consumer situation needs and hands
 * back its parameters, {@code authorization} among them: the bearer the consumer's pact sends. Two
 * callers: {@link GoldenMasterRecordingTest} runs a state before each operation that names it, and
 * {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <ul>
 *   <li><b>{@code toggle <slug> is declared}</b> ({@link #declared}), one per declared toggle: the
 *       evaluation, asked by a platform service's own client ({@value #CONSUMER_BEARER}), so a
 *       consumer's pact names the slug it evaluates;
 *   <li>{@value #A_TOGGLE_SLUG_IS_NOT_DECLARED}: the evaluation's and the read's refusal;
 *   <li>{@value #A_TOGGLE_IS_DECLARED_ASKED_BY_AN_ADMIN}: the configuration doors, the smoke toggle
 *       {@value #SLUG} as a fresh declaration (version 0, its one STATIC rule), asked by a person
 *       holding {@code qits:admin} ({@value #ADMIN_BEARER});
 *   <li>the rules door's refusals: {@value #A_TOGGLE_IS_DECLARED_SENT_MALFORMED_RULES_BY_AN_ADMIN}
 *       (400), {@value #A_TOGGLE_IS_DECLARED_ASKED_BY_AN_AGENT} (403) and {@value
 *       #A_TOGGLES_RULES_CHANGED_SINCE_AN_ADMIN_READ_THEM} (409).
 * </ul>
 *
 * <p>Applying a state twice is the same state.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_TOGGLE_IS_DECLARED_ASKED_BY_AN_ADMIN =
      "a toggle is declared, asked by an admin";

  /**
   * The evaluation's state for the smoke toggle, named the way every declared toggle's is (epic
   * qits-2542): {@code toggle <slug> is declared}, param {@code slug}, so a consumer's pact names
   * the slug it evaluates concretely. Asked by a platform service's own client ({@value
   * #CONSUMER_BEARER}), as a toggle's consumer evaluates it.
   */
  public static final String TOGGLE_TOGGLES_SMOKE_IS_DECLARED = "toggle toggles-smoke is declared";

  /** The evaluation's refusal: a slug nothing declares, asked by a service's own client. */
  public static final String A_TOGGLE_SLUG_IS_NOT_DECLARED = "a toggle slug is not declared";

  /** The slug {@value #A_TOGGLE_SLUG_IS_NOT_DECLARED} names. */
  public static final String UNDECLARED_SLUG = "no-such-toggle";

  /** The rules door's 403: a toggle is declared, and an agent without a configuring role asks. */
  public static final String A_TOGGLE_IS_DECLARED_ASKED_BY_AN_AGENT =
      "a toggle is declared, asked by an agent";

  /** The rules door's 400: an admin sends a list of the wrong shape. */
  public static final String A_TOGGLE_IS_DECLARED_SENT_MALFORMED_RULES_BY_AN_ADMIN =
      "a toggle is declared, sent malformed rules by an admin";

  /** The rules door's 409: the rules changed (version 1) since the admin read version 0. */
  public static final String A_TOGGLES_RULES_CHANGED_SINCE_AN_ADMIN_READ_THEM =
      "a toggle's rules changed since an admin read them";

  /** An agent's own token, holding no configuring role. */
  public static final String AGENT = "tok-project-agent-contract";

  /** The bearer {@link #AGENT} presents. */
  public static final String AGENT_BEARER = "Bearer contract-qits-agent";

  /**
   * The evaluation state of the declared toggle {@code slug}: {@code toggle <slug> is declared},
   * param {@code slug}. There is one per declared toggle, so a consumer's pact names the slug it
   * evaluates concretely; {@code DeclaredStatesTest} holds every declaration to its callback.
   */
  public static String declared(String slug) {
    return "toggle " + slug + " is declared";
  }

  /** The toggle the states stage: the smoke toggle, declared in this repository. */
  public static final String SLUG = "toggles-smoke";

  /** A person holding {@code qits:admin}, as qits-idp names one: by user id. */
  public static final String ADMIN = "00000000-0000-4000-8000-0000000000ad";

  /** The bearer {@link #ADMIN} presents. */
  public static final String ADMIN_BEARER = "Bearer contract-qits-admin";

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

  @Inject ToggleStore store;

  @Inject Declarations declarations;

  @Inject
  @DataSource("toggles")
  AgroalDataSource toggles;

  public ProviderStates() {
    states.put(A_TOGGLE_IS_DECLARED_ASKED_BY_AN_ADMIN, this::aToggleIsDeclaredAskedByAnAdmin);
    states.put(A_TOGGLE_SLUG_IS_NOT_DECLARED, this::aToggleSlugIsNotDeclared);
    states.put(A_TOGGLE_IS_DECLARED_ASKED_BY_AN_AGENT, this::aToggleIsDeclaredAskedByAnAgent);
    states.put(
        A_TOGGLE_IS_DECLARED_SENT_MALFORMED_RULES_BY_AN_ADMIN, this::aToggleIsDeclaredAskedByAnAdmin);
    states.put(
        A_TOGGLES_RULES_CHANGED_SINCE_AN_ADMIN_READ_THEM, this::aTogglesRulesChangedSinceAnAdminRead);
  }

  /** One evaluation state per declared toggle, from the declarations themselves. */
  @PostConstruct
  void declaredToggles() {
    for (Toggle toggle : declarations.all()) {
      states.put(declared(toggle.slug()), () -> toggleIsDeclared(toggle.slug()));
    }
  }

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

  /** Grant {@link #ADMIN_BEARER} as a person holding {@code qits:admin}. */
  public static void grantAdmin() {
    ContractCallers.bearer(ADMIN_BEARER, ADMIN, Set.of("qits:admin"), Set.of("qits:admin"));
  }

  // --- the states ------------------------------------------------------------------------------

  private Setup aToggleIsDeclaredAskedByAnAdmin() {
    redeclare(SLUG);
    grantAdmin();
    Map<String, String> params = new TreeMap<>();
    params.put("authorization", ADMIN_BEARER);
    params.put("slug", SLUG);
    return new Setup(Collections.unmodifiableMap(params));
  }

  private Setup toggleIsDeclared(String slug) {
    redeclare(slug);
    grantConsumer();
    return params(CONSUMER_BEARER, slug);
  }

  private Setup aToggleSlugIsNotDeclared() {
    store.reconcile(declarations.forReconcile());
    grantConsumer();
    return params(CONSUMER_BEARER, UNDECLARED_SLUG);
  }

  private Setup aToggleIsDeclaredAskedByAnAgent() {
    redeclare(SLUG);
    ContractCallers.bearer(AGENT_BEARER, AGENT, Set.of("qits:agent"), Set.of("qits:agent"));
    return params(AGENT_BEARER, SLUG);
  }

  private Setup aTogglesRulesChangedSinceAnAdminRead() {
    redeclare(SLUG);
    if (store.replaceRules(SLUG, 0, List.of(Rule.always(true))) != ToggleStore.Replaced.REPLACED) {
      throw new IllegalStateException("could not move " + SLUG + " to version 1");
    }
    grantAdmin();
    return params(ADMIN_BEARER, SLUG);
  }

  private static Setup params(String authorization, String slug) {
    Map<String, String> params = new TreeMap<>();
    params.put("authorization", authorization);
    params.put("slug", slug);
    return new Setup(Collections.unmodifiableMap(params));
  }

  /**
   * {@code slug} as a fresh declaration: its row deleted and reconciled again from the declarations,
   * so it is back at version 0 with its one STATIC rule, whatever an earlier state did to it.
   */
  private void redeclare(String slug) {
    try (Connection db = toggles.getConnection();
        PreparedStatement delete =
            db.prepareStatement("delete from toggles.toggle where slug = ?")) {
      delete.setString(1, slug);
      delete.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("could not clear the toggle " + slug, e);
    }
    store.reconcile(declarations.forReconcile());
  }
}
