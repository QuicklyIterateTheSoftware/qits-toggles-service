package eu.wohlben.qits.toggles.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.toggles.declarations.Declarations;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The startup reconcile: a new declaration gets one STATIC rule, a redeclared toggle keeps its
 * configured rules, and a removed declaration takes its toggle, rules and freezes with it.
 */
@QuarkusTest
class ReconcileTest {

  @Inject ToggleStore store;

  @Inject Declarations declarations;

  @Inject
  @DataSource("toggles")
  AgroalDataSource toggles;

  @AfterEach
  void backToTheDeclarations() {
    store.reconcile(declarations.forReconcile());
  }

  @Test
  void theSmokeToggleIsCreatedFromItsDeclarationWithOneStaticRule() throws Exception {
    // As the startup reconcile meets it on a fresh store (another suite may have changed its rules).
    try (Connection db = toggles.getConnection();
        PreparedStatement delete =
            db.prepareStatement("delete from toggles.toggle where slug = 'toggles-smoke'")) {
      delete.executeUpdate();
    }
    ToggleStore.Reconciled done = store.reconcile(declarations.forReconcile());
    assertTrue(done.created().contains("toggles-smoke"));
    ToggleStore.StoredToggle smoke = store.find("toggles-smoke").orElseThrow();
    assertEquals(0L, smoke.version());
    assertEquals("BOOLEAN", smoke.type());
    assertEquals(false, smoke.declaredDefault());
    assertEquals(List.of(Rule.always(false)), smoke.rules());
  }

  @Test
  void aNewDeclarationGetsOneStaticRuleHoldingItsDefault() {
    List<Declaration> withNew = new ArrayList<>(declarations.forReconcile());
    withNew.add(new Declaration("reconcile-new", "a new one", true));
    ToggleStore.Reconciled done = store.reconcile(withNew);
    assertEquals(Set.of("reconcile-new"), done.created());
    ToggleStore.StoredToggle created = store.find("reconcile-new").orElseThrow();
    assertEquals("a new one", created.description());
    assertEquals(0L, created.version());
    assertEquals(List.of(Rule.always(true)), created.rules());
  }

  @Test
  void aRedeclaredToggleKeepsItsRulesAndTakesTheNewDescription() {
    List<Declaration> before = new ArrayList<>(declarations.forReconcile());
    before.add(new Declaration("reconcile-kept", "first words", false));
    store.reconcile(before);
    List<Rule> configured =
        List.of(
            new Rule(Rule.Kind.USER, true, List.of("tok-agent-a", "user-b"), null),
            new Rule(Rule.Kind.TIME, true, null, Instant.parse("2026-12-01T00:00:00Z")),
            Rule.always(false));
    assertEquals(
        ToggleStore.Replaced.REPLACED, store.replaceRules("reconcile-kept", 0L, configured));

    List<Declaration> after = new ArrayList<>(declarations.forReconcile());
    after.add(new Declaration("reconcile-kept", "second words", true));
    ToggleStore.Reconciled done = store.reconcile(after);

    assertTrue(done.kept().contains("reconcile-kept"));
    ToggleStore.StoredToggle kept = store.find("reconcile-kept").orElseThrow();
    assertEquals("second words", kept.description());
    assertEquals(true, kept.declaredDefault());
    assertEquals(1L, kept.version());
    assertEquals(configured, kept.rules());
  }

  @Test
  void aRemovedDeclarationDeletesTheToggleWithItsRulesAndFreezes() throws Exception {
    List<Declaration> with = new ArrayList<>(declarations.forReconcile());
    with.add(new Declaration("reconcile-gone", "going", false));
    store.reconcile(with);
    try (Connection db = toggles.getConnection();
        PreparedStatement freeze =
            db.prepareStatement(
                "insert into toggles.toggle_freeze (slug, origin_trace_id, value, frozen_at)"
                    + " values ('reconcile-gone', '0af7651916cd43dd8448eb211c80319c', false,"
                    + " now())")) {
      freeze.executeUpdate();
    }

    ToggleStore.Reconciled done = store.reconcile(declarations.forReconcile());

    assertEquals(Set.of("reconcile-gone"), done.deleted());
    assertTrue(store.find("reconcile-gone").isEmpty());
    for (String table : List.of("toggle_rule", "toggle_freeze")) {
      assertEquals(0, count(table, "reconcile-gone"), table + " kept rows of a deleted toggle");
    }
  }

  private int count(String table, String slug) throws Exception {
    try (Connection db = toggles.getConnection();
        PreparedStatement read =
            db.prepareStatement("select count(*) from toggles." + table + " where slug = ?")) {
      read.setString(1, slug);
      try (ResultSet row = read.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }
}
