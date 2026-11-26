package eu.wohlben.qits.toggles.store;

import eu.wohlben.qits.db.DbRetry;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * <b>The toggles store</b>: the declared toggles ({@code toggle}) and their ordered rules ({@code
 * toggle_rule}), in schema {@code toggles}.
 *
 * <p><b>Plain JDBC, not entities</b>, as qits-egress keeps its store: a toggle is read whole with its
 * rules and written in one transaction, and no API maps a row field by field. Every call is wrapped
 * in {@link DbRetry}'s patience, which waits out a postgres that is moving; each write is one
 * transaction, so a retried one is all or nothing.
 *
 * <p><b>The only writer of {@code toggle} rows is {@link #reconcile}</b>, run at startup from the
 * declarations in code. Rules are replaced whole, under the version the writer read ({@link
 * #replaceRules}).
 *
 * <p><b>Freezes</b> ({@code toggle_freeze}) hold the first answer per (toggle, origin trace id):
 * {@link #evaluate} writes one with {@code insert … on conflict do nothing} and answers the row that
 * stands, so replicas racing on one chain agree; {@link #purgeFreezes} drops the old ones.
 */
@ApplicationScoped
public class ToggleStore {

  /** The one type there is. */
  public static final String BOOLEAN = "BOOLEAN";

  /** The advisory lock two replicas starting at once take, so one reconciles after the other. */
  static final String RECONCILE_LOCK = "qits-toggles-reconcile";

  /** A toggle as stored: the declaration's facts, its rules in order and its version. */
  public record StoredToggle(
      String slug,
      String type,
      String description,
      boolean declaredDefault,
      Instant firstDeclaredAt,
      long version,
      List<Rule> rules) {

    public StoredToggle {
      rules = List.copyOf(rules);
    }
  }

  /** What a reconcile did: the slugs created, kept and deleted, each sorted. */
  public record Reconciled(Set<String> created, Set<String> kept, Set<String> deleted) {}

  /** What a rules replace did. */
  public enum Replaced {
    /** The rules are replaced and the version moved by one. */
    REPLACED,
    /** No toggle has that slug. */
    UNKNOWN,
    /** The version named is not the one held: someone else changed the rules first. */
    STALE
  }

  @Inject
  @DataSource("toggles")
  AgroalDataSource toggles;

  /**
   * Bring the store in line with the declarations: a new slug gets its row and a single STATIC rule
   * holding its default; an existing slug keeps its rules, and only its description and declared
   * default are updated; a slug no longer declared is deleted, with its rules and freezes.
   *
   * <p>One transaction, under an advisory lock, so two replicas starting at once reconcile one after
   * the other. The declarations are expected to be checked already (unique, well-formed slugs).
   */
  public Reconciled reconcile(List<Declaration> declarations) {
    return DbRetry.call(
        "reconcile the declared toggles",
        () -> {
          try (Connection db = toggles.getConnection()) {
            db.setAutoCommit(false);
            try {
              Reconciled done = reconcile(db, declarations);
              db.commit();
              return done;
            } catch (SQLException | RuntimeException e) {
              db.rollback();
              throw e;
            }
          } catch (SQLException e) {
            throw new IllegalStateException("reconciling the declared toggles failed", e);
          }
        });
  }

  private static Reconciled reconcile(Connection db, List<Declaration> declarations)
      throws SQLException {
    try (PreparedStatement lock = db.prepareStatement("select pg_advisory_xact_lock(hashtext(?))")) {
      lock.setString(1, RECONCILE_LOCK);
      lock.execute();
    }
    Set<String> held = new TreeSet<>();
    try (PreparedStatement read = db.prepareStatement("select slug from toggles.toggle");
        ResultSet rows = read.executeQuery()) {
      while (rows.next()) {
        held.add(rows.getString(1));
      }
    }
    Set<String> created = new TreeSet<>();
    Set<String> kept = new TreeSet<>();
    for (Declaration declaration : declarations) {
      if (held.remove(declaration.slug())) {
        try (PreparedStatement update =
            db.prepareStatement(
                "update toggles.toggle set description = ?, declared_default = ? where slug = ?")) {
          update.setString(1, declaration.description());
          update.setBoolean(2, declaration.defaultValue());
          update.setString(3, declaration.slug());
          update.executeUpdate();
        }
        kept.add(declaration.slug());
        continue;
      }
      try (PreparedStatement insert =
          db.prepareStatement(
              "insert into toggles.toggle"
                  + " (slug, type, description, declared_default, first_declared_at, version)"
                  + " values (?, ?, ?, ?, now(), 0)")) {
        insert.setString(1, declaration.slug());
        insert.setString(2, BOOLEAN);
        insert.setString(3, declaration.description());
        insert.setBoolean(4, declaration.defaultValue());
        insert.executeUpdate();
      }
      insertRules(db, declaration.slug(), List.of(Rule.always(declaration.defaultValue())));
      created.add(declaration.slug());
    }
    // What is left was held and is no longer declared: its rules and freezes go by cascade.
    for (String undeclared : held) {
      try (PreparedStatement delete =
          db.prepareStatement("delete from toggles.toggle where slug = ?")) {
        delete.setString(1, undeclared);
        delete.executeUpdate();
      }
    }
    return new Reconciled(created, kept, held);
  }

  /** Every toggle with its rules, by slug. */
  public List<StoredToggle> list() {
    return DbRetry.call(
        "list the toggles",
        () -> {
          try (Connection db = toggles.getConnection()) {
            return snapshot(db, null);
          } catch (SQLException e) {
            throw new IllegalStateException("listing the toggles failed", e);
          }
        });
  }

  /** One toggle with its rules, or empty when no toggle has that slug. */
  public Optional<StoredToggle> find(String slug) {
    return DbRetry.call(
        "read the toggle " + slug,
        () -> {
          try (Connection db = toggles.getConnection()) {
            return snapshot(db, slug).stream().findFirst();
          } catch (SQLException e) {
            throw new IllegalStateException("reading the toggle " + slug + " failed", e);
          }
        });
  }

  /**
   * Replace a toggle's rules whole, when {@code version} is the one held; the version then moves by
   * one. The rules are expected to be checked already (exactly one STATIC rule, last; a USER rule
   * with subjects; a TIME rule with an instant): the table's checks refuse the rest.
   */
  public Replaced replaceRules(String slug, long version, List<Rule> rules) {
    return DbRetry.call(
        "replace the rules of " + slug,
        () -> {
          try (Connection db = toggles.getConnection()) {
            db.setAutoCommit(false);
            try {
              Replaced done = replaceRules(db, slug, version, rules);
              if (done == Replaced.REPLACED) {
                db.commit();
              } else {
                db.rollback();
              }
              return done;
            } catch (SQLException | RuntimeException e) {
              db.rollback();
              throw e;
            }
          } catch (SQLException e) {
            throw new IllegalStateException("replacing the rules of " + slug + " failed", e);
          }
        });
  }

  private static Replaced replaceRules(Connection db, String slug, long version, List<Rule> rules)
      throws SQLException {
    try (PreparedStatement bump =
        db.prepareStatement(
            "update toggles.toggle set version = version + 1 where slug = ? and version = ?")) {
      bump.setString(1, slug);
      bump.setLong(2, version);
      if (bump.executeUpdate() == 0) {
        try (PreparedStatement exists =
            db.prepareStatement("select 1 from toggles.toggle where slug = ?")) {
          exists.setString(1, slug);
          try (ResultSet row = exists.executeQuery()) {
            return row.next() ? Replaced.STALE : Replaced.UNKNOWN;
          }
        }
      }
    }
    try (PreparedStatement delete =
        db.prepareStatement("delete from toggles.toggle_rule where slug = ?")) {
      delete.setString(1, slug);
      delete.executeUpdate();
    }
    insertRules(db, slug, rules);
    return Replaced.REPLACED;
  }

  /**
   * The answer of {@code slug} for the chain {@code originTraceId}, frozen: the stored answer when
   * the chain already has one, otherwise {@code walk} over the toggle's rules, stored and then read
   * back. Two replicas evaluating one chain at once both answer the row that was written first —
   * {@code insert … on conflict do nothing} keeps it, and the read after it returns it.
   *
   * @param walk the rules → the answer; only called when the chain has no answer yet
   * @return the answer, or empty when no toggle is declared as {@code slug}
   */
  public Optional<Boolean> evaluate(
      String slug, String originTraceId, Function<List<Rule>, Boolean> walk) {
    return DbRetry.call(
        "evaluate the toggle " + slug,
        () -> {
          try (Connection db = toggles.getConnection()) {
            Optional<Boolean> frozen = frozen(db, slug, originTraceId);
            if (frozen.isPresent()) {
              return frozen;
            }
            Optional<StoredToggle> toggle = snapshot(db, slug).stream().findFirst();
            if (toggle.isEmpty()) {
              return Optional.<Boolean>empty();
            }
            db.setAutoCommit(true);
            boolean value = walk.apply(toggle.get().rules());
            try (PreparedStatement insert =
                db.prepareStatement(
                    "insert into toggles.toggle_freeze (slug, origin_trace_id, value, frozen_at)"
                        + " values (?, ?, ?, now()) on conflict (slug, origin_trace_id) do nothing")) {
              insert.setString(1, slug);
              insert.setString(2, originTraceId);
              insert.setBoolean(3, value);
              insert.executeUpdate();
            } catch (SQLException e) {
              if (FOREIGN_KEY_VIOLATION.equals(e.getSQLState())) {
                // Undeclared between the read and the write: a reconcile deleted it.
                return Optional.<Boolean>empty();
              }
              throw e;
            }
            return frozen(db, slug, originTraceId);
          } catch (SQLException e) {
            throw new IllegalStateException("evaluating the toggle " + slug + " failed", e);
          }
        });
  }

  /** Delete every freeze made before {@code before}; answers how many went. */
  public int purgeFreezes(Instant before) {
    return DbRetry.call(
        "purge the old freezes",
        () -> {
          try (Connection db = toggles.getConnection();
              PreparedStatement delete =
                  db.prepareStatement("delete from toggles.toggle_freeze where frozen_at < ?")) {
            delete.setTimestamp(1, Timestamp.from(before));
            return delete.executeUpdate();
          } catch (SQLException e) {
            throw new IllegalStateException("purging the old freezes failed", e);
          }
        });
  }

  /** SQLSTATE foreign_key_violation. */
  private static final String FOREIGN_KEY_VIOLATION = "23503";

  private static Optional<Boolean> frozen(Connection db, String slug, String originTraceId)
      throws SQLException {
    try (PreparedStatement read =
        db.prepareStatement(
            "select value from toggles.toggle_freeze where slug = ? and origin_trace_id = ?")) {
      read.setString(1, slug);
      read.setString(2, originTraceId);
      try (ResultSet row = read.executeQuery()) {
        return row.next() ? Optional.of(row.getBoolean(1)) : Optional.empty();
      }
    }
  }

  private static void insertRules(Connection db, String slug, List<Rule> rules)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "insert into toggles.toggle_rule"
                + " (slug, position, kind, value, user_subjects, after_instant)"
                + " values (?, ?, ?, ?, ?, ?)")) {
      for (int position = 0; position < rules.size(); position++) {
        Rule rule = rules.get(position);
        insert.setString(1, slug);
        insert.setInt(2, position);
        insert.setString(3, rule.kind().name());
        insert.setBoolean(4, rule.value());
        if (rule.userSubjects() == null) {
          insert.setNull(5, Types.ARRAY);
        } else {
          insert.setArray(5, db.createArrayOf("text", rule.userSubjects().toArray()));
        }
        if (rule.afterInstant() == null) {
          insert.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
          insert.setTimestamp(6, Timestamp.from(rule.afterInstant()));
        }
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  /** {@link #read} in one repeatable-read transaction, so a toggle's version and rules agree. */
  private static List<StoredToggle> snapshot(Connection db, String slug) throws SQLException {
    db.setAutoCommit(false);
    db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    try {
      return read(db, slug);
    } finally {
      db.rollback();
    }
  }

  /** The toggles (one when {@code slug} is given), each with its rules in order. */
  private static List<StoredToggle> read(Connection db, String slug) throws SQLException {
    Map<String, Object[]> facts = new LinkedHashMap<>();
    String where = slug == null ? "" : " where slug = ?";
    try (PreparedStatement read =
        db.prepareStatement(
            "select slug, type, description, declared_default, first_declared_at, version"
                + " from toggles.toggle"
                + where
                + " order by slug")) {
      if (slug != null) {
        read.setString(1, slug);
      }
      try (ResultSet row = read.executeQuery()) {
        while (row.next()) {
          facts.put(
              row.getString(1),
              new Object[] {
                row.getString(2),
                row.getString(3),
                row.getBoolean(4),
                row.getObject(5, OffsetDateTime.class).toInstant(),
                row.getLong(6)
              });
        }
      }
    }
    Map<String, List<Rule>> rules = new LinkedHashMap<>();
    facts.keySet().forEach(s -> rules.put(s, new ArrayList<>()));
    try (PreparedStatement read =
        db.prepareStatement(
            "select slug, kind, value, user_subjects, after_instant from toggles.toggle_rule"
                + where
                + " order by slug, position")) {
      if (slug != null) {
        read.setString(1, slug);
      }
      try (ResultSet row = read.executeQuery()) {
        while (row.next()) {
          List<Rule> list = rules.get(row.getString(1));
          Array subjects = row.getArray(4);
          OffsetDateTime after = row.getObject(5, OffsetDateTime.class);
          list.add(
              new Rule(
                  Rule.Kind.valueOf(row.getString(2)),
                  row.getBoolean(3),
                  subjects == null ? null : List.of((String[]) subjects.getArray()),
                  after == null ? null : after.toInstant()));
        }
      }
    }
    List<StoredToggle> out = new ArrayList<>();
    facts.forEach(
        (s, f) ->
            out.add(
                new StoredToggle(
                    s,
                    (String) f[0],
                    (String) f[1],
                    (Boolean) f[2],
                    (Instant) f[3],
                    (Long) f[4],
                    rules.get(s))));
    return out;
  }
}
