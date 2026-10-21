package art.machinewitness.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * DeepSeek's own web search, for the one writer whose chat API has no server-side search tool.
 * DeepSeek publishes search as a separate endpoint on the same key rather than as a tool the
 * model calls itself, so {@link DeepSeekArtDirectionWriter} runs the loop and hands the results
 * back - but the index and the ranking are DeepSeek's, the same as every other model here
 * researching with its own lab's search.
 *
 * <p>Returns each result's {@code summary}, which the API writes for a model to read (~600-800
 * chars), rather than the full page {@code content}: the brief tells every model that "a headline
 * is not the story", which only means anything if the results carry the story, and the summary
 * does without a page of boilerplate per hit.
 */
final class DeepSeekSearch {

    private static final String SEARCH_URL = "https://api.deepseek.com/v1/tools/search";
    private static final int MAX_RESULTS = 5;
    /** The endpoint rejects longer queries outright. */
    private static final int MAX_QUERY_CHARS = 128;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;

    DeepSeekSearch(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Returns results already formatted for a model to read, or a plain-text failure note. */
    String search(String query) throws IOException, InterruptedException {
        if (query.isBlank()) {
            return "Empty query. Call web_search with something to look up.";
        }
        if (query.codePointCount(0, query.length()) > MAX_QUERY_CHARS) {
            query = query.substring(0, query.offsetByCodePoints(0, MAX_QUERY_CHARS));
        }

        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("count", MAX_RESULTS);

        HttpRequest request = HttpRequest.newBuilder(URI.create(SEARCH_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Handed back to the model as a tool result rather than thrown: one dead search
            // shouldn't cost the piece, and the model can try a different query or proceed on
            // what it already found.
            return "Search failed (HTTP " + response.statusCode() + "). Try a different query.";
        }
        return format(JsonParser.parseString(response.body()).getAsJsonObject(), query);
    }

    private static String format(JsonObject response, String query) {
        JsonArray results = response.getAsJsonArray("results");
        if (results == null || results.isEmpty()) {
            return "No results for \"" + query + "\".";
        }
        StringBuilder out = new StringBuilder("Search results for \"" + query + "\":\n");
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            out.append("\n- ").append(string(result, "title"));
            String site = string(result, "site_name");
            if (!site.isBlank()) {
                out.append(" (").append(site).append(')');
            }
            out.append("\n  ").append(string(result, "url"));
            String published = string(result, "date");
            if (!published.isBlank()) {
                out.append("\n  published: ").append(published);
            }
            String summary = string(result, "summary");
            if (summary.isBlank()) {
                summary = string(result, "snippet");
            }
            if (!summary.isBlank()) {
                out.append("\n  ").append(summary.replace("\n", " "));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String string(JsonObject obj, String field) {
        JsonElement element = obj.get(field);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }
}
