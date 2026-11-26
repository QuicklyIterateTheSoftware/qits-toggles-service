package eu.wohlben.qits.toggles.stories.toggles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.toggles.testdb.EmbeddedPg;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The service as it is <b>packaged</b>, with the OIDC tenant <b>on</b> against {@link MockIdp} —
 * the shipped validation block (discovery, JWKS, audience {@code qits-platform}) that no {@code
 * @QuarkusTest} here exercises, since every suite turns the gate off.
 *
 * <p>It is also this repository's <b>userflow catalogue</b>: the story's documentation, with an
 * observed network diagram, is written under {@code service/target/userstories/}, which the
 * java-service release recipe publishes as {@code @userflows/qits-toggles}.
 *
 * <p>Run with {@code ./mvnw verify -DskipITs=false}.
 */
@QuarkusIntegrationTest
@TestProfile(TogglesReadinessIT.PackagedWithMockIdp.class)
public class TogglesReadinessIT {

  static final String CATEGORY = "toggles";

  static final String READY_SLUG = "the-deployer-finds-qits-toggles-ready";

  static final String SERVICE = "qits-toggles";

  /**
   * The packaged process's environment: the {@code QITS_RESOURCE_DB_*} triple on this JVM's
   * embedded postgres, the gate on, the idp moved to the mock, telemetry dark and no shutdown delay.
   * Every key is a RUNTIME key: a packaged artifact takes its configuration as {@code -D} arguments.
   */
  public static class PackagedWithMockIdp implements QuarkusTestProfile {

    static final String URL_PROPERTY = "qits.test.userflow-it.db-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>();
      overrides.put("QITS_RESOURCE_DB_URL", databaseUrl());
      overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put("qits.auth.machine.required", "true");
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      overrides.put("quarkus.otel.sdk.disabled", "true");
      overrides.put("quarkus.shutdown.delay", "0s");
      return overrides;
    }

    /** One url per JVM, parked in a system property for the other classloaders. */
    static synchronized String databaseUrl() {
      String recorded = System.getProperty(URL_PROPERTY);
      if (recorded != null) {
        return recorded;
      }
      String url = EmbeddedPg.url("toggles_userflow_it");
      System.setProperty(URL_PROPERTY, url);
      return url;
    }
  }

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }

  @UserStory(value = "The deployer finds qits-toggles ready", category = CATEGORY)
  @UserStoryDescription(
      """
      qits-deployments starts qits-toggles and gates the rollout on /toggles/q/health/ready. The
      service migrates its store, fetches qits-idp's signing keys so it can validate its callers'
      bearers, and answers ready. A caller without a bearer is refused: there is no anonymous door.
      """)
  @Test
  void theDeployerFindsTheServiceReady(Interactions story) {
    MockIdp idp = MockIdp.attach();
    story.note("qits-toggles starts with the OIDC tenant on, beside a reachable qits-idp");

    given()
        .get("/toggles/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"));
    story.note("the readiness probe answers UP").as("ready");

    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story.note("the signing keys were fetched at startup").as("jwks-fetched");

    given().get("/toggles/api/toggles").then().statusCode(401);
    story.note("an anonymous request is refused with 401").as("anonymous-refused");
  }

  @AfterAll
  static void theStoryReportIsComplete() {
    ReportAssertions.assertComplete(CATEGORY, READY_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, READY_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    ReportAssertions.assertStepId(CATEGORY, READY_SLUG, "jwks-fetched");
  }
}
