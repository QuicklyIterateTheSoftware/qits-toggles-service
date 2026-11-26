package eu.wohlben.qits.toggles.declarations;

import eu.wohlben.qits.toggles.store.Declaration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** <b>The declared toggles</b>, as CDI collects them: every {@link Toggle} bean, checked. */
@ApplicationScoped
public class Declarations {

  @Inject @Any Instance<Toggle> declared;

  /** Every declared toggle, by slug, checked by {@link #check}. */
  public List<Toggle> all() {
    List<Toggle> all = new ArrayList<>();
    declared.forEach(all::add);
    check(all);
    all.sort(Comparator.comparing(Toggle::slug));
    return all;
  }

  /** The declarations as the store's reconcile takes them. */
  public List<Declaration> forReconcile() {
    return all().stream()
        .map(t -> new Declaration(t.slug(), t.description(), t.defaultValue()))
        .toList();
  }

  /**
   * Refuse a set of declarations that names one slug twice, or a slug that is not {@value
   * Toggle#SLUG_PATTERN} (which {@link Toggle#bool} already refuses).
   *
   * @throws IllegalStateException naming every duplicate slug
   */
  public static void check(List<Toggle> toggles) {
    Set<String> seen = new HashSet<>();
    Set<String> duplicates = new TreeSet<>();
    for (Toggle toggle : toggles) {
      if (!toggle.slug().matches(Toggle.SLUG_PATTERN)) {
        throw new IllegalStateException(
            "toggle slug '" + toggle.slug() + "' does not match " + Toggle.SLUG_PATTERN);
      }
      if (!seen.add(toggle.slug())) {
        duplicates.add(toggle.slug());
      }
    }
    if (!duplicates.isEmpty()) {
      throw new IllegalStateException("toggles declared more than once: " + duplicates);
    }
  }
}
