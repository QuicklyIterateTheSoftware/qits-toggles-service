package eu.wohlben.qits.toggles.declarations;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

/**
 * <b>Every toggle there is</b>, one producer field each, collected by CDI ({@link Declarations}).
 * Adding a field here and releasing is how a toggle comes to exist; deleting it and releasing is
 * how it is retired (the startup reconcile deletes it with its rules and freezes). A consumer that
 * evaluates a toggle declares a consumer pact for {@code GET /toggles/api/toggle/<slug>/boolean},
 * which is how the retirement run knows the toggle is still used.
 */
@Dependent
public class Declared {

  /**
   * The smoke toggle: proves evaluation live, and, since no consumer's pact uses it, the
   * retirement run's removal ticket proves the retirement live too.
   */
  @Produces
  static final Toggle TOGGLES_SMOKE =
      Toggle.bool("toggles-smoke")
          .description(
              "Smoke toggle of qits-toggles itself: proves evaluation and, since no pact uses it,"
                  + " retirement live.")
          .defaultValue(false);
}
