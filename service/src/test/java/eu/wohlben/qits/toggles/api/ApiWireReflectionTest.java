package eu.wohlben.qits.toggles.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiWireReflection} is complete: every nested type of {@link ToggleWire}. On a JVM
 * everything binds regardless; the failure this guards is a 500 in the native binary.
 */
class ApiWireReflectionTest {

  @Test
  void everyWireTypeIsRegistered() {
    RegisterForReflection registration =
        ApiWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class");
    Set<Class<?>> registered = Set.of(registration.targets());
    List<Class<?>> nested = List.of(ToggleWire.class.getDeclaredClasses());
    assertTrue(nested.size() >= 4, "the sweep found only " + nested.size() + " wire types");
    for (Class<?> type : nested) {
      assertTrue(
          registered.contains(type),
          type.getSimpleName() + " is on the wire and not in ApiWireReflection");
    }
  }
}
