package eu.wohlben.qits.toggles.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-toggles' provider golden masters</b> — {@code golden-masters/} at the repository
 * root, the source of {@code eu.wohlben.qits:qits-toggles-golden-masters} (maven) and {@code
 * @qits/toggles-golden-masters} (npm).
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the operation as the state's {@code authorization}, freezes ids and
 * instants ({@link Freezer}) and renders {@code golden-masters/<state-slug>/<operationId>.json};
 * then {@code golden-masters/index.json} (format version 1). A 204 records no file ({@code "file":
 * null}). Every operation sends the state's {@code authorization}, recorded as the index's {@code
 * headers: {Authorization: "{authorization}"}}, so the consumer's pact sends the bearer the state
 * hands it.
 *
 * <p>The release gate fails any operation of {@code docs/openapi.yml} this table does not cover, so
 * a new door joins it in the same commit.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file, including a
 * committed {@code .json} no interaction produces any more; {@code -Dgolden.update=true} rewrites.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  static final String PROVIDER = "qits-toggles";

  /** One recorded interaction; {@code body} is the JSON request body sent, or null for none. */
  record Interaction(
      String state, String operationId, String method, String path, String body, int status) {

    boolean self() {
      return true;
    }
  }

  static final String TOGGLES_PATH = "/toggles/api/toggles";

  static final String TOGGLE_PATH = TOGGLES_PATH + "/{slug}";

  /** The rules the configuration state's {@code PUT} sends: one of each kind, the STATIC last. */
  static final String RULES =
      "{\"version\":0,\"rules\":["
          + "{\"kind\":\"USER\",\"value\":true,\"userSubjects\":[\"tok-project-agent-qits-developer\"],\"afterInstant\":null},"
          + "{\"kind\":\"TIME\",\"value\":true,\"userSubjects\":null,\"afterInstant\":\"2026-12-01T00:00:00Z\"},"
          + "{\"kind\":\"STATIC\",\"value\":false,\"userSubjects\":null,\"afterInstant\":null}]}";

  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.A_TOGGLE_IS_DECLARED_ASKED_BY_AN_ADMIN,
              "getToggles",
              "GET",
              TOGGLES_PATH,
              null,
              200),
          new Interaction(
              ProviderStates.A_TOGGLE_IS_DECLARED_ASKED_BY_AN_ADMIN,
              "getToggle",
              "GET",
              TOGGLE_PATH,
              null,
              200),
          new Interaction(
              ProviderStates.A_TOGGLE_IS_DECLARED_ASKED_BY_AN_ADMIN,
              "putToggleRules",
              "PUT",
              TOGGLE_PATH + "/rules",
              RULES,
              200),
          new Interaction(
              ProviderStates.TOGGLE_TOGGLES_SMOKE_IS_DECLARED,
              "getToggleBoolean",
              "GET",
              "/toggles/api/toggle/{slug}/boolean",
              null,
              200));

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  @Inject ProviderStates states;

  /** The {@code Authorization} an interaction sends, already expanded: the state's caller. */
  private static String authorization(Interaction interaction, Map<String, String> params) {
    return params.get("authorization");
  }

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();
    Map<String, Map<String, String>> committed = committedParams(dir.resolve("index.json"));

    for (Interaction interaction : INTERACTIONS) {
      String slug = ProviderStates.slug(interaction.state());
      ProviderStates.Setup setup = states.setUp(interaction.state());
      Recorded recorded = record(interaction, setup, committed.getOrDefault(slug, Map.of()));
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", recorded.params());
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(recorded.params())) {
        failures.add("State '" + interaction.state() + "' froze to different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      if (interaction.self()) {
        operation.putObject("headers").put("Authorization", "{authorization}");
      }
      if (interaction.body() != null) {
        operation.set("body", JSON.readTree(interaction.body()));
      }
      operation.put("status", interaction.status());
      boolean empty = interaction.status() == 204;
      if (empty) {
        operation.putNull("file");
      } else {
        operation.put("file", file);
      }
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      frozen.putNull("listFilteredTo");
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }
      if (!empty) {
        written.add(file);
        check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
      }
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }
    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  /** One interaction's frozen answer, its frozen params and what was frozen where. */
  record Recorded(JsonNode body, ObjectNode params, Freezer freezer) {}

  private static Recorded record(
      Interaction interaction, ProviderStates.Setup setup, Map<String, String> committedParams)
      throws IOException {
    Map<String, String> params = setup.params();
    var request =
        given()
            .header("Accept", "application/json")
            .header("Authorization", authorization(interaction, params));
    if (interaction.body() != null) {
      request = request.contentType("application/json").body(interaction.body());
    }
    Response response =
        request.when().request(interaction.method(), expand(interaction.path(), params));
    String raw = response.asString();
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + raw);
    }
    String type = String.valueOf(response.getContentType());
    JsonNode body;
    if (raw.isEmpty()) {
      body = JsonNodeFactory.instance.nullNode();
    } else {
      body = JSON.readTree(raw);
    }
    Freezer freezer =
        new Freezer().seed(params, committedParams).uniqueTokens(setup.uniqueTokens());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    return new Recorded(freezer.freeze(body), frozenParams, freezer);
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  private static String expand(String template, Map<String, String> params) {
    Matcher m = TEMPLATE_PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  /** slug → frozen params of every state in the committed index; empty when there is none yet. */
  static Map<String, Map<String, String>> committedParams(Path index) throws IOException {
    if (!Files.isRegularFile(index)) {
      return Map.of();
    }
    Map<String, Map<String, String>> out = new TreeMap<>();
    for (JsonNode state : JSON.readTree(index.toFile()).path("states")) {
      Map<String, String> params = new TreeMap<>();
      state
          .path("params")
          .fields()
          .forEachRemaining(p -> params.put(p.getKey(), p.getValue().asText()));
      out.put(state.path("slug").asText(), params);
    }
    return out;
  }

  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
