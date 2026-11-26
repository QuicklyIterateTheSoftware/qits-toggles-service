package eu.wohlben.qits.toggles.store;

import java.time.Instant;
import java.util.List;

/**
 * One rule of a toggle's ordered list: the first rule that matches answers its own {@link #value}.
 *
 * @param kind what the rule matches on
 * @param value what the rule answers when it matches
 * @param userSubjects the bearer subjects a {@link Kind#USER} rule matches; null for other kinds
 * @param afterInstant the instant from which a {@link Kind#TIME} rule matches; null for other kinds
 */
public record Rule(Kind kind, boolean value, List<String> userSubjects, Instant afterInstant) {

  /** What a rule matches on. */
  public enum Kind {
    /** The subject (the on-behalf-of user, else the bearer's own) is in the set. */
    USER,
    /** Now is at or after the instant. */
    TIME,
    /** Always. Exactly one per toggle, always last: the toggle's default. */
    STATIC
  }

  public Rule {
    userSubjects = userSubjects == null ? null : List.copyOf(userSubjects);
  }

  /** The STATIC rule answering {@code value}. */
  public static Rule always(boolean value) {
    return new Rule(Kind.STATIC, value, null, null);
  }
}
