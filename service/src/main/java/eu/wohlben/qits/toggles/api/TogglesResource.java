package eu.wohlben.qits.toggles.api;

import eu.wohlben.qits.toggles.store.ToggleStore;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * {@code /toggles/api/toggles}: the configuration doors qits-landing's Toggles section uses.
 *
 * <ul>
 *   <li>{@code GET /toggles} and {@code GET /toggles/{slug}} — every authenticated caller: each
 *       toggle's slug, description, declared default, ordered rules and version.
 *   <li>{@code PUT /toggles/{slug}/rules} — {@code qits:admin} and {@code qits:desk-admin} only:
 *       the whole ordered list and the version it was read at. 400 for a list of the wrong shape
 *       ({@link RulesShape}) or an unknown slug, 409 for a stale version; nothing is changed by a
 *       refused replace.
 * </ul>
 *
 * <p><b>There is no create and no delete door</b>, on purpose: a toggle exists by its declaration
 * in this repository's code.
 */
@Path("/toggles")
@Produces(MediaType.APPLICATION_JSON)
public class TogglesResource {

  private static final Logger LOG = Logger.getLogger(TogglesResource.class);

  @Inject ToggleStore store;

  @Inject SecurityIdentity identity;

  @GET
  @Authenticated
  @Operation(operationId = "getToggles", summary = "Every declared toggle with its rules")
  @APIResponse(
      responseCode = "200",
      description = "The toggles, by slug",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema =
                  @Schema(implementation = ToggleWire.ToggleView.class, type = SchemaType.ARRAY)))
  public List<ToggleWire.ToggleView> list() {
    return store.list().stream().map(ToggleWire.ToggleView::of).toList();
  }

  @GET
  @Path("/{slug}")
  @Authenticated
  @Operation(operationId = "getToggle", summary = "One declared toggle with its rules")
  @APIResponse(
      responseCode = "200",
      description = "The toggle",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ToggleWire.ToggleView.class)))
  @APIResponse(
      responseCode = "404",
      description = "No toggle is declared with that slug",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ToggleWire.Problem.class)))
  public Response get(@PathParam("slug") String slug) {
    return store
        .find(slug)
        .map(found -> Response.ok(ToggleWire.ToggleView.of(found)).build())
        .orElseGet(
            () -> problem(Response.Status.NOT_FOUND, "no toggle is declared as '" + slug + "'"));
  }

  @PUT
  @Path("/{slug}/rules")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.ADMIN, Roles.DESK_ADMIN})
  @Operation(
      operationId = "putToggleRules",
      summary = "Replace a toggle's ordered rules (qits:admin or the delivery desk)")
  @APIResponse(
      responseCode = "200",
      description = "Replaced; the toggle as it is now, its version moved by one",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ToggleWire.ToggleView.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "The list is malformed (not exactly one STATIC rule, last; a USER rule without"
              + " subjects; a TIME rule without its instant), the version is missing, or no toggle"
              + " is declared with that slug",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ToggleWire.Problem.class)))
  @APIResponse(responseCode = "403", description = "Neither qits:admin nor qits:desk-admin")
  @APIResponse(
      responseCode = "409",
      description = "The version is not the one held: someone else changed the rules first",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ToggleWire.Problem.class)))
  public Response replaceRules(@PathParam("slug") String slug, ToggleWire.RulesReplace body) {
    if (body == null || body.version() == null) {
      return problem(Response.Status.BAD_REQUEST, "the body names no version");
    }
    RulesShape.Checked checked = RulesShape.check(body.rules());
    if (checked.problem() != null) {
      return Response.status(Response.Status.BAD_REQUEST).entity(checked.problem()).build();
    }
    ToggleStore.Replaced done = store.replaceRules(slug, body.version(), checked.rules());
    return switch (done) {
      case UNKNOWN -> problem(Response.Status.BAD_REQUEST, "no toggle is declared as '" + slug + "'");
      case STALE ->
          problem(
              Response.Status.CONFLICT,
              "the rules of '"
                  + slug
                  + "' changed since version "
                  + body.version()
                  + " was read; reload and try again");
      case REPLACED -> {
        LOG.infof(
            "rules of %s replaced at version %d by %s: %d rules",
            slug, body.version(), identity.getPrincipal().getName(), checked.rules().size());
        yield Response.ok(ToggleWire.ToggleView.of(store.find(slug).orElseThrow())).build();
      }
    };
  }

  private static Response problem(Response.Status status, String error) {
    return Response.status(status).entity(new ToggleWire.Problem(error, null)).build();
  }
}
