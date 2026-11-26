package eu.wohlben.qits.toggles.retirement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * {@link Tickets} over qits-projects' {@code /work} doors, the way qits-maintenance's sbom-check
 * {@code TicketClient} calls them: {@code POST /projects/api/work} with the archetype and the ticket
 * create schema (201, the entity with {@code id} and {@code qualifiedId}); {@code GET
 * /projects/api/work/{ref}} ({@code status}); {@code POST …/{ref}/comments} {@code {"body": …}};
 * {@code POST …/{ref}/status} {@code {"target": "DROPPED", "outcome": "OBSOLETE"}}. qits-projects
 * resolves a qualified id or a UUID in {@code {ref}}.
 */
@ApplicationScoped
public class ProjectsTickets implements Tickets {

  static final String WORK = "/api/work";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject Peers peers;

  private PeerHttp over;

  /** The same calls over {@code http} — a pact mock server in the consumer test. */
  public static ProjectsTickets over(PeerHttp http) {
    ProjectsTickets tickets = new ProjectsTickets();
    tickets.over = http;
    return tickets;
  }

  private PeerHttp http() {
    return over != null ? over : peers.projects();
  }

  @Override
  public Filed file(String project, String title, String impetus, String description) {
    ObjectNode body = JSON.createObjectNode();
    body.put("archetype", "TICKET");
    body.put("project", project);
    body.put("ticketType", "MAINTENANCE");
    body.put("title", title);
    body.put("impetus", impetus);
    body.put("description", description);
    PeerHttp.Answer answer = http().post(WORK, body.toString());
    if (!answer.ok()) {
      throw failed("file a ticket in project " + project, "POST", WORK, answer);
    }
    JsonNode entity = json(answer);
    String ref = text(entity, "qualifiedId");
    if (ref == null) {
      ref = text(entity, "id");
    }
    if (ref == null) {
      throw new CallFailed("qits-projects filed a ticket and answered no id: " + answer.brief());
    }
    return new Filed(ref);
  }

  @Override
  public Optional<State> state(String ref) {
    PeerHttp.Answer answer = http().get(WORK + "/" + ref);
    if (answer.notFound()) {
      return Optional.empty();
    }
    if (!answer.ok()) {
      throw failed("read ticket " + ref, "GET", WORK + "/" + ref, answer);
    }
    return Optional.of(new State(text(json(answer), "status")));
  }

  @Override
  public void comment(String ref, String text) {
    ObjectNode body = JSON.createObjectNode();
    body.put("body", text);
    String path = WORK + "/" + ref + "/comments";
    PeerHttp.Answer answer = http().post(path, body.toString());
    if (!answer.ok()) {
      throw failed("comment on ticket " + ref, "POST", path, answer);
    }
  }

  @Override
  public void drop(String ref) {
    ObjectNode body = JSON.createObjectNode();
    body.put("target", "DROPPED");
    body.put("outcome", "OBSOLETE");
    String path = WORK + "/" + ref + "/status";
    PeerHttp.Answer answer = http().post(path, body.toString());
    if (!answer.ok()) {
      throw failed("drop ticket " + ref, "POST", path, answer);
    }
  }

  private CallFailed failed(String what, String method, String path, PeerHttp.Answer answer) {
    return new CallFailed(
        "could not " + what + ": " + method + " " + http().url(path) + " answered " + answer.brief());
  }

  private static JsonNode json(PeerHttp.Answer answer) {
    try {
      return JSON.readTree(answer.body());
    } catch (Exception e) {
      throw new CallFailed("qits-projects answered no JSON: " + answer.brief());
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
  }
}
