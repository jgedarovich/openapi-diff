package org.openapitools.openapidiff.core.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.openapitools.openapidiff.core.exception.RendererException;
import org.openapitools.openapidiff.core.model.ChangedApiResponse;
import org.openapitools.openapidiff.core.model.ChangedContent;
import org.openapitools.openapidiff.core.model.ChangedMediaType;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.ChangedParameter;
import org.openapitools.openapidiff.core.model.ChangedParameters;
import org.openapitools.openapidiff.core.model.ChangedRequestBody;
import org.openapitools.openapidiff.core.model.ChangedResponse;
import org.openapitools.openapidiff.core.model.ChangedSchema;
import org.openapitools.openapidiff.core.model.DiffResult;
import org.openapitools.openapidiff.core.model.Endpoint;

/**
 * Renders a {@link ChangedOpenApi} diff as a lean, purpose-built JSON structure. Unlike {@link
 * JsonRender}, this renderer does not serialize the raw {@code Changed*} model objects; instead it
 * builds an {@link ObjectNode} tree with only the information consumers (CI pipelines, dashboards,
 * code generators) actually need.
 */
public class JsonWriter implements Render {

  private final ObjectMapper mapper;

  public JsonWriter() {
    mapper = new ObjectMapper();
    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mapper.findAndRegisterModules();
  }

  @Override
  public void render(ChangedOpenApi diff, OutputStreamWriter outputStreamWriter) {
    try {
      ObjectNode root = buildRoot(diff);
      mapper.writeValue(outputStreamWriter, root);
      outputStreamWriter.close();
    } catch (JsonProcessingException e) {
      throw new RendererException("Could not serialize diff as JSON", e);
    } catch (IOException e) {
      throw new RendererException(e);
    }
  }

  private ObjectNode buildRoot(ChangedOpenApi diff) {
    ObjectNode root = mapper.createObjectNode();
    root.put("compatible", diff.isCompatible());

    ArrayNode newEndpointsNode = mapper.createArrayNode();
    if (diff.getNewEndpoints() != null) {
      for (Endpoint ep : diff.getNewEndpoints()) {
        newEndpointsNode.add(endpointNode(ep));
      }
    }
    root.set("newEndpoints", newEndpointsNode);

    ArrayNode removedEndpointsNode = mapper.createArrayNode();
    if (diff.getMissingEndpoints() != null) {
      for (Endpoint ep : diff.getMissingEndpoints()) {
        removedEndpointsNode.add(endpointNode(ep));
      }
    }
    root.set("removedEndpoints", removedEndpointsNode);

    ArrayNode deprecatedEndpointsNode = mapper.createArrayNode();
    if (diff.getDeprecatedEndpoints() != null) {
      for (Endpoint ep : diff.getDeprecatedEndpoints()) {
        deprecatedEndpointsNode.add(endpointNode(ep));
      }
    }
    root.set("deprecatedEndpoints", deprecatedEndpointsNode);

    ArrayNode changedOpsNode = mapper.createArrayNode();
    if (diff.getChangedOperations() != null) {
      for (ChangedOperation op : diff.getChangedOperations()) {
        changedOpsNode.add(operationNode(op));
      }
    }
    root.set("changedOperations", changedOpsNode);

    ArrayNode changedSchemasNode = mapper.createArrayNode();
    if (diff.getChangedSchemas() != null) {
      for (ChangedSchema cs : diff.getChangedSchemas()) {
        ObjectNode schemaNode = schemaNode(cs, new HashSet<>());
        if (schemaNode != null) {
          ObjectNode entry = mapper.createObjectNode();
          String name = cs.getNewSchema() != null ? cs.getNewSchema().getName() : null;
          if (name == null && cs.getOldSchema() != null) {
            name = cs.getOldSchema().getName();
          }
          if (name != null) {
            entry.put("name", name);
          }
          entry.put("compatible", cs.isCompatible());
          if (cs.getContext() != null) {
            entry.set("context", mapper.valueToTree(cs.getContext()));
          }
          if (cs.getOldSchema() != null) {
            entry.set("oldSchema", mapper.valueToTree(cs.getOldSchema()));
          }
          if (cs.getNewSchema() != null) {
            entry.set("newSchema", mapper.valueToTree(cs.getNewSchema()));
          }
          entry.setAll(schemaNode);
          changedSchemasNode.add(entry);
        }
      }
    }
    root.set("changedSchemas", changedSchemasNode);

    return root;
  }

