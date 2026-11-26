package eu.wohlben.qits.toggles.retirement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.toggles.declarations.Declarations;
import eu.wohlben.qits.toggles.store.Declaration;
import eu.wohlben.qits.toggles.store.ToggleStore;
import eu.wohlben.qits.toggles.support.ContractCallers;
import eu.wohlben.qits.toggles.support.FakePeers;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The retirement run, against {@link FakePeers}: a toggle unused for three days gets one ticket, a
 * used one none; a second run files no duplicate; a failed pact read files nothing; a dry run files
 * nothing and reports what it would; a toggle used again has its open ticket dropped.
 */
@QuarkusTest
class RetirementRunTest {

  static final String SLUG = "retire-test";

  static final String RUNS = "/toggles/api/retirement/runs";

  @Inject ToggleStore store;

  @Inject Declarations declarations;

  @Inject FakePeers fake;

  @Inject
  @DataSource("toggles")
  AgroalDataSource toggles;

  String service;

  @BeforeEach
  void aDeclaredToggle() throws SQLException {
    store.reconcile(declarations.forReconcile());
    var with = new ArrayList<>(declarations.forReconcile());
    with.add(new Declaration(SLUG, "a retirement test", false));
    store.reconcile(with);
    sql("delete from toggles.toggle_usage");
    // Every other toggle fresh, so only this test's toggle is ever old enough to retire.
    sql("update toggles.toggle set first_declared_at = now()");
    fake.reset();
    service = ContractCallers.client("dev-qits-orchestrator");
  }

  @AfterEach
  void backToTheDeclarations() {
    store.reconcile(declarations.forReconcile());
    fake.reset();
  }

  void sql(String statement, Object... args) throws SQLException {
    try (Connection db = toggles.getConnection();
        PreparedStatement update = db.prepareStatement(statement)) {
      for (int i = 0; i < args.length; i++) {
        Object a = args[i];
        if (a instanceof Instant instant) {
          update.setTimestamp(i + 1, Timestamp.from(instant));
        } else {
          update.setObject(i + 1, a);
        }
      }
      update.executeUpdate();
    }
  }

  void declaredDaysAgo(long days) throws SQLException {
    sql(
        "update toggles.toggle set first_declared_at = ? where slug = ?",
        Instant.now().minus(days, ChronoUnit.DAYS),
        SLUG);
  }

  void lastUsedDaysAgo(long days) throws SQLException {
    sql(
        "insert into toggles.toggle_usage (slug, last_used_at) values (?, ?)"
            + " on conflict (slug) do update set last_used_at = excluded.last_used_at",
        SLUG,
        Instant.now().minus(days, ChronoUnit.DAYS));
  }

