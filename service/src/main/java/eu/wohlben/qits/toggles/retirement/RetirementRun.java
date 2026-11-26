package eu.wohlben.qits.toggles.retirement;

import eu.wohlben.qits.toggles.store.ToggleStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The retirement run</b> (epic qits-2542, qits-2714): a toggle no deployed consumer's pact
 * evaluates gets, after {@code qits.toggles.retire-after} (3 days), one MAINTENANCE ticket "remove
 * toggle {@code <slug>}" in project {@code qits.toggles.retire.project}.
 *
 * <ol>
 *   <li><b>Read</b> qits-maintenance's deployed pact interactions on {@value #PROVIDER}. A failed
 *       read fails the run before anything is decided: a broken read never retires a toggle.
 *   <li><b>Used</b>: every declared slug an evaluation interaction names — its concrete path {@code
 *       /toggles/api/toggle/<slug>/boolean}, else its provider state's {@code slug} param — gets
 *       {@code last_used_at = now}. Only evaluations count: a pact on the configuration doors is
 *       landing's, and keeps nothing alive.
 *   <li><b>Per declared toggle</b>: used, it keeps no ticket — an open one gets a comment and is
 *       dropped OBSOLETE; unused since its last use (else its first declaration) for longer than the
 *       window, it gets a ticket unless it holds one already. A ticket somebody closed is not filed
 *       again: that was a person's decision, and a toggle whose declaration is deleted takes its
 *       usage with it at the next startup.
 * </ol>
 *
 * <p>A dry run reads and decides the same, and writes nothing: no ticket, comment or drop, no usage.
 * A ticket call that fails is reported for its toggle, and the others are still decided.
 */
@ApplicationScoped
public class RetirementRun {

  static final String PROVIDER = "qits-toggles-service";

  static final String DECLARATIONS =
      "service/src/main/java/eu/wohlben/qits/toggles/declarations/Declared.java";

  /** The evaluation path; the slug as {@code Toggle.SLUG_PATTERN} spells it. */
  static final Pattern EVALUATION =
      Pattern.compile("^/toggles/api/toggle/([a-z][a-z0-9-]{1,62})/boolean/?(?:\\?.*)?$");

  static final String EVALUATION_OPERATION = "getToggleBoolean";

  private static final Logger LOG = Logger.getLogger(RetirementRun.class);

  /** What one run did, or on a dry run would do; {@code api.ToggleWire.RetirementReport} on the wire. */
  public record Outcome(
      boolean dryRun,
      Instant at,
      List<String> used,
      List<Filed> filed,
      List<Kept> kept,
      List<Dropped> dropped,
      List<Failed> failed) {}

  /** A removal ticket filed; {@code ticket} null on a dry run. */
  public record Filed(String slug, String ticket, Instant unusedSince) {}

  /** A toggle left as it is: USED, RECENT, TICKET_OPEN or TICKET_CLOSED. */
  public record Kept(String slug, String reason, String ticket) {}

  /** A removal ticket dropped OBSOLETE: its toggle is used again. */
  public record Dropped(String slug, String ticket) {}

  /** A toggle whose ticket call failed. */
  public record Failed(String slug, String error) {}

  @Inject ToggleStore store;

  @Inject PactInteractions interactions;

  @Inject Tickets tickets;

  @ConfigProperty(name = "qits.toggles.retire-after")
  Duration retireAfter;

  @ConfigProperty(name = "qits.toggles.retire.project")
  String project;

  Clock clock = Clock.systemUTC();

