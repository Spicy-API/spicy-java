package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A validated example input for a model.
 *
 * @param id         the example's own identifier
 * @param input      a payload known to satisfy this model's current schema; usable as-is in
 *                   {@link CreateTaskRequest}
 * @param sortWeight display order, descending
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelExample(String id, JsonNode input, int sortWeight) {
}
