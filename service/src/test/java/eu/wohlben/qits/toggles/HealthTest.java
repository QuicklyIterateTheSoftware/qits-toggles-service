package eu.wohlben.qits.toggles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The service boots, migrates its store, and answers the readiness probe the deployer gates on
 * ({@code health_path: /toggles/q/health/ready}).
 */
@QuarkusTest
class HealthTest {

  @Test
  void readinessAnswersUp() {
    given()
        .when()
        .get("/toggles/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"))
        .body("checks.name", hasItem("Database connections health check"));
  }
}
