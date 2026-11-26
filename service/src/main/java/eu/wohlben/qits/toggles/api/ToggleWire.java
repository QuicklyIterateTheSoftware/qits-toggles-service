package eu.wohlben.qits.toggles.api;

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
   */
  public record ToggleView(
      String slug,
      String type,
      String description,
      boolean declaredDefault,
      Instant firstDeclaredAt,
      long version,
      List<RuleView> rules) {

    static ToggleView of(ToggleStore.StoredToggle stored) {
      return new ToggleView(
          stored.slug(),
          stored.type(),
          stored.description(),
          stored.declaredDefault(),
          stored.firstDeclaredAt(),
          stored.version(),
          stored.rules().stream().map(RuleView::of).toList());
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
}
