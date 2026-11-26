package eu.wohlben.qits.toggles.api;

import eu.wohlben.qits.toggles.retirement.RetirementRun;
import eu.wohlben.qits.toggles.store.Rule;
import eu.wohlben.qits.toggles.store.ToggleStore;
import java.time.Instant;
import java.util.List;

/** The JSON shapes of the configuration doors, each registered in {@link ApiWireReflection}. */
public final class ToggleWire {

  private ToggleWire() {}

  /**
   * A toggle as the configuration doors answer it.
   *
   * @param slug the declared slug
   * @param type {@code BOOLEAN}, the only type
   * @param description the declaration's description
   * @param declaredDefault the declaration's default, what a new toggle's STATIC rule answered
   * @param firstDeclaredAt when the declaration was first deployed
   * @param version the version a rules replace names
   * @param rules the ordered rules, the STATIC one last
   * @param lastUsedAt when a retirement run last saw a deployed consumer's pact evaluate it; null
   *     while none ever has
   * @param removalTicket its open "remove toggle" ticket (qualified id, else entity id); null while
   *     none is open
   */
  public record ToggleView(
      String slug,
      String type,
      String description,
      boolean declaredDefault,
      Instant firstDeclaredAt,
      long version,
      List<RuleView> rules,
      Instant lastUsedAt,
      String removalTicket) {

    static ToggleView of(ToggleStore.StoredToggle stored) {
      return new ToggleView(
          stored.slug(),
          stored.type(),
          stored.description(),
          stored.declaredDefault(),
          stored.firstDeclaredAt(),
          stored.version(),
          stored.rules().stream().map(RuleView::of).toList(),
          stored.lastUsedAt(),
          stored.removalTicket());
    }
  }

  /**
   * One rule, answered and sent alike.
   *
   * @param kind {@code USER}, {@code TIME} or {@code STATIC}
   * @param value what the rule answers when it matches
   * @param userSubjects a USER rule's bearer subjects (a person's user id, an agent's {@code tok-…}
   *     subject); null otherwise
   * @param afterInstant the instant from which a TIME rule matches; null otherwise
   */
  public record RuleView(
      String kind, Boolean value, List<String> userSubjects, Instant afterInstant) {

    static RuleView of(Rule rule) {
      return new RuleView(
          rule.kind().name(), rule.value(), rule.userSubjects(), rule.afterInstant());
    }
  }

  /**
   * The body of {@code PUT /toggles/{slug}/rules}: the whole ordered list, and the version it was
   * read at.
   */
  public record RulesReplace(Long version, List<RuleView> rules) {}

  /**
   * A refusal: what is wrong, and for a malformed rule list the position (from 0) of the rule it
   * names, null when it names the list as a whole.
   */
  public record Problem(String error, Integer position) {}

  /**
   * What one retirement run did ({@code POST /retirement/runs}), or on a dry run would do.
   *
   * @param dryRun true when nothing was filed, commented, dropped or recorded
   * @param at the run's instant
   * @param used the declared slugs a deployed consumer's pact evaluates, sorted
   * @param filed the removal tickets filed
   * @param kept the declared toggles left as they are, each with why
   * @param dropped the removal tickets dropped because their toggle is used again
   * @param failed the toggles whose ticket call failed; the others were still decided
   */
  public record RetirementReport(
      boolean dryRun,
      Instant at,
      List<String> used,
      List<Filed> filed,
      List<Kept> kept,
      List<Dropped> dropped,
      List<Failed> failed) {

    static RetirementReport of(RetirementRun.Outcome o) {
      return new RetirementReport(
          o.dryRun(),
          o.at(),
          o.used(),
          o.filed().stream().map(f -> new Filed(f.slug(), f.ticket(), f.unusedSince())).toList(),
          o.kept().stream().map(k -> new Kept(k.slug(), k.reason(), k.ticket())).toList(),
          o.dropped().stream().map(d -> new Dropped(d.slug(), d.ticket())).toList(),
          o.failed().stream().map(f -> new Failed(f.slug(), f.error())).toList());
    }
  }

  /**
   * A removal ticket filed (or, on a dry run, to be filed).
   *
   * @param slug the toggle
   * @param ticket the ticket's qualified id, else its entity id; null on a dry run
   * @param unusedSince its last use by a deployed pact, else its first declaration
   */
  public record Filed(String slug, String ticket, Instant unusedSince) {}

  /**
   * A toggle the run leaves as it is.
   *
   * @param slug the toggle
   * @param reason {@code USED} (a deployed pact evaluates it), {@code RECENT} (unused for less than
   *     qits.toggles.retire-after), {@code TICKET_OPEN} (its removal ticket is still open) or {@code
   *     TICKET_CLOSED} (somebody closed its removal ticket; it is not filed again)
   * @param ticket its removal ticket, when it has one
   */
  public record Kept(String slug, String reason, String ticket) {}

  /**
   * A removal ticket dropped (OBSOLETE): its toggle is used again.
   *
   * @param slug the toggle
   * @param ticket the dropped ticket
   */
  public record Dropped(String slug, String ticket) {}

  /**
   * A toggle whose ticket call failed.
   *
   * @param slug the toggle
   * @param error what failed
   */
  public record Failed(String slug, String error) {}
}
