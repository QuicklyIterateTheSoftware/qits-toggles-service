package eu.wohlben.qits.toggles.contracts;

import eu.wohlben.qits.toggles.declarations.Declared;
import eu.wohlben.qits.toggles.declarations.Toggle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;

/**
 * The slugs {@link Declared} declares, read without a container: the recording table is static, and
 * it holds one evaluation golden master per declared toggle, so a declaration added to {@link
 * Declared} gets its golden master — and, through {@code ProviderStateCoverageTest}, needs its
 * {@code @State} callback — in the same commit.
 */
final class DeclaredSlugs {

  private DeclaredSlugs() {}

  static List<String> all() {
    return Stream.of(Declared.class.getDeclaredFields())
        .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == Toggle.class)
        .map(DeclaredSlugs::slug)
        .sorted()
        .toList();
  }

  private static String slug(Field field) {
    try {
      field.setAccessible(true);
      return ((Toggle) field.get(null)).slug();
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("cannot read " + field, e);
    }
  }
}
