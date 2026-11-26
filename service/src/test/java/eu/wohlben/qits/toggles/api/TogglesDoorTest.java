package eu.wohlben.qits.toggles.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.toggles.declarations.Declarations;
import eu.wohlben.qits.toggles.store.Rule;
import eu.wohlben.qits.toggles.store.ToggleStore;
import eu.wohlben.qits.toggles.support.ContractCallers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The configuration doors: every authenticated caller reads; only an admin or the delivery desk
 * replaces a toggle's rules; a malformed list answers 400 and a stale version 409, and neither
 * changes anything; no door creates or deletes a toggle.
 */
@QuarkusTest
class TogglesDoorTest {

  static final String SLUG = "door-test";

  static final String RULES = "/toggles/api/toggles/" + SLUG + "/rules";

  static final String VALID =
      "{\"version\":%d,\"rules\":["
          + "{\"kind\":\"USER\",\"value\":true,\"userSubjects\":[\"tok-project-agent-qits-developer\"]},"
          + "{\"kind\":\"TIME\",\"value\":true,\"afterInstant\":\"2026-12-01T00:00:00Z\"},"
          + "{\"kind\":\"STATIC\",\"value\":false}]}";

  @Inject ToggleStore store;

  @Inject Declarations declarations;

  String admin;
  String desk;
  String agent;

  @BeforeEach
  void aFreshToggle() {
    store.reconcile(declarations.forReconcile());
    var with = new ArrayList<>(declarations.forReconcile());
    with.add(new eu.wohlben.qits.toggles.store.Declaration(SLUG, "a door test", false));
    store.reconcile(with);
    admin = ContractCallers.caller("admin-person", "qits:admin");
    desk = ContractCallers.caller("tok-desk-delivery", "qits:agent", "qits:desk", "qits:desk-admin");
    agent = ContractCallers.caller("tok-project-agent-qits-developer", "qits:agent");
  }

  @AfterEach
  void backToTheDeclarations() {
    store.reconcile(declarations.forReconcile());
  }

  @Test
  void everyAuthenticatedCallerReads() {
    given()
        .header("Authorization", agent)
        .get("/toggles/api/toggles")
        .then()
        .statusCode(200)
        .body("slug", hasItem("toggles-smoke"))
        .body("slug", hasItem(SLUG));
    given()
        .header("Authorization", ContractCallers.client("dev-qits-orchestrator"))
        .get("/toggles/api/toggles/" + SLUG)
        .then()
        .statusCode(200)
        .body("slug", equalTo(SLUG))
        .body("type", equalTo("BOOLEAN"))
        .body("description", equalTo("a door test"))
        .body("declaredDefault", equalTo(false))
        .body("version", equalTo(0))
        .body("rules[0].kind", equalTo("STATIC"))
        .body("rules[0].value", equalTo(false));
    given()
        .header("Authorization", agent)
        .get("/toggles/api/toggles/no-such-toggle")
        .then()
        .statusCode(404)
        .body("error", equalTo("no toggle is declared as 'no-such-toggle'"));
  }

  @Test
  void anAdminReplacesTheRules() {
    given()
        .header("Authorization", admin)
        .contentType("application/json")
        .body(VALID.formatted(0))
        .put(RULES)
        .then()
        .statusCode(200)
        .body("version", equalTo(1))
        .body("rules.kind", equalTo(List.of("USER", "TIME", "STATIC")))
        .body("rules[0].userSubjects", equalTo(List.of("tok-project-agent-qits-developer")))
        .body("rules[1].afterInstant", equalTo("2026-12-01T00:00:00Z"))
        .body("rules[2].value", equalTo(false));
    assertEquals(3, store.find(SLUG).orElseThrow().rules().size());
  }

  @Test
  void theDeskReplacesTheRules() {
    given()
        .header("Authorization", desk)
        .contentType("application/json")
        .body(VALID.formatted(0))
        .put(RULES)
        .then()
        .statusCode(200)
        .body("version", equalTo(1));
  }

  @Test
  void anyOtherCallerIsRefused() {
    for (String caller :
        List.of(
            agent,
            ContractCallers.client("dev-qits-orchestrator"),
            ContractCallers.caller("admin-agent", "qits:agent", "qits:admin-agent"))) {
      given()
          .header("Authorization", caller)
          .contentType("application/json")
          .body(VALID.formatted(0))
          .put(RULES)
          .then()
          .statusCode(403);
    }
    given()
        .contentType("application/json")
        .body(VALID.formatted(0))
        .put(RULES)
        .then()
        .statusCode(401);
    assertUnchanged();
  }

