package eu.wohlben.qits.toggles.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply — {@code jdbc.url}, {@code username}, {@code password} — for the
 * one datasource this deployable boots: the toggles store.
 *
 * <p>A config source rather than three lines in the test properties because the port is chosen at
 * run time. The ordinal sits above application.properties (250), and it is registered through
 * {@code META-INF/services}. The database name is this repository's own, so two surefire JVMs on
 * one host never mean one database.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This service's own store: toggles, rules and freezes. */
  private static final String TOGGLES_DATABASE = "toggles_svc";

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.toggles.jdbc.url", EmbeddedPg.url(TOGGLES_DATABASE),
          "quarkus.datasource.toggles.username", EmbeddedPg.USER,
          "quarkus.datasource.toggles.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
