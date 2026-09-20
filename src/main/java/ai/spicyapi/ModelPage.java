package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A page of the catalogue. The catalogue is not paginated; {@code total} equals the item count.
 *
 * @param total number of models the filter matched
 * @param items the models themselves
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelPage(int total, List<ApiModel> items) {

    // As above: a missing items array normalises to an empty list.
    public ModelPage {
        items = Internal.emptyIfNull(items);
    }

    /**
     * The identifiers {@link CreateTaskRequest} accepts, for every model on this page.
     *
     * @return the callable identifiers of every model on this page
     */
    public List<String> modelIds() {
        return items.stream().map(ApiModel::model).toList();
    }
}