  @Test
  void aMalformedListIsRefusedAndChangesNothing() {
    record Case(String rules, Integer position, String error) {}
    List<Case> cases =
        List.of(
            new Case("[]", null, "the rule list is empty: it needs its STATIC rule, last"),
            new Case(
                "[{\"kind\":\"USER\",\"value\":true,\"userSubjects\":[\"a\"]}]",
                0,
                "the last rule must be the STATIC one"),
            new Case(
                "[{\"kind\":\"STATIC\",\"value\":true},{\"kind\":\"STATIC\",\"value\":false}]",
                0,
                "the STATIC rule must be the last one"),
            new Case(
                "[{\"kind\":\"USER\",\"value\":true,\"userSubjects\":[]},{\"kind\":\"STATIC\",\"value\":false}]",
                0,
                "a USER rule needs at least one user subject"),
            new Case(
                "[{\"kind\":\"USER\",\"value\":true,\"userSubjects\":[\" \"]},{\"kind\":\"STATIC\",\"value\":false}]",
                0,
                "a user subject is blank, longer than 255 characters or carries control characters"),
            new Case(
                "[{\"kind\":\"STATIC\",\"value\":true},{\"kind\":\"TIME\",\"value\":true},{\"kind\":\"STATIC\",\"value\":false}]",
                0,
                "the STATIC rule must be the last one"),
            new Case(
                "[{\"kind\":\"TIME\",\"value\":true},{\"kind\":\"STATIC\",\"value\":false}]",
                0,
                "a TIME rule needs its instant"),
            new Case(
                "[{\"kind\":\"PERCENT\",\"value\":true},{\"kind\":\"STATIC\",\"value\":false}]",
                0,
                "the rule's kind 'PERCENT' is not USER, TIME or STATIC"),
            new Case(
                "[{\"kind\":\"STATIC\"}]", 0, "the rule has no value: true or false"));
    for (Case c : cases) {
      var answer =
          given()
              .header("Authorization", admin)
              .contentType("application/json")
              .body("{\"version\":0,\"rules\":" + c.rules() + "}")
              .put(RULES)
              .then()
              .statusCode(400)
              .body("error", equalTo(c.error()));
      if (c.position() == null) {
        answer.body("position", nullValue());
      } else {
        answer.body("position", equalTo(c.position()));
      }
    }
    given()
        .header("Authorization", admin)
        .contentType("application/json")
        .body("{\"rules\":[{\"kind\":\"STATIC\",\"value\":true}]}")
        .put(RULES)
        .then()
        .statusCode(400)
        .body("error", equalTo("the body names no version"));
    assertUnchanged();
  }

  @Test
  void aStaleVersionIsRefusedWith409() {
    given()
        .header("Authorization", admin)
        .contentType("application/json")
        .body(VALID.formatted(0))
        .put(RULES)
        .then()
        .statusCode(200);
    given()
        .header("Authorization", desk)
        .contentType("application/json")
        .body("{\"version\":0,\"rules\":[{\"kind\":\"STATIC\",\"value\":true}]}")
        .put(RULES)
        .then()
        .statusCode(409)
        .body(
            "error",
            equalTo(
                "the rules of 'door-test' changed since version 0 was read; reload and try again"));
    assertEquals(3, store.find(SLUG).orElseThrow().rules().size(), "the stale write changed it");
  }

  @Test
  void anUnknownSlugIsRefusedWith400() {
    given()
        .header("Authorization", admin)
        .contentType("application/json")
        .body(VALID.formatted(0))
        .put("/toggles/api/toggles/no-such-toggle/rules")
        .then()
        .statusCode(400)
        .body("error", equalTo("no toggle is declared as 'no-such-toggle'"));
  }

  @Test
  void noDoorCreatesOrDeletesAToggle() {
    given()
        .header("Authorization", admin)
        .contentType("application/json")
        .body("{\"slug\":\"made-up\"}")
        .post("/toggles/api/toggles")
        .then()
        .statusCode(405);
    given()
        .header("Authorization", admin)
        .delete("/toggles/api/toggles/" + SLUG)
        .then()
        .statusCode(405);
    given()
        .header("Authorization", admin)
        .contentType("application/json")
        .body("{\"slug\":\"made-up\"}")
        .put("/toggles/api/toggles/made-up")
        .then()
        .statusCode(405);
    assertUnchanged();
  }

  private void assertUnchanged() {
    ToggleStore.StoredToggle held = store.find(SLUG).orElseThrow();
    assertEquals(0L, held.version());
    assertEquals(List.of(Rule.always(false)), held.rules());
  }
}
