package eu.wohlben.qits.toggles.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.toggles.declarations.Declarations;
import eu.wohlben.qits.toggles.store.Declaration;
import eu.wohlben.qits.toggles.store.Rule;
import eu.wohlben.qits.toggles.store.ToggleStore;
import eu.wohlben.qits.toggles.support.ContractCallers;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /toggles/api/toggle/{slug}/boolean} through the real chain filter of
 * qits-integrations: a declared toggle answers true or false, an undeclared one 400; one origin
 * trace answers the same even after the rules change; the rules decide in order; a service client's
 * on-behalf-of user is matched, a person's header is not; old freezes are purged.
 */
@QuarkusTest
class EvaluationTest {

  static final String SLUG = "evaluation-test";

  static final String PATH = "/toggles/api/toggle/{slug}/boolean";

  static final String ORIGIN = "X-Qits-Origin-Trace-Id";

  static final String ON_BEHALF_OF = "X-Qits-On-Behalf-Of";

  @Inject ToggleStore store;

  @Inject Declarations declarations;

  @Inject FreezePurge purge;

  @Inject
  @DataSource("toggles")
  AgroalDataSource toggles;

  String service;

  @BeforeEach
  void aFreshToggle() {
    store.reconcile(declarations.forReconcile());
    var with = new ArrayList<>(declarations.forReconcile());
    with.add(new Declaration(SLUG, "an evaluation test", false));
    store.reconcile(with);
    service = ContractCallers.client("dev-qits-consumer");
  }

  @AfterEach
  void backToTheDeclarations() {
    store.reconcile(declarations.forReconcile());
  }

  static String origin() {
    byte[] bytes = new byte[16];
    ThreadLocalRandom.current().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  void rules(Rule... rules) {
    long version = store.find(SLUG).orElseThrow().version();
    assertEquals(ToggleStore.Replaced.REPLACED, store.replaceRules(SLUG, version, List.of(rules)));
  }

  RequestSpecification as(String authorization) {
    return given().header("Authorization", authorization).accept("application/json");
  }

  boolean evaluate(RequestSpecification request) {
    return request
        .get(PATH, SLUG)
        .then()
        .statusCode(200)
        .contentType("application/json")
        .extract()
        .as(Boolean.class);
  }

  @Test
  void aDeclaredToggleAnswersTrueOrFalse() {
    String agent = ContractCallers.caller("tok-project-agent-qits-developer", "qits:agent");
    assertFalse(evaluate(as(agent)), "the declared default");
    rules(Rule.always(true));
    assertTrue(evaluate(as(agent)));
    assertEquals("false", as(service).get(PATH, "toggles-smoke").then().statusCode(200).extract().asString());
  }

  @Test
  void anUndeclaredSlugAnswers400NamingIt() {
    as(service)
        .get(PATH, "no-such-toggle")
        .then()
        .statusCode(400)
        .body("error", equalTo("no toggle is declared as 'no-such-toggle'"));
  }

  @Test
  void anAnonymousCallerIsRefused() {
    given().get(PATH, SLUG).then().statusCode(401);
  }

  @Test
  void onlyTheBooleanTypeHasARoute() {
    as(service).get("/toggles/api/toggle/{slug}/string", SLUG).then().statusCode(404);
  }

  @Test
  void oneOriginTraceAnswersTheSameEvenAfterTheRulesChange() {
    String chain = origin();
    assertFalse(evaluate(as(service).header(ORIGIN, chain)));

    rules(Rule.always(true));

    assertFalse(evaluate(as(service).header(ORIGIN, chain)), "the chain's first answer holds");
    assertTrue(evaluate(as(service).header(ORIGIN, origin())), "a new chain sees the new rules");
  }

  @Test
  void aPersonsCallsAreEachTheirOwnChain() {
    // A person's origin header is ignored: each call starts its own chain at its own trace id.
    String person = ContractCallers.caller("4c1f2e8a-0000-4000-8000-00000000a11c", "qits:user");
    String chain = origin();
    assertFalse(evaluate(as(person).header(ORIGIN, chain)));
    rules(Rule.always(true));
    assertTrue(
        evaluate(as(person).header(ORIGIN, chain)),
        "a person cannot choose a chain to freeze into");
  }

  @Test
  void theFirstMatchingRuleDecidesInTheConfiguredOrder() {
    Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
    rules(
        new Rule(Rule.Kind.USER, false, List.of("alice"), null),
        new Rule(Rule.Kind.TIME, true, null, past),
        Rule.always(false));
    assertFalse(evaluate(as(ContractCallers.caller("alice", "qits:user"))), "user first");
    assertTrue(evaluate(as(ContractCallers.caller("bob", "qits:user"))), "then time");

    rules(
        new Rule(Rule.Kind.USER, false, List.of("alice"), null),
        new Rule(Rule.Kind.TIME, true, null, future),
        Rule.always(false));
    assertFalse(evaluate(as(ContractCallers.caller("bob", "qits:user"))), "then static");
  }

  @Test
  void aServiceClientsOnBehalfOfUserIsMatchedByTheUserRule() {
    rules(new Rule(Rule.Kind.USER, true, List.of("alice"), null), Rule.always(false));
    assertTrue(evaluate(as(service).header(ON_BEHALF_OF, "alice")));
    assertFalse(evaluate(as(service).header(ON_BEHALF_OF, "bob")));
    assertFalse(evaluate(as(service)), "without the header the client speaks for itself");
  }

  @Test
  void aPersonIsMatchedByItsOwnSubjectWhateverHeaderItSends() {
    rules(new Rule(Rule.Kind.USER, true, List.of("alice"), null), Rule.always(false));
    assertFalse(
        evaluate(as(ContractCallers.caller("mallory", "qits:user")).header(ON_BEHALF_OF, "alice")),
        "mallory cannot speak for alice");
    assertTrue(
        evaluate(as(ContractCallers.caller("alice", "qits:user")).header(ON_BEHALF_OF, "mallory")),
        "alice is alice whatever she sends");
    String agent = ContractCallers.caller("tok-project-agent-qits-developer", "qits:agent");
    assertFalse(evaluate(as(agent).header(ON_BEHALF_OF, "alice")), "nor can an agent");
  }

  @Test
  void aFreezeOlderThanAnHourIsPurged() throws SQLException {
    String old = origin();
    String fresh = origin();
    freeze(old, Instant.now().minus(61, ChronoUnit.MINUTES));
    freeze(fresh, Instant.now().minus(59, ChronoUnit.MINUTES));

    purge.purge();

    assertFalse(frozen(old), "older than the 1h ttl");
    assertTrue(frozen(fresh), "younger than the ttl");
  }

  void freeze(String origin, Instant at) throws SQLException {
    try (Connection db = toggles.getConnection();
        PreparedStatement insert =
            db.prepareStatement(
                "insert into toggles.toggle_freeze (slug, origin_trace_id, value, frozen_at)"
                    + " values (?, ?, true, ?)")) {
      insert.setString(1, SLUG);
      insert.setString(2, origin);
      insert.setTimestamp(3, Timestamp.from(at));
      insert.executeUpdate();
    }
  }

  boolean frozen(String origin) throws SQLException {
    try (Connection db = toggles.getConnection();
        PreparedStatement read =
            db.prepareStatement(
                "select 1 from toggles.toggle_freeze where slug = ? and origin_trace_id = ?")) {
      read.setString(1, SLUG);
      read.setString(2, origin);
      try (ResultSet row = read.executeQuery()) {
        return row.next();
      }
    }
  }
}
