package eu.wohlben.qits.toggles.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Every type Jackson binds on a door, registered for the native image: on a JVM everything binds
 * regardless, and the failure this prevents is a 500 in the native binary. {@code
 * ApiWireReflectionTest} keeps the list complete.
 */
@RegisterForReflection(
    targets = {
      ToggleWire.ToggleView.class,
      ToggleWire.RuleView.class,
      ToggleWire.RulesReplace.class,
      ToggleWire.Problem.class,
      ToggleWire.RetirementReport.class,
      ToggleWire.Filed.class,
      ToggleWire.Kept.class,
      ToggleWire.Dropped.class,
      ToggleWire.Failed.class
    })
public final class ApiWireReflection {

  private ApiWireReflection() {}
}