  private ObjectNode endpointNode(Endpoint ep) {
    ObjectNode node = mapper.createObjectNode();
    node.put("method", ep.getMethod().name());
    node.put("path", ep.getPathUrl());
    if (ep.getSummary() != null) {
      node.put("summary", ep.getSummary());
    }
    return node;
  }

  private ObjectNode operationNode(ChangedOperation op) {
    ObjectNode node = mapper.createObjectNode();
    node.put("method", op.getHttpMethod().name());
    node.put("path", op.getPathUrl());
    String operationId =
        op.getNewOperation() != null ? op.getNewOperation().getOperationId() : null;
    if (operationId == null && op.getOldOperation() != null) {
      operationId = op.getOldOperation().getOperationId();
    }
    if (operationId != null) {
      node.put("operationId", operationId);
    }
    node.put("compatible", op.isChanged().isCompatible());

    if (op.getSummary() != null && op.getSummary().isChanged() != DiffResult.NO_CHANGES) {
      node.set("summary", changeNode(op.getSummary().getLeft(), op.getSummary().getRight()));
    }
    if (op.getOperationId() != null && op.getOperationId().isChanged() != DiffResult.NO_CHANGES) {
      node.set(
          "operationId",
          changeNode(op.getOperationId().getLeft(), op.getOperationId().getRight()));
    }

    if (op.getParameters() != null) {
      ObjectNode paramsNode = parametersNode(op.getParameters());
      if (paramsNode != null) {
        node.set("parameters", paramsNode);
      }
    }

    if (op.getRequestBody() != null) {
      ObjectNode bodyNode = requestBodyNode(op.getRequestBody());
      if (bodyNode != null) {
        node.set("requestBody", bodyNode);
      }
    }

    if (op.getApiResponses() != null) {
      ObjectNode responsesNode = responsesNode(op.getApiResponses());
      if (responsesNode != null) {
        node.set("responses", responsesNode);
      }
    }

    return node;
  }

  private ObjectNode parametersNode(ChangedParameters params) {
    ObjectNode node = mapper.createObjectNode();
    boolean hasContent = false;

    if (params.getIncreased() != null && !params.getIncreased().isEmpty()) {
      ArrayNode addedNode = mapper.createArrayNode();
      for (Parameter p : params.getIncreased()) {
        ObjectNode pNode = mapper.createObjectNode();
        pNode.put("name", p.getName());
        pNode.put("in", p.getIn());
        if (p.getRequired() != null) {
          pNode.put("required", p.getRequired());
        }
        addedNode.add(pNode);
      }
      node.set("added", addedNode);
      hasContent = true;
    }

    if (params.getMissing() != null && !params.getMissing().isEmpty()) {
      ArrayNode removedNode = mapper.createArrayNode();
      for (Parameter p : params.getMissing()) {
        ObjectNode pNode = mapper.createObjectNode();
        pNode.put("name", p.getName());
        pNode.put("in", p.getIn());
        removedNode.add(pNode);
      }
      node.set("removed", removedNode);
      hasContent = true;
    }

    if (params.getChanged() != null && !params.getChanged().isEmpty()) {
      ArrayNode changedNode = mapper.createArrayNode();
      for (ChangedParameter cp : params.getChanged()) {
        changedNode.add(parameterNode(cp));
      }
      node.set("changed", changedNode);
      hasContent = true;
    }

    return hasContent ? node : null;
  }

  private ObjectNode parameterNode(ChangedParameter cp) {
    ObjectNode node = mapper.createObjectNode();
    node.put("name", cp.getName());
    node.put("in", cp.getIn());

    if (cp.isChangeRequired()) {
      Boolean oldReq = cp.getOldParameter() != null ? cp.getOldParameter().getRequired() : null;
      Boolean newReq = cp.getNewParameter() != null ? cp.getNewParameter().getRequired() : null;
      node.set("required", changeNode(oldReq, newReq));
    }

    if (cp.getSchema() != null) {
      ObjectNode schemaNode = schemaNode(cp.getSchema(), new HashSet<>());
      if (schemaNode != null) {
        node.set("schema", schemaNode);
      }
    }

    return node;
  }

