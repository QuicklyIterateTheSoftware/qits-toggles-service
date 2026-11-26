package eu.wohlben.qits.toggles.retirement;

import java.util.Optional;

/**
 * The retirement run's four calls into qits-projects' {@code /work} API — file a ticket, read one,
 * comment on one, drop one — {@link ProjectsTickets} in the service. Every failure throws {@link
 * CallFailed}: a write that may or may not have happened must not be mistaken for one that did.
 */
public interface Tickets {

  /** A filed ticket: the reference to hold, its qualified id when qits-projects answered one. */
  record Filed(String ref) {}

  /** A ticket's lifecycle status. */
  record State(String status) {

    /** DONE or DROPPED: somebody has finished with it. */
    public boolean closed() {
      return "DONE".equals(status) || "DROPPED".equals(status);
    }
  }

  /** A ticket call that did not do what was asked. */
  class CallFailed extends RuntimeException {
    public CallFailed(String message) {
      super(message);
    }
  }

  /** Files a MAINTENANCE ticket in {@code project} (its slug or id). */
  Filed file(String project, String title, String impetus, String description);

  /** One ticket's state; empty when qits-projects has no such ticket any more. */
  Optional<State> state(String ref);

  /** Adds a comment to a ticket's thread. */
  void comment(String ref, String text);

  /** Moves a ticket to DROPPED, outcome OBSOLETE: its subject went away by itself. */
  void drop(String ref);
}
