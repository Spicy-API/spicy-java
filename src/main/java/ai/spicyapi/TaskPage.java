package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One page of task history, newest first.
 *
 * <p>Pages read live state rather than a frozen snapshot, so a task can change state, or settle,
 * while its pages are being walked.
 *
 * <p>Walking every page means repeating the filter unchanged and moving only the cursor:
 *
 * <pre>{@code
 * TaskFilter filter = TaskFilter.all()
 *         .withRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8));   // pin the dates
 * TaskPage page = client.listTasks(filter);
 * while (true) {
 *     page.items().forEach(...);
 *     if (!page.hasMore() || page.nextCursor() == null) break;
 *     page = client.listTasks(filter.withCursor(page.nextCursor()));
 * }
 * }</pre>
 *
 * <p><b>Pin {@code from} and {@code to} before a walk that might cross midnight UTC.</b> Left
 * unset, the service recomputes its default window — {@code to} is tomorrow in UTC — on every
 * request, so a walk that starts at 23:59 and continues at 00:01 asks two different questions and
 * splices the answers together. Nothing reports that: the pages arrive, the loop ends, and the
 * result is quietly wrong.
 *
 * <p>Both stop conditions are worth checking. The contract says {@code nextCursor} is present only
 * when {@code hasMore} is true, so either one saying "done" means done; trusting only one of them
 * turns a service-side surprise into an endless paging loop, which bills real requests and looks
 * like a hang.
 *
 * @param items      this page's rows, newest first
 * @param hasMore    whether another page follows
 * @param nextCursor the cursor for the next page, present only when {@code hasMore} is true. It is
 *                   opaque: pass it back unchanged and never construct one
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskPage(List<TaskSummary> items, boolean hasMore, String nextCursor) {

    public TaskPage {
        items = Internal.emptyIfNull(items);
    }

    /**
     * The identifiers on this page, in the order they were listed.
     *
     * @return the task identifiers on this page
     */
    public List<String> taskIds() {
        return items.stream().map(TaskSummary::taskId).toList();
    }
}
