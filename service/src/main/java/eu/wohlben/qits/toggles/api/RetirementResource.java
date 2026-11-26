package eu.wohlben.qits.toggles.api;

import eu.wohlben.qits.toggles.retirement.PactInteractions;
import eu.wohlben.qits.toggles.retirement.RetirementRun;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code POST /toggles/api/retirement/runs?dryRun=}: one retirement run ({@link RetirementRun}),
 * {@code qits:system} only — qits-orchestrator's {@code gc} step {@code toggles-retirement} calls it,
 * with {@code dryRun=true} on a dry gc run.
 */
@Path("/retirement/runs")
@Produces(MediaType.APPLICATION_JSON)
public class RetirementResource {

  @Inject RetirementRun run;

  @POST
  @RolesAllowed(Roles.SYSTEM)
  @Operation(
      operationId = "runToggleRetirement",
      summary =
          "File a removal ticket for every toggle no deployed consumer's pact has used for"
              + " qits.toggles.retire-after (qits:system)")
  @APIResponse(
      responseCode = "200",
      description = "What the run filed, kept and dropped; on a dry run, what it would",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ToggleWire.RetirementReport.class)))
  @APIResponse(responseCode = "403", description = "Not qits:system")
  @APIResponse(
      responseCode = "502",
      description = "qits-maintenance did not answer the deployed pacts: nothing was filed",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ToggleWire.Problem.class)))
  public Response run(@QueryParam("dryRun") boolean dryRun) {
    try {
      return Response.ok(ToggleWire.RetirementReport.of(run.run(dryRun))).build();
    } catch (PactInteractions.ReadFailed e) {
      return Response.status(Response.Status.BAD_GATEWAY)
          .entity(new ToggleWire.Problem(e.getMessage(), null))
          .build();
    }
  }
}
