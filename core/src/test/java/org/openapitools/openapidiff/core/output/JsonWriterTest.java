package org.openapitools.openapidiff.core.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import org.junit.jupiter.api.Test;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;

public class JsonWriterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private String render(String oldSpec, String newSpec) {
    JsonWriter writer = new JsonWriter();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ChangedOpenApi diff = OpenApiCompare.fromLocations(oldSpec, newSpec);
    writer.render(diff, new OutputStreamWriter(out));
    return out.toString();
  }

  private JsonNode parse(String json) throws Exception {
    return MAPPER.readTree(json);
  }

  @Test
  public void smokeTestProducesNonBlankOutput() {
    String output = render("missing_property_1.yaml", "missing_property_2.yaml");
    assertThat(output).isNotBlank();
  }

  @Test
  public void compatibleFlagTrueWhenSpecsAreIdentical() throws Exception {
    String output = render("missing_property_1.yaml", "missing_property_1.yaml");
    JsonNode root = parse(output);
    assertThat(root.get("compatible").asBoolean()).isTrue();
  }

  @Test
  public void compatibleFlagTrueForCompatibleChange() throws Exception {
    // Removing a non-required property from a response schema is compatible
    String output = render("missing_property_1.yaml", "missing_property_2.yaml");
    JsonNode root = parse(output);
    assertThat(root.get("compatible").asBoolean()).isTrue();
  }

  @Test
  public void compatibleFlagFalseForBreakingChange() throws Exception {
    // Removing an endpoint is a breaking change (OPENAPI_ENDPOINTS_DECREASED is enabled by default)
    String output = render("add_endpoint_2.yaml", "add_endpoint_1.yaml");
    JsonNode root = parse(output);
    assertThat(root.get("compatible").asBoolean()).isFalse();
  }

  @Test
  public void newEndpointsArePopulated() throws Exception {
    String output = render("add_endpoint_1.yaml", "add_endpoint_2.yaml");
    JsonNode root = parse(output);
    JsonNode newEndpoints = root.get("newEndpoints");
    assertThat(newEndpoints.size()).isGreaterThan(0);
    assertThat(newEndpoints.get(0).get("path").asText()).contains("petId2");
  }

  @Test
  public void removedEndpointsArePopulated() throws Exception {
    // Go from a spec with two endpoints to one with one — the removed endpoint is a breaking change
    String output = render("add_endpoint_2.yaml", "add_endpoint_1.yaml");
    JsonNode root = parse(output);
    assertThat(root.get("removedEndpoints").size()).isGreaterThan(0);
  }

  @Test
  public void propertyLevelSchemaShowsRemovedProperties() throws Exception {
    String output = render("missing_property_1.yaml", "missing_property_2.yaml");
    JsonNode root = parse(output);
    // changedOperations[0] is GET /, its default response has application/json schema
    // with childProperty removed
    JsonNode schema =
        root.at(
            "/changedOperations/0/responses/changed/default/content/changed/application~1json/schema");
    assertThat(schema.isMissingNode()).isFalse();
    JsonNode removedProperties = schema.get("removedProperties");
    assertThat(removedProperties).isNotNull();
    assertThat(removedProperties.isArray()).isTrue();
    boolean found = false;
    for (JsonNode prop : removedProperties) {
      if ("childProperty".equals(prop.asText())) {
        found = true;
        break;
      }
    }
    assertThat(found).as("removedProperties should contain 'childProperty'").isTrue();
  }

  @Test
  public void doesNotFailForJsr310Types() {
    String output = render("jsr310_property_1.yaml", "jsr310_property_2.yaml");
    assertThat(output).isNotBlank();
  }

  @Test
  public void doesNotLeakRawOpenApiObjects() {
    String output = render("missing_property_1.yaml", "missing_property_2.yaml");
    // Raw OpenAPI objects would include an "openapi" version field such as "openapi":"3.0.1"
    assertThat(output).doesNotContain("\"openapi\"");
  }
}
