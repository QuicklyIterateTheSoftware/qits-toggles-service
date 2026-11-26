package eu.wohlben.qits.toggles.declarations;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * <b>One toggle's declaration</b> — the only way a toggle comes to exist. A declaration is a CDI
 * producer field in {@link Declared}:
 *
 * <pre>{@code
 * @Produces
 * static final Toggle NEW_RELEASE_PAGE =
 *     Toggle.bool("new-release-page").description("The new release page").defaultValue(false);
 * }</pre>
 *
 * <p>At startup every declared toggle is reconciled into the store ({@link DeclarationReconcile}):
 * a new slug gets its row and a single STATIC rule holding {@link #defaultValue()}; an existing slug
 * keeps its configured rules; a slug no longer declared is deleted. Deleting a declaration is
 * therefore the whole of retiring a toggle.
 *
 * <p>A slug is {@value #SLUG_PATTERN}, checked here, so a bad slug fails the class that declares it
 * and with it every test; {@link Declarations#check} refuses a duplicate.
 */
public final class Toggle {

  /** What a slug looks like: a lowercase letter, then 1 to 62 lowercase letters, digits or dashes. */
  public static final String SLUG_PATTERN = "^[a-z][a-z0-9-]{1,62}$";

  private static final Pattern SLUG = Pattern.compile(SLUG_PATTERN);

  /** The types a toggle can have; only boolean exists. */
  public enum Type {
    BOOLEAN
  }

  private final String slug;
  private final Type type;
  private final String description;
  private final boolean defaultValue;

  private Toggle(String slug, Type type, String description, boolean defaultValue) {
    this.slug = slug;
    this.type = type;
    this.description = description;
    this.defaultValue = defaultValue;
  }

  /** A boolean toggle named {@code slug}, with no description yet and default {@code false}. */
  public static Toggle bool(String slug) {
    if (slug == null || !SLUG.matcher(slug).matches()) {
      throw new IllegalArgumentException(
          "toggle slug '" + slug + "' does not match " + SLUG_PATTERN);
    }
    return new Toggle(slug, Type.BOOLEAN, "", false);
  }

  /** This declaration with its description: what the toggle is for, shown where it is configured. */
  public Toggle description(String description) {
    return new Toggle(slug, type, Objects.requireNonNull(description, "description"), defaultValue);
  }

  /** This declaration with its default: what a new toggle's STATIC rule answers. */
  public Toggle defaultValue(boolean defaultValue) {
    return new Toggle(slug, type, description, defaultValue);
  }

  public String slug() {
    return slug;
  }

  public Type type() {
    return type;
  }

  public String description() {
    return description;
  }

  public boolean defaultValue() {
    return defaultValue;
  }

  @Override
  public String toString() {
    return "Toggle[" + slug + ", " + type + ", default " + defaultValue + "]";
  }
}