  JsonPath run(boolean dryRun) {
    return given()
        .header("Authorization", service)
        .queryParam("dryRun", dryRun)
        .post(RUNS)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  /** The report's entry for {@link #SLUG} in {@code list}, or null. */
  static Map<String, Object> entry(JsonPath report, String list) {
    List<Map<String, Object>> entries = report.getList(list);
    return entries.stream().filter(e -> SLUG.equals(e.get("slug"))).findFirst().orElse(null);
  }

  ToggleStore.StoredToggle stored() {
    return store.find(SLUG).orElseThrow();
  }

  @Test
  void aToggleUnusedForThreeDaysGetsOneTicket() throws SQLException {
    declaredDaysAgo(4);

    JsonPath report = run(false);

    Map<String, Object> filed = entry(report, "filed");
    assertNotNull(filed, report.prettify());
    assertEquals("qits-100", filed.get("ticket"));
    List<FakePeers.Call> files = fake.calls("file");
    assertEquals(1, files.stream().filter(c -> c.detail().contains("remove toggle " + SLUG)).count());
    String detail = files.getFirst().detail();
    assertTrue(detail.startsWith("qits | remove toggle " + SLUG + " | "), detail);
    assertTrue(detail.contains("since it was first declared"), detail);
    assertTrue(detail.contains("declarations/Declared.java"), detail);
    assertEquals(List.of("qits-toggles-service"), fake.askedProviders());
    assertEquals("qits-100", stored().removalTicket());
    given()
        .header("Authorization", service)
        .get("/toggles/api/toggles/" + SLUG)
        .then()
        .statusCode(200)
        .body("removalTicket", equalTo("qits-100"))
        .body("lastUsedAt", nullValue());
  }

  @Test
  void aToggleADeployedPactEvaluatesGetsNoTicket() throws SQLException {
    declaredDaysAgo(4);
    fake.evaluated(SLUG);

    JsonPath report = run(false);

    assertEquals("USED", entry(report, "kept").get("reason"));
    assertTrue(report.getList("used").contains(SLUG));
    assertTrue(fake.calls("file").isEmpty(), fake.calls().toString());
    assertNotNull(stored().lastUsedAt(), "the run records the use");
  }

  @Test
  void aSecondRunFilesNoDuplicate() throws SQLException {
    declaredDaysAgo(4);

    run(false);
    JsonPath second = run(false);

    assertEquals(1, fake.calls("file").size());
    Map<String, Object> kept = entry(second, "kept");
    assertEquals("TICKET_OPEN", kept.get("reason"));
    assertEquals("qits-100", kept.get("ticket"));
  }

  @Test
  void aFailedPactReadFilesNothing() throws SQLException {
    declaredDaysAgo(4);
    fake.failReads("qits-maintenance answered HTTP 503");

    given()
        .header("Authorization", service)
        .post(RUNS)
        .then()
        .statusCode(502)
        .body("error", containsString("HTTP 503"));

    assertTrue(fake.calls().isEmpty(), fake.calls().toString());
    assertNull(stored().removalTicket());
    assertNull(stored().lastUsedAt());
  }

  @Test
  void aDryRunFilesNothingAndReportsWhatItWould() throws SQLException {
    declaredDaysAgo(4);
    fake.evaluated("toggles-smoke");

    JsonPath report = run(true);

    assertEquals(true, report.getBoolean("dryRun"));
    Map<String, Object> filed = entry(report, "filed");
    assertNotNull(filed, report.prettify());
    assertNull(filed.get("ticket"));
    assertTrue(fake.calls("file").isEmpty());
    assertNull(stored().removalTicket());
    assertNull(store.find("toggles-smoke").orElseThrow().lastUsedAt(), "no usage is recorded");
  }

  @Test
  void aToggleUsedAgainHasItsOpenTicketDropped() throws SQLException {
    declaredDaysAgo(4);
    run(false);
    fake.evaluated(SLUG);

    JsonPath report = run(false);

    assertEquals("qits-100", entry(report, "dropped").get("ticket"));
    assertEquals(1, fake.calls("comment").size());
    assertTrue(fake.calls("comment").getFirst().detail().contains("is used again"));
    assertEquals(1, fake.calls("drop").size());
    assertEquals("DROPPED", fake.status("qits-100").orElseThrow());
    assertNull(stored().removalTicket());
  }

  @Test
  void aDryRunDropsNothing() throws SQLException {
    declaredDaysAgo(4);
    run(false);
    fake.evaluated(SLUG);

    JsonPath report = run(true);

    assertEquals("qits-100", entry(report, "dropped").get("ticket"));
    assertTrue(fake.calls("drop").isEmpty());
    assertEquals("qits-100", stored().removalTicket());
  }

  @Test
  void aRecentlyUsedOrDeclaredToggleIsKept() throws SQLException {
    declaredDaysAgo(2);
    assertEquals("RECENT", entry(run(false), "kept").get("reason"));

    declaredDaysAgo(10);
    lastUsedDaysAgo(1);
    assertEquals("RECENT", entry(run(false), "kept").get("reason"), "its last use counts");

    lastUsedDaysAgo(4);
    Map<String, Object> filed = entry(run(false), "filed");
    assertNotNull(filed);
    assertTrue(
        fake.calls("file").getFirst().detail().contains("has used toggle `" + SLUG + "` since "),
        fake.calls().toString());
  }

  @Test
  void aClosedTicketIsNotFiledAgainAndAVanishedOneIs() throws SQLException {
    declaredDaysAgo(4);
    run(false);
    fake.ticket("qits-100", "DONE");
    assertEquals("TICKET_CLOSED", entry(run(false), "kept").get("reason"));
    assertEquals(1, fake.calls("file").size());

    store.setRemovalTicket(SLUG, "qits-404");
    Map<String, Object> filed = entry(run(false), "filed");
    assertEquals("qits-101", filed.get("ticket"), "the vanished ticket is filed anew");
  }

  @Test
  void onlyEvaluationsCountAsUse() throws SQLException {
    declaredDaysAgo(4);
    fake.interactions(
        List.of(
            new PactInteractions.Interaction(
                "GET", "/toggles/api/toggles/" + SLUG, "getToggle", List.of(Map.of("slug", SLUG))),
            new PactInteractions.Interaction(
                "PUT",
                "/toggles/api/toggles/" + SLUG + "/rules",
                "putToggleRules",
                List.of(Map.of("slug", SLUG)))));

    assertNotNull(entry(run(false), "filed"), "a configuration pact keeps nothing alive");
  }

  @Test
  void anEvaluationsStateParamNamesItsSlug() throws SQLException {
    declaredDaysAgo(4);
    fake.interactions(
        List.of(
            new PactInteractions.Interaction(
                "GET",
                "/toggles/api/toggle/%7Bslug%7D/boolean",
                "getToggleBoolean",
                List.of(Map.of("slug", SLUG)))));

    assertEquals("USED", entry(run(false), "kept").get("reason"));
  }

  @Test
  void onlyAServiceRunsIt() {
    for (String caller :
        List.of(
            ContractCallers.caller("tok-project-agent-qits-developer", "qits:agent"),
            ContractCallers.caller("admin-person", "qits:admin"),
            ContractCallers.caller("tok-desk-delivery", "qits:agent", "qits:desk-admin"))) {
      given().header("Authorization", caller).post(RUNS).then().statusCode(403);
    }
    given().post(RUNS).then().statusCode(401);
    assertTrue(fake.calls().isEmpty());
  }
}