  /**
   * One run.
   *
   * @throws PactInteractions.ReadFailed when the deployed pacts could not be read; nothing is filed
   */
  public Outcome run(boolean dryRun) {
    Instant now = clock.instant();
    List<PactInteractions.Interaction> read = interactions.read(PROVIDER);
    List<ToggleStore.StoredToggle> declared = store.list();
    Set<String> slugs = new TreeSet<>();
    declared.forEach(t -> slugs.add(t.slug()));
    Set<String> used = new TreeSet<>(evaluated(read));
    used.retainAll(slugs);
    if (!dryRun) {
      store.markUsed(used, now);
    }

    List<Filed> filed = new ArrayList<>();
    List<Kept> kept = new ArrayList<>();
    List<Dropped> dropped = new ArrayList<>();
    List<Failed> failed = new ArrayList<>();
    for (ToggleStore.StoredToggle toggle : declared) {
      String slug = toggle.slug();
      String ticket = toggle.removalTicket();
      try {
        if (used.contains(slug)) {
          if (ticket != null) {
            Optional<Tickets.State> state = tickets.state(ticket);
            if (state.isPresent() && !state.get().closed()) {
              if (!dryRun) {
                tickets.comment(ticket, usedAgain(slug, now));
                tickets.drop(ticket);
                store.setRemovalTicket(slug, null);
              }
              dropped.add(new Dropped(slug, ticket));
              continue;
            }
            if (!dryRun) {
              store.setRemovalTicket(slug, null);
            }
          }
          kept.add(new Kept(slug, "USED", null));
          continue;
        }
        Instant since = toggle.lastUsedAt() != null ? toggle.lastUsedAt() : toggle.firstDeclaredAt();
        if (!since.plus(retireAfter).isBefore(now)) {
          kept.add(new Kept(slug, "RECENT", ticket));
          continue;
        }
        if (ticket != null) {
          Optional<Tickets.State> state = tickets.state(ticket);
          if (state.isPresent()) {
            kept.add(
                new Kept(
                    slug, state.get().closed() ? "TICKET_CLOSED" : "TICKET_OPEN", ticket));
            continue;
          }
          // The ticket is gone from qits-projects: file a new one.
        }
        if (dryRun) {
          filed.add(new Filed(slug, null, since));
          continue;
        }
        Tickets.Filed made =
            tickets.file(
                project,
                "remove toggle " + slug,
                impetus(slug, since, toggle.lastUsedAt() == null),
                description(slug));
        store.setRemovalTicket(slug, made.ref());
        filed.add(new Filed(slug, made.ref(), since));
      } catch (Tickets.CallFailed e) {
        LOG.warnf("retirement of %s: %s", slug, e.getMessage());
        failed.add(new Failed(slug, e.getMessage()));
      }
    }
    LOG.infof(
        "retirement run%s: %d used, %d filed, %d kept, %d dropped, %d failed",
        dryRun ? " (dry)" : "",
        used.size(),
        filed.size(),
        kept.size(),
        dropped.size(),
        failed.size());
    return new Outcome(
        dryRun, now, List.copyOf(used), filed, kept, dropped, failed);
  }

  /** The slugs the evaluation interactions name: the concrete path's, else the state's param. */
  static Set<String> evaluated(List<PactInteractions.Interaction> interactions) {
    Set<String> slugs = new TreeSet<>();
    for (PactInteractions.Interaction interaction : interactions) {
      String path = interaction.path() == null ? "" : interaction.path();
      Matcher m = EVALUATION.matcher(path);
      boolean evaluation =
          m.matches()
              || EVALUATION_OPERATION.equals(interaction.operationId())
              || path.matches("^/toggles/api/toggle/[^/]+/boolean.*");
      if (!evaluation) {
        continue;
      }
      if (m.matches()) {
        slugs.add(m.group(1));
        continue;
      }
      for (Map<String, String> params : interaction.params()) {
        String slug = params.get("slug");
        if (slug != null && !slug.isBlank()) {
          slugs.add(slug);
        }
      }
    }
    return slugs;
  }

  static String impetus(String slug, Instant since, boolean neverUsed) {
    return neverUsed
        ? "No deployed consumer's pact has used toggle `"
            + slug
            + "` since it was first declared, at "
            + since
            + "."
        : "No deployed consumer's pact has used toggle `" + slug + "` since " + since + ".";
  }

  static String description(String slug) {
    return "Toggle `"
        + slug
        + "` is declared in qits-toggles-service, in `"
        + DECLARATIONS
        + "`. No deployed consumer's pact evaluates it any more (`GET /toggles/api/toggle/"
        + slug
        + "/boolean`), so delete its declaration there and release qits-toggles-service: that is"
        + " the whole fix. The startup reconcile then deletes the toggle with its rules, freezes"
        + " and usage. A consumer that still needs it declares a consumer pact on that path"
        + " instead, and the next retirement run drops this ticket.";
  }

  static String usedAgain(String slug, Instant now) {
    return "Toggle `"
        + slug
        + "` is used again: a deployed consumer's pact evaluates it (seen by qits-toggles'"
        + " retirement run at "
        + now
        + "), so it is no longer to be removed. Dropped by qits-toggles' retirement run.";
  }
}
