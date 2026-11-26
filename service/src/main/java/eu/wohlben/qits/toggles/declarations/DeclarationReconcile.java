package eu.wohlben.qits.toggles.declarations;

import eu.wohlben.qits.toggles.store.ToggleStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * <b>At startup, the store is brought in line with the declarations</b> ({@link
 * ToggleStore#reconcile}): new slugs created with one STATIC rule, existing ones keeping their
 * rules, undeclared ones deleted. It runs after Flyway's migration; a failure fails the boot, so a
 * release with a duplicate slug never answers ready.
 */
@ApplicationScoped
public class DeclarationReconcile {

  private static final Logger LOG = Logger.getLogger(DeclarationReconcile.class);

  @Inject Declarations declarations;

  @Inject ToggleStore store;

  void onStart(@Observes StartupEvent start) {
    ToggleStore.Reconciled done = store.reconcile(declarations.forReconcile());
    LOG.infof(
        "toggles reconciled: created %s, kept %s, deleted %s",
        done.created(), done.kept(), done.deleted());
  }
}