  private ObjectNode requestBodyNode(ChangedRequestBody body) {
    ObjectNode node = mapper.createObjectNode();
    boolean hasContent = false;

    if (body.isChangeRequired()) {
      Boolean oldReq =
          body.getOldRequestBody() != null ? body.getOldRequestBody().getRequired() : null;
      Boolean newReq =
          body.getNewRequestBody() != null ? body.getNewRequestBody().getRequired() : null;
      node.set("required", changeNode(oldReq, newReq));
      hasContent = true;
    }

    if (body.getContent() != null) {
      ObjectNode contentNode = contentNode(body.getContent());
      if (contentNode != null) {
        node.set("content", contentNode);
        hasContent = true;
      }
    }

    return hasContent ? node : null;
  }

  private ObjectNode contentNode(ChangedContent content) {
    ObjectNode node = mapper.createObjectNode();
    boolean hasContent = false;

    if (content.getIncreased() != null && !content.getIncreased().isEmpty()) {
      ArrayNode addedNode = mapper.createArrayNode();
      for (String mediaType : content.getIncreased().keySet()) {
        addedNode.add(mediaType);
      }
      node.set("added", addedNode);
      hasContent = true;
    }

    if (content.getMissing() != null && !content.getMissing().isEmpty()) {
      ArrayNode removedNode = mapper.createArrayNode();
      for (String mediaType : content.getMissing().keySet()) {
        removedNode.add(mediaType);
      }
      node.set("removed", removedNode);
      hasContent = true;
    }

    if (content.getChanged() != null && !content.getChanged().isEmpty()) {
      ObjectNode changedNode = mapper.createObjectNode();
      for (Map.Entry<String, ChangedMediaType> entry : content.getChanged().entrySet()) {
        ChangedMediaType changedMediaType = entry.getValue();
        if (changedMediaType.getSchema() != null) {
          ObjectNode mediaTypeNode = mapper.createObjectNode();
          ObjectNode schemaNode = schemaNode(changedMediaType.getSchema(), new HashSet<>());
          if (schemaNode != null) {
            mediaTypeNode.set("schema", schemaNode);
          }
          changedNode.set(entry.getKey(), mediaTypeNode);
        }
      }
      if (changedNode.size() > 0) {
        node.set("changed", changedNode);
        hasContent = true;
      }
    }

    return hasContent ? node : null;
  }

  private ObjectNode responsesNode(ChangedApiResponse apiResp) {
    ObjectNode node = mapper.createObjectNode();
    boolean hasContent = false;

    if (apiResp.getIncreased() != null && !apiResp.getIncreased().isEmpty()) {
      ArrayNode addedNode = mapper.createArrayNode();
      for (Map.Entry<String, ApiResponse> entry : apiResp.getIncreased().entrySet()) {
        ObjectNode rNode = mapper.createObjectNode();
        rNode.put("statusCode", entry.getKey());
        if (entry.getValue().getDescription() != null) {
          rNode.put("description", entry.getValue().getDescription());
        }
        addedNode.add(rNode);
      }
      node.set("added", addedNode);
      hasContent = true;
    }

    if (apiResp.getMissing() != null && !apiResp.getMissing().isEmpty()) {
      ArrayNode removedNode = mapper.createArrayNode();
      for (String statusCode : apiResp.getMissing().keySet()) {
        removedNode.add(statusCode);
      }
      node.set("removed", removedNode);
      hasContent = true;
    }

    if (apiResp.getChanged() != null && !apiResp.getChanged().isEmpty()) {
      ObjectNode changedNode = mapper.createObjectNode();
      for (Map.Entry<String, ChangedResponse> entry : apiResp.getChanged().entrySet()) {
        ObjectNode rNode = responseNode(entry.getValue());
        if (rNode != null) {
          changedNode.set(entry.getKey(), rNode);
        }
      }
      if (changedNode.size() > 0) {
        node.set("changed", changedNode);
        hasContent = true;
      }
    }

    return hasContent ? node : null;
  }

