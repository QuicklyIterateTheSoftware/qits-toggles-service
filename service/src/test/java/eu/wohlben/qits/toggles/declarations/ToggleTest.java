package eu.wohlben.qits.toggles.declarations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The declaration DSL refuses a bad slug and {@link Declarations#check} a duplicate one; and the
 * declarations this repository carries ({@link Declared}) pass both, so a bad or duplicate slug
 * fails the build here.
 */
class ToggleTest {

  @Test
  void aWellFormedSlugIsDeclared() {
    Toggle toggle = Toggle.bool("new-release-page").description("d").defaultValue(true);
    assertEquals("new-release-page", toggle.slug());
    assertEquals(Toggle.Type.BOOLEAN, toggle.type());
    assertEquals("d", toggle.description());
    assertTrue(toggle.defaultValue());
    assertFalse(Toggle.bool("ab").defaultValue(), "the default default is false");
    Toggle.bool("a" + "b".repeat(62));
  }

  @Test
  void aBadSlugIsRefused() {
    for (String bad :
        new String[] {
          null, "", "a", "New-page", "1page", "-page", "page_one", "page one", "a" + "b".repeat(63)
        }) {
      assertThrows(IllegalArgumentException.class, () -> Toggle.bool(bad), "accepted " + bad);
    }
  }

  @Test
  void aDuplicateSlugIsRefused() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                Declarations.check(
                    List.of(Toggle.bool("twice"), Toggle.bool("once"), Toggle.bool("twice"))));
    assertTrue(refused.getMessage().contains("[twice]"), refused.getMessage());
  }

  @Test
  void theDeclarationsHereAreWellFormedAndUnique() throws Exception {
    List<Toggle> declared = new ArrayList<>();
    for (Field field : Declared.class.getDeclaredFields()) {
      if (field.getType() == Toggle.class && Modifier.isStatic(field.getModifiers())) {
        field.setAccessible(true);
        declared.add((Toggle) field.get(null));
      }
    }
    assertTrue(
        declared.stream().anyMatch(t -> t.slug().equals("toggles-smoke")),
        "the smoke toggle is declared");
    Declarations.check(declared);
  }
}
