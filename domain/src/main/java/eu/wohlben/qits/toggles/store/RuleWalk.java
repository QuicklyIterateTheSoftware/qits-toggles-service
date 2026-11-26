package eu.wohlben.qits.toggles.store;

import java.time.Instant;
import java.util.List;

/**
 * <b>The evaluation of a toggle's ordered rules</b>: the first rule that matches answers its own
 * value.
 *
 * <ul>
 *   <li>{@link Rule.Kind#USER} matches when the subject is in its set — the subject being the chain's
 *       on-behalf-of user when there is one, else the caller's own bearer {@code sub}; the caller of
 *       this class has already decided which of the two counts;
 *   <li>{@link Rule.Kind#TIME} matches when {@code now} is at or after its instant;
 *   <li>{@link Rule.Kind#STATIC} always matches. Exactly one per toggle, always last, so a walk
 *       always ends there.
 * </ul>
 */
public final class RuleWalk {

  private RuleWalk() {}

  /**
   * What {@code rules} answer for {@code subject} at {@code now}.
   *
   * @param subject whom the call acts for, or null when nobody is known: no USER rule matches then
   * @throws IllegalStateException when no rule matches, which a list holding its STATIC rule cannot
   */
  public static boolean decide(List<Rule> rules, String subject, Instant now) {
    for (Rule rule : rules) {
      boolean matches =
          switch (rule.kind()) {
            case USER -> subject != null && rule.userSubjects().contains(subject);
            case TIME -> !now.isBefore(rule.afterInstant());
            case STATIC -> true;
          };
      if (matches) {
        return rule.value();
      }
    }
    throw new IllegalStateException("no rule matched: the list holds no STATIC rule");
  }
}