  private ObjectNode responseNode(ChangedResponse resp) {
    ObjectNode node = mapper.createObjectNode();
    boolean hasContent = false;

    if (resp.getDescription() != null
        && resp.getDescription().isChanged() != DiffResult.NO_CHANGES) {
      node.set(
          "description",
          changeNode(resp.getDescription().getLeft(), resp.getDescription().getRight()));
      hasContent = true;
    }

    if (resp.getContent() != null) {
      ObjectNode contentNode = contentNode(resp.getContent());
      if (contentNode != null) {
        node.set("content", contentNode);
        hasContent = true;
      }
    }

    return hasContent ? node : null;
  }

  /**
   * Builds a JSON node describing the changes in a {@link ChangedSchema}. Returns {@code null} when
   * there are no reportable changes (so callers can omit empty nodes). The {@code visited} set
   * prevents infinite recursion on self-referential schemas.
   */
  private ObjectNode schemaNode(ChangedSchema schema, Set<ChangedSchema> visited) {
    if (schema == null || visited.contains(schema)) {
      return null;
    }
    visited.add(schema);

    ObjectNode node = mapper.createObjectNode();
    boolean hasContent = false;

    if (schema.isChangedType()) {
      String oldType = schema.getOldSchema() != null ? schema.getOldSchema().getType() : null;
      String newType = schema.getNewSchema() != null ? schema.getNewSchema().getType() : null;
      node.set("type", changeNode(oldType, newType));
      hasContent = true;
    }

    if (schema.isChangeFormat()) {
      String oldFormat = schema.getOldSchema() != null ? schema.getOldSchema().getFormat() : null;
      String newFormat = schema.getNewSchema() != null ? schema.getNewSchema().getFormat() : null;
      node.set("format", changeNode(oldFormat, newFormat));
      hasContent = true;
    }

    if (schema.getReadOnly() != null
        && schema.getReadOnly().isChanged() != DiffResult.NO_CHANGES) {
      Boolean oldRo = schema.getOldSchema() != null ? schema.getOldSchema().getReadOnly() : null;
      Boolean newRo = schema.getNewSchema() != null ? schema.getNewSchema().getReadOnly() : null;
      node.set("readOnly", changeNode(oldRo, newRo));
      hasContent = true;
    }

    if (schema.getWriteOnly() != null
        && schema.getWriteOnly().isChanged() != DiffResult.NO_CHANGES) {
      Boolean oldWo = schema.getOldSchema() != null ? schema.getOldSchema().getWriteOnly() : null;
      Boolean newWo = schema.getNewSchema() != null ? schema.getNewSchema().getWriteOnly() : null;
      node.set("writeOnly", changeNode(oldWo, newWo));
      hasContent = true;
    }

    if (schema.getNullable() != null
        && schema.getNullable().isChanged() != DiffResult.NO_CHANGES) {
      node.set(
          "nullable",
          changeNode(schema.getNullable().getLeft(), schema.getNullable().getRight()));
      hasContent = true;
    }

    if (schema.getMaxLength() != null
        && schema.getMaxLength().isChanged() != DiffResult.NO_CHANGES) {
      node.set(
          "maxLength",
          changeNode(schema.getMaxLength().getOldValue(), schema.getMaxLength().getNewValue()));
      hasContent = true;
    }

    if (schema.getMinLength() != null
        && schema.getMinLength().isChanged() != DiffResult.NO_CHANGES) {
      node.set(
          "minLength",
          changeNode(schema.getMinLength().getOldValue(), schema.getMinLength().getNewValue()));
      hasContent = true;
    }

    if (schema.getPattern() != null
        && schema.getPattern().isChanged() != DiffResult.NO_CHANGES) {
      node.set(
          "pattern",
          changeNode(
              schema.getPattern().getOldPattern(), schema.getPattern().getNewPattern()));
      hasContent = true;
    }

    if (schema.getEnumeration() != null
        && schema.getEnumeration().isChanged() != DiffResult.NO_CHANGES) {
      ObjectNode enumNode = mapper.createObjectNode();
      boolean enumHasContent = false;
      if (!schema.getEnumeration().getIncreased().isEmpty()) {
        ArrayNode addedEnum = mapper.createArrayNode();
        for (Object v : schema.getEnumeration().getIncreased()) {
          addedEnum.addPOJO(v);
        }
        enumNode.set("added", addedEnum);
        enumHasContent = true;
      }
      if (!schema.getEnumeration().getMissing().isEmpty()) {
        ArrayNode removedEnum = mapper.createArrayNode();
        for (Object v : schema.getEnumeration().getMissing()) {
          removedEnum.addPOJO(v);
        }
        enumNode.set("removed", removedEnum);
        enumHasContent = true;
      }
      if (enumHasContent) {
        node.set("enum", enumNode);
        hasContent = true;
      }
    }

    if (schema.getRequired() != null
        && schema.getRequired().isChanged() != DiffResult.NO_CHANGES) {
      ObjectNode reqNode = mapper.createObjectNode();
      boolean reqHasContent = false;
      if (!schema.getRequired().getIncreased().isEmpty()) {
        ArrayNode addedReq = mapper.createArrayNode();
        for (String s : schema.getRequired().getIncreased()) {
          addedReq.add(s);
        }
        reqNode.set("added", addedReq);
        reqHasContent = true;
      }
      if (!schema.getRequired().getMissing().isEmpty()) {
        ArrayNode removedReq = mapper.createArrayNode();
        for (String s : schema.getRequired().getMissing()) {
          removedReq.add(s);
        }
        reqNode.set("removed", removedReq);
        reqHasContent = true;
      }
      if (reqHasContent) {
        node.set("required", reqNode);
        hasContent = true;
      }
    }

    if (!schema.getIncreasedProperties().isEmpty()) {
      ArrayNode addedPropsNode = mapper.createArrayNode();
      for (String propName : schema.getIncreasedProperties().keySet()) {
        addedPropsNode.add(propName);
      }
      node.set("addedProperties", addedPropsNode);
      hasContent = true;
    }

    if (!schema.getMissingProperties().isEmpty()) {
      ArrayNode removedPropsNode = mapper.createArrayNode();
      for (String propName : schema.getMissingProperties().keySet()) {
        removedPropsNode.add(propName);
      }
      node.set("removedProperties", removedPropsNode);
      hasContent = true;
    }

    if (!schema.getChangedProperties().isEmpty()) {
      ObjectNode changedPropsNode = mapper.createObjectNode();
      for (Map.Entry<String, ChangedSchema> entry : schema.getChangedProperties().entrySet()) {
        ObjectNode propNode = schemaNode(entry.getValue(), visited);
        if (propNode != null) {
          changedPropsNode.set(entry.getKey(), propNode);
        }
      }
      if (changedPropsNode.size() > 0) {
        node.set("changedProperties", changedPropsNode);
        hasContent = true;
      }
    }

    if (schema.getItems() != null) {
      ObjectNode itemsNode = schemaNode(schema.getItems(), visited);
      if (itemsNode != null) {
        node.set("items", itemsNode);
        hasContent = true;
      }
    }

    visited.remove(schema);
    return hasContent ? node : null;
  }

  private ObjectNode changeNode(String from, String to) {
    ObjectNode node = mapper.createObjectNode();
    if (from != null) {
      node.put("from", from);
    } else {
      node.putNull("from");
    }
    if (to != null) {
      node.put("to", to);
    } else {
      node.putNull("to");
    }
    return node;
  }

  private ObjectNode changeNode(Boolean from, Boolean to) {
    ObjectNode node = mapper.createObjectNode();
    if (from != null) {
      node.put("from", from);
    } else {
      node.putNull("from");
    }
    if (to != null) {
      node.put("to", to);
    } else {
      node.putNull("to");
    }
    return node;
  }

  private ObjectNode changeNode(Integer from, Integer to) {
    ObjectNode node = mapper.createObjectNode();
    if (from != null) {
      node.put("from", from);
    } else {
      node.putNull("from");
    }
    if (to != null) {
      node.put("to", to);
    } else {
      node.putNull("to");
    }
    return node;
  }
}
