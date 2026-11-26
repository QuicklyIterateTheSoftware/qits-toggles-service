package eu.wohlben.qits.toggles.api;

/**
 * The roles this service's doors name.
 *
 * <ul>
 *   <li><b>Evaluating</b> a toggle and <b>reading</b> the configuration: every authenticated caller
 *       ({@code @Authenticated}), a person, a service client or an agent alike.
 *   <li><b>Changing a toggle's rules</b>: {@code qits:admin} for people and {@code qits:desk-admin}
 *       for the delivery desk. Nothing creates or deletes a toggle: a toggle exists by its
 *       declaration in this repository's code.
 * </ul>
 */
public final class Roles {

  public static final String SYSTEM = "qits:system";
  public static final String ADMIN = "qits:admin";
  public static final String ADMIN_AGENT = "qits:admin-agent";
  public static final String DESK_ADMIN = "qits:desk-admin";
  public static final String AGENT = "qits:agent";

  private Roles() {}
}
