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
 * Web search for the one model in the comparison that has none of its own. Every other writer
 * calls its provider's native, server-side search tool; DeepSeek's API has no such tool, so its
 * research has to be run client-side and handed back to it - see
 * {@link DeepSeekArtDirectionWriter} for why that asymmetry is published rather than hidden.
 *
 * <p>Tavily rather than a raw search engine because it returns extracted page content, not just
 * titles and meta descriptions: the brief tells every model that "a headline is not the story",
 * which only means anything if the search results actually contain the story.
 */
final class TavilySearch {

    private static final String SEARCH_URL = "https://api.tavily.com/search";
    private static final int MAX_RESULTS = 5;
    private static final int MAX_CONTENT_CHARS = 1200;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;

    TavilySearch(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Returns results already formatted for a model to read, or a plain-text failure note. */
    String search(String query) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("max_results", MAX_RESULTS);
        body.addProperty("search_depth", "advanced");
        body.addProperty("topic", "news");

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
            out.append("\n  ").append(string(result, "url"));
            String published = string(result, "published_date");
            if (!published.isBlank()) {
                out.append("\n  published: ").append(published);
            }
            String content = string(result, "content");
            if (!content.isBlank()) {
                if (content.length() > MAX_CONTENT_CHARS) {
                    content = content.substring(0, MAX_CONTENT_CHARS) + "...";
                }
                out.append("\n  ").append(content.replace("\n", " "));
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
