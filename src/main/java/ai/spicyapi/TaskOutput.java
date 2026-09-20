package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The result of a finished task.
 *
 * @param text   the answer when the endpoint answers in text. Not every generation produces a
 *               file: transcription and similar endpoints put the entire result here and return
 *               no assets at all
 * @param assets generated artifacts; empty for a text-only result
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskOutput(String text, List<TaskAsset> assets) {

    // Text-only outputs carry no assets field; normalise it to an empty list.
    public TaskOutput {
        assets = Internal.emptyIfNull(assets);
    }
}
