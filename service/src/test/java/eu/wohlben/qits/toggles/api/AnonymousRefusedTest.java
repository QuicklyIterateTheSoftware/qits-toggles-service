package eu.wohlben.qits.toggles.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** There is no anonymous door under {@code /toggles/api}: a request without a bearer gets 401. */
@QuarkusTest
class AnonymousRefusedTest {

  @Test
  void anAnonymousRequestAnswers401() {
    given().when().get("/toggles/api/toggles").then().statusCode(401);
  }
}
