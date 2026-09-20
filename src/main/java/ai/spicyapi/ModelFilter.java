package ai.spicyapi;

/**
 * Filter for {@link SpicyClient#listModels(ModelFilter)}. Any {@code null} field is omitted.
 *
 * @param modality        {@code image}, {@code video}, {@code audio} or {@code text}
 * @param provider        exact model creator identifier
 * @param task            exact task, for example {@code text-to-image}. Image editing accepts
 *                        either spelling: {@code edit} and {@code image-to-image} select the
 *                        same endpoints
 * @param search          free-text match, 128 characters at most
 * @param includeSchema   include each model's input JSON Schema, which is large; ask for it
 *                        when building a form, not when listing names
 * @param includeExamples include validated example inputs
 */
public record ModelFilter(String modality, String provider, String task, String search,
                          Boolean includeSchema, Boolean includeExamples) {

    /**
     * A filter that selects everything and requests no optional sections.
     *
     * @return a filter with every field unset
     */
    public static ModelFilter all() {
        return new ModelFilter(null, null, null, null, null, null);
    }

    /**
     * @param value the modality to select
     * @return a copy with the modality set
     */
    public ModelFilter withModality(String value) {
        return new ModelFilter(value, provider, task, search, includeSchema, includeExamples);
    }

    /**
     * @param value the model creator to select
     * @return a copy with the provider set
     */
    public ModelFilter withProvider(String value) {
        return new ModelFilter(modality, value, task, search, includeSchema, includeExamples);
    }

    /**
     * @param value the task to select
     * @return a copy with the task set
     */
    public ModelFilter withTask(String value) {
        return new ModelFilter(modality, provider, value, search, includeSchema, includeExamples);
    }

    /**
     * @param value free text to match
     * @return a copy with the search term set
     */
    public ModelFilter withSearch(String value) {
        return new ModelFilter(modality, provider, task, value, includeSchema, includeExamples);
    }

    /**
     * @param value whether to include each model's input schema
     * @return a copy with the flag set
     */
    public ModelFilter withIncludeSchema(boolean value) {
        return new ModelFilter(modality, provider, task, search, value, includeExamples);
    }

    /**
     * @param value whether to include validated example inputs
     * @return a copy with the flag set
     */
    public ModelFilter withIncludeExamples(boolean value) {
        return new ModelFilter(modality, provider, task, search, includeSchema, value);
    }

    String toQuery() {
        StringBuilder query = new StringBuilder();
        Internal.appendParameter(query, "modality", modality);
        Internal.appendParameter(query, "provider", provider);
        Internal.appendParameter(query, "task", task);
        Internal.appendParameter(query, "search", search);
        Internal.appendParameter(query, "includeSchema", flag(includeSchema));
        Internal.appendParameter(query, "includeExamples", flag(includeExamples));
        return query.length() == 0 ? "" : "?" + query;
    }

    private static String flag(Boolean value) {
        return value == null ? null : (value ? "1" : "0");
    }
}
