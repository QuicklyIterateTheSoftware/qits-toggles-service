package eu.wohlben.qits.toggles.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The first rule that matches answers its own value, in the configured order. */
class RuleWalkTest {

  static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

  static Rule user(boolean value, String... subjects) {
    return new Rule(Rule.Kind.USER, value, List.of(subjects), null);
  }

  static Rule after(boolean value, Instant instant) {
    return new Rule(Rule.Kind.TIME, value, null, instant);
  }

  @Test
  void theStaticRuleAnswersWhenNothingElseMatches() {
    assertTrue(RuleWalk.decide(List.of(Rule.always(true)), "anyone", NOW));
    assertFalse(RuleWalk.decide(List.of(Rule.always(false)), null, NOW));
  }

  @Test
  void aUserRuleMatchesOnlyItsSubjects() {
    List<Rule> rules = List.of(user(true, "alice", "tok-agent-x"), Rule.always(false));
    assertTrue(RuleWalk.decide(rules, "alice", NOW));
    assertTrue(RuleWalk.decide(rules, "tok-agent-x", NOW));
    assertFalse(RuleWalk.decide(rules, "bob", NOW));
    assertFalse(RuleWalk.decide(rules, null, NOW), "nobody known matches no user rule");
  }

  @Test
  void aTimeRuleMatchesFromItsInstantOn() {
    assertTrue(RuleWalk.decide(List.of(after(true, NOW), Rule.always(false)), null, NOW));
    assertTrue(
        RuleWalk.decide(List.of(after(true, NOW.minusSeconds(1)), Rule.always(false)), null, NOW));
    assertFalse(
        RuleWalk.decide(List.of(after(true, NOW.plusSeconds(1)), Rule.always(false)), null, NOW));
  }

  @Test
  void theFirstMatchingRuleDecidesInTheConfiguredOrder() {
    List<Rule> userFirst =
        List.of(user(false, "alice"), after(true, NOW.minusSeconds(60)), Rule.always(false));
    assertFalse(RuleWalk.decide(userFirst, "alice", NOW), "alice's user rule comes first");
    assertTrue(RuleWalk.decide(userFirst, "bob", NOW), "bob falls through to the time rule");

    List<Rule> timeFirst =
        List.of(after(true, NOW.minusSeconds(60)), user(false, "alice"), Rule.always(false));
    assertTrue(RuleWalk.decide(timeFirst, "alice", NOW), "the time rule now comes first");
  }

  @Test
  void aListWithoutItsStaticRuleIsABug() {
    assertThrows(IllegalStateException.class, () -> RuleWalk.decide(List.of(), "alice", NOW));
  }
}
