package eu.wohlben.qits.toggles.api;

import eu.wohlben.qits.toggles.store.ToggleStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Every minute, drop the freezes older than {@code qits.toggles.freeze-ttl} (1h): a chain's answer
 * holds for that long, then the next evaluation of the same origin decides afresh. Every replica
 * runs it; the delete is idempotent, so they need no coordination.
 */
@ApplicationScoped
public class FreezePurge {

  private static final Logger LOG = Logger.getLogger(FreezePurge.class);

  @Inject ToggleStore store;

  @ConfigProperty(name = "qits.toggles.freeze-ttl")
  Duration ttl;

  Clock clock = Clock.systemUTC();

  @Scheduled(
      identity = "toggles-freeze-purge",
      every = "1m",
      delayed = "1m",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduled() {
    purge();
  }

  /** Drop the expired freezes now; answers how many went. */
  public int purge() {
    int purged = store.purgeFreezes(clock.instant().minus(ttl));
    if (purged > 0) {
      LOG.debugf("purged %d freezes older than %s", purged, ttl);
    }
    return purged;
  }
}
