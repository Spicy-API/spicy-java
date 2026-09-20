package ai.spicyapi;

import java.time.LocalDate;

/**
 * Filter for {@link SpicyClient#listTasks(TaskFilter)}. Any {@code null} field is omitted.
 *
 * <p>The range is a half-open UTC interval {@code [from, to)} of at most 92 days. Left unset, the
 * service defaults {@code to} to tomorrow in UTC and {@code from} to seven days before it — a
 * window that moves, which is why {@link TaskPage} asks for pinned dates before paginating.
 *
 * @param from  first day, inclusive; {@code null} for the service default
 * @param to    last day, exclusive; {@code null} for the service default
 * @param state one lifecycle state to select
 * @param model an exact catalogue model identifier, or a declared alias
 * @param limit page size; the contract's floor is 1 and its default is 20
 * @param cursor the {@link TaskPage#nextCursor()} of the previous page, passed through unchanged
 */
public record TaskFilter(LocalDate from, LocalDate to, TaskState state, String model, Integer limit,
                         String cursor) {

    public TaskFilter {
        // Reject only the clearly illegal side. The upper bound (the contract says 100 today) is
        // deliberately not enforced locally: that is the platform's number, and copying it into the
        // client makes it a constant that will expire - the day the server relaxes to 200, this
        // would still reject 150 on its behalf, and reject it silently. Sending a value the server
        // dislikes costs one round trip and an ordinary 400, which is far cheaper than a stale
        // local ceiling. Same stance as the X-Spicy-Retention limit.
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (state == TaskState.UNKNOWN) {
            // UNKNOWN has an empty wire value; it is the parser's fallback, not a state in the
            // contract. Letting it through would send state= and the server would reject it as an
            // empty parameter, leaving the caller with a 400 that bears no relation to anything
            // they wrote.
            throw new IllegalArgumentException("UNKNOWN is this client's fallback, not a state to filter on");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
    }

    /**
     * A filter that selects nothing in particular.
     *
     * @return a filter with every field unset
     */
    public static TaskFilter all() {
        return new TaskFilter(null, null, null, null, null, null);
    }

    /**
     * Bounds the query to a half-open UTC interval.
     *
     * <p>Both dates move together on purpose: a cursor is only meaningful against the query it was
     * issued for, and half a range is how a walk ends up spanning two different windows.
     *
     * @param from first day, inclusive; {@code null} for the service default
     * @param to   last day, exclusive; {@code null} for the service default
     * @return a copy with the range set
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    public TaskFilter withRange(LocalDate from, LocalDate to) {
        return new TaskFilter(from, to, state, model, limit, cursor);
    }

    /**
     * @param value the lifecycle state to select
     * @return a copy with the state set
     * @throws IllegalArgumentException when {@code value} is {@link TaskState#UNKNOWN}
     */
    public TaskFilter withState(TaskState value) {
        return new TaskFilter(from, to, value, model, limit, cursor);
    }

    /**
     * @param value an exact catalogue model identifier, or a declared alias
     * @return a copy with the model set
     */
    public TaskFilter withModel(String value) {
        return new TaskFilter(from, to, state, value, limit, cursor);
    }

    /**
     * @param value the page size, at least 1
     * @return a copy with the page size set
     * @throws IllegalArgumentException when {@code value} is below 1
     */
    public TaskFilter withLimit(int value) {
        return new TaskFilter(from, to, state, model, value, cursor);
    }

    /**
     * Moves to the next page, keeping every other field as it is.
     *
     * @param value the {@link TaskPage#nextCursor()} of the page just read, unchanged
     * @return a copy pointing at the next page
     */
    public TaskFilter withCursor(String value) {
        return new TaskFilter(from, to, state, model, limit, value);
    }

    String toQuery() {
        StringBuilder query = new StringBuilder();
        Internal.appendParameter(query, "from", from == null ? null : from.toString());
        Internal.appendParameter(query, "to", to == null ? null : to.toString());
        Internal.appendParameter(query, "state", state == null ? null : state.wireValue());
        Internal.appendParameter(query, "model", model);
        Internal.appendParameter(query, "limit", limit == null ? null : limit.toString());
        Internal.appendParameter(query, "cursor", cursor);
        return query.length() == 0 ? "" : "?" + query;
    }
}
