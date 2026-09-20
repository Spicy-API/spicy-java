package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * When a task's content is scheduled to be removed, and which layer decided it.
 *
 * @param outputsExpireAt when the media objects and result payload are removed
 * @param promptsExpireAt when the stored request text is erased; the task row, its state,
 *                        amounts and request ID remain
 * @param source          which layer constrained the value: {@code header}, {@code account} or
 *                        {@code platform}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskRetention(Instant outputsExpireAt, Instant promptsExpireAt, String source) {
}
