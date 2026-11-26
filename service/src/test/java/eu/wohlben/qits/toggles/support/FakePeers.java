package eu.wohlben.qits.toggles.support;

import eu.wohlben.qits.toggles.retirement.PactInteractions;
import eu.wohlben.qits.toggles.retirement.Tickets;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>The peers every {@code @QuarkusTest} here talks to instead of qits-maintenance and
 * qits-projects</b>: the deployed pact interactions a test states, and a ticket book that files
 * {@code qits-100}, {@code qits-101}, … and records every call. No suite dials a peer; the HTTP
 * clients themselves are exercised against pact mock servers in {@code contracts/consumer/}.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FakePeers implements PactInteractions, Tickets {

  /** One call the ticket book saw: {@code file}, {@code read}, {@code comment} or {@code drop}. */
  public record Call(String kind, String ref, String detail) {}

  private List<Interaction> interactions = List.of();
  private String readFailure;
  private final Map<String, String> statuses = new LinkedHashMap<>();
  private final List<Call> calls = new ArrayList<>();
  private final List<String> askedProviders = new ArrayList<>();
  private int next = 100;

  /** Back to no interactions, no tickets, no calls; the next ticket is {@code qits-100}. */
  public synchronized void reset() {
    interactions = List.of();
    readFailure = null;
    statuses.clear();
    calls.clear();
    askedProviders.clear();
    next = 100;
  }

  /** The deployed consumers' pacts evaluate these concrete paths (each a GET). */
  public synchronized void evaluated(String... slugs) {
    List<Interaction> list = new ArrayList<>();
    for (String slug : slugs) {
      list.add(
          new Interaction(
              "GET",
              "/toggles/api/toggle/" + slug + "/boolean",
              "getToggleBoolean",
              List.of(Map.of("slug", slug))));
    }
    interactions = List.copyOf(list);
  }

  /** The deployed pacts hold exactly these interactions. */
  public synchronized void interactions(List<Interaction> list) {
    interactions = List.copyOf(list);
  }

  /** The next interactions read fails with {@code failure}. */
  public synchronized void failReads(String failure) {
    readFailure = failure;
  }

  /** A ticket {@code ref} that exists with {@code status}. */
  public synchronized void ticket(String ref, String status) {
    statuses.put(ref, status);
  }

  public synchronized Optional<String> status(String ref) {
    return Optional.ofNullable(statuses.get(ref));
  }

  public synchronized List<Call> calls() {
    return List.copyOf(calls);
  }

  public synchronized List<Call> calls(String kind) {
    return calls.stream().filter(c -> c.kind().equals(kind)).toList();
  }

  public synchronized List<String> askedProviders() {
    return List.copyOf(askedProviders);
  }

  @Override
  public synchronized List<Interaction> read(String provider) {
    askedProviders.add(provider);
    if (readFailure != null) {
      throw new ReadFailed(readFailure);
    }
    return interactions;
  }

  @Override
  public synchronized Filed file(String project, String title, String impetus, String description) {
    String ref = "qits-" + next++;
    statuses.put(ref, "REPORTED");
    calls.add(new Call("file", ref, project + " | " + title + " | " + impetus + " | " + description));
    return new Filed(ref);
  }

  @Override
  public synchronized Optional<State> state(String ref) {
    calls.add(new Call("read", ref, null));
    return Optional.ofNullable(statuses.get(ref)).map(State::new);
  }

  @Override
  public synchronized void comment(String ref, String text) {
    calls.add(new Call("comment", ref, text));
  }

  @Override
  public synchronized void drop(String ref) {
    calls.add(new Call("drop", ref, null));
    statuses.put(ref, "DROPPED");
  }
}
