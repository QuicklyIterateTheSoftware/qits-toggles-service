package eu.wohlben.qits.toggles.retirement;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One peer's base URL, the HTTP client that reaches it and the bearer each call presents: the
 * plumbing under {@link MaintenanceInteractions} and {@link ProjectsTickets}. Built by {@link
 * Peers} in the service, and over a pact mock server's URL in the consumer tests.
 *
 * <p>A call never throws for the peer's answer: a transport failure is an {@link Answer} with
 * status {@code -1} and the failure as its body, so a caller decides once, on {@link Answer#ok}.
 */
public final class PeerHttp {

  static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final HttpClient client;
  private final String base;
  private final Supplier<Optional<String>> bearer;

  /**
   * @param base the peer's root, with its application prefix: {@code
   *     https://maintenance.qits.<domain>/maintenance}
   * @param bearer the access token to present, empty for none
   */
  public PeerHttp(HttpClient client, String base, Supplier<Optional<String>> bearer) {
    this.client = client;
    this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.bearer = bearer;
  }

  /** What a peer answered: its status ({@code -1} when nothing came back) and its body. */
  public record Answer(int status, String body) {

    public boolean ok() {
      return status >= 200 && status < 300;
    }

    public boolean notFound() {
      return status == 404;
    }

    /** The status and the start of the body, for a sentence. */
    public String brief() {
      String flat = body == null ? "" : body.replaceAll("\\s+", " ").trim();
      if (flat.length() > 200) {
        flat = flat.substring(0, 200) + "…";
      }
      return (status < 0 ? "no answer" : "HTTP " + status) + (flat.isEmpty() ? "" : ": " + flat);
    }
  }

  /** {@code GET <base><path>}; {@code path} starts with {@code /}. */
  public Answer get(String path) {
    return send(request(path).GET());
  }

  /** {@code POST <base><path>} with a JSON body. */
  public Answer post(String path, String json) {
    return send(
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
  }

  /** The URL a path resolves to, for a sentence. */
  public String url(String path) {
    return base + path;
  }

  private HttpRequest.Builder request(String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(TIMEOUT)
            .header("Accept", "application/json");
    bearer.get().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
    return builder;
  }

  private Answer send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Answer(response.statusCode(), response.body());
    } catch (IOException e) {
      return new Answer(-1, e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Answer(-1, "interrupted");
    }
  }
}
