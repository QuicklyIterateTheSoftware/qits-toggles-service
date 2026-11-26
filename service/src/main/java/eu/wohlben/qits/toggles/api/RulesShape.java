package eu.wohlben.qits.toggles.api;

import eu.wohlben.qits.toggles.store.Rule;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The shape a rule list must have</b> before it is stored: exactly one STATIC rule, last; a
 * USER rule with at least one subject and no instant; a TIME rule with an instant and no subjects; a
 * STATIC rule with neither; every rule with its value.
 */
final class RulesShape {

  /** A list longer than this is refused: a toggle's rules are read on every evaluation. */
  static final int MAX_RULES = 100;

  /** A USER rule naming more subjects than this is refused. */
  static final int MAX_SUBJECTS = 1000;

  /** The longest subject (a bearer {@code sub}); the same bound the chain context holds. */
  static final int MAX_SUBJECT_LENGTH = 255;

  /** The list, checked, or the problem with it. */
  record Checked(List<Rule> rules, ToggleWire.Problem problem) {}

  private RulesShape() {}

  static Checked check(List<ToggleWire.RuleView> wire) {
    if (wire == null || wire.isEmpty()) {
      return refused("the rule list is empty: it needs its STATIC rule, last", null);
    }
    if (wire.size() > MAX_RULES) {
      return refused("a toggle has at most " + MAX_RULES + " rules", null);
    }
    List<Rule> rules = new ArrayList<>();
    for (int position = 0; position < wire.size(); position++) {
      ToggleWire.RuleView rule = wire.get(position);
      if (rule == null || rule.kind() == null) {
        return refused("the rule has no kind: USER, TIME or STATIC", position);
      }
      Rule.Kind kind;
      try {
        kind = Rule.Kind.valueOf(rule.kind());
      } catch (IllegalArgumentException unknown) {
        return refused(
            "the rule's kind '" + rule.kind() + "' is not USER, TIME or STATIC", position);
      }
      if (rule.value() == null) {
        return refused("the rule has no value: true or false", position);
      }
      boolean last = position == wire.size() - 1;
      List<String> subjects = rule.userSubjects();
      switch (kind) {
        case STATIC -> {
          if (!last) {
            return refused("the STATIC rule must be the last one", position);
          }
          if ((subjects != null && !subjects.isEmpty()) || rule.afterInstant() != null) {
            return refused("a STATIC rule has no user subjects and no instant", position);
          }
          rules.add(Rule.always(rule.value()));
        }
        case USER -> {
          if (subjects == null || subjects.isEmpty()) {
            return refused("a USER rule needs at least one user subject", position);
          }
          if (subjects.size() > MAX_SUBJECTS) {
            return refused("a USER rule names at most " + MAX_SUBJECTS + " subjects", position);
          }
          for (String subject : subjects) {
            if (!wellFormedSubject(subject)) {
              return refused(
                  "a user subject is blank, longer than "
                      + MAX_SUBJECT_LENGTH
                      + " characters or carries control characters",
                  position);
            }
          }
          if (rule.afterInstant() != null) {
            return refused("a USER rule has no instant", position);
          }
          rules.add(new Rule(Rule.Kind.USER, rule.value(), subjects, null));
        }
        case TIME -> {
          if (rule.afterInstant() == null) {
            return refused("a TIME rule needs its instant", position);
          }
          if (subjects != null && !subjects.isEmpty()) {
            return refused("a TIME rule has no user subjects", position);
          }
          rules.add(new Rule(Rule.Kind.TIME, rule.value(), null, rule.afterInstant()));
        }
      }
      if (last && kind != Rule.Kind.STATIC) {
        return refused("the last rule must be the STATIC one", position);
      }
    }
    return new Checked(List.copyOf(rules), null);
  }

  private static boolean wellFormedSubject(String subject) {
    if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
      return false;
    }
    return subject.chars().noneMatch(Character::isISOControl);
  }

  private static Checked refused(String error, Integer position) {
    return new Checked(null, new ToggleWire.Problem(error, position));
  }
}
