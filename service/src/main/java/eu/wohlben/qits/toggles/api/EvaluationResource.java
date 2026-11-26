package eu.wohlben.qits.toggles.api;

import eu.wohlben.qits.auth.ChainRule;
import eu.wohlben.qits.http.ChainContext;
import eu.wohlben.qits.toggles.store.RuleWalk;
import eu.wohlben.qits.toggles.store.ToggleStore;
import io.opentelemetry.api.trace.Span;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Clock;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code GET /toggles/api/toggle/{slug}/boolean}: a declared toggle's answer for the call chain this
 * request belongs to — every authenticated caller, a person, a service client or an agent alike.
 *
 * <p><b>Frozen per origin trace.</b> The chain is the integrations library's {@link ChainContext}:
 * the trace id of the chain's first call and whom it acts for, already decided by its
 * anti-impersonation rule ({@link ChainRule}) — a plain service client's {@code
 * X-Qits-Origin-Trace-Id}/{@code X-Qits-On-Behalf-Of} count, anybody else's are ignored and it
 * speaks for its own bearer. The chain's first evaluation of a toggle is stored, and every later one
 * with the same origin answers it, whatever the rules say by then ({@link ToggleStore#evaluate}),
 * until {@link FreezePurge} drops it.
 *
 * <p><b>The rules</b> are walked in order ({@link RuleWalk}) with the subject = the chain's
 * on-behalf-of user, else the caller's own subject.
 *
 * <p>Only {@code boolean} exists: another type segment has no route. An undeclared slug answers
 * 400, naming it.
 */
@Path("/toggle")
@Produces(MediaType.APPLICATION_JSON)
public class EvaluationResource {

  @Inject ToggleStore store;

  @Inject SecurityIdentity identity;

  Clock clock = Clock.systemUTC();

  @GET
  @Path("/{slug}/boolean")
  @Authenticated
  @Operation(
      operationId = "getToggleBoolean",
      summary = "A declared toggle's answer, frozen for the call chain's origin trace")
  @APIResponse(
      responseCode = "200",
      description = "true or false",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(type = SchemaType.BOOLEAN)))
  @APIResponse(
      responseCode = "400",
      description = "No toggle is declared with that slug",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ToggleWire.Problem.class)))
  public Response evaluate(@PathParam("slug") String slug) {
    ChainContext chain = chain();
    String subject =
        chain.onBehalfOf() != null ? chain.onBehalfOf() : identity.getPrincipal().getName();
    Optional<Boolean> answer =
        store.evaluate(
            slug, chain.originTraceId(), rules -> RuleWalk.decide(rules, subject, clock.instant()));
    return answer
        .map(value -> Response.ok(value).build())
        .orElseGet(
            () ->
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ToggleWire.Problem("no toggle is declared as '" + slug + "'", null))
                    .build());
  }

  /**
   * The request's chain: the library's scope, or — should a request ever run outside it — the same
   * rule applied here without the headers: the request's own trace id, its own subject.
   */
  private ChainContext chain() {
    ChainContext current = ChainContext.current();
    if (current != null) {
      return current;
    }
    return ChainRule.decide(
        identity.isAnonymous() ? null : identity.getPrincipal(),
        null,
        null,
        ChainContext.traceIdOf(Span.current()));
  }
}
