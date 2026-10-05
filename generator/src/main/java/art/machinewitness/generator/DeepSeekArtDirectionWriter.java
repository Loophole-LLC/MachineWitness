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
 * Asks DeepSeek to turn this week's real AI-industry headlines into one piece of art - the one
 * model in the comparison that isn't a Silicon Valley lab reasoning about Silicon Valley.
 *
 * <p>It is also the one model here with no web search of its own. DeepSeek's API is
 * OpenAI-compatible chat completions with {@code tools} and JSON output but no server-side
 * search tool, so the brief's "do the reading" step has to be run client-side: this declares a
 * {@code web_search} function, runs whatever queries DeepSeek asks for against
 * {@link TavilySearch}, and feeds the results back until it stops asking. The model still
 * decides what to look up and what to conclude - but its research is mediated by a search
 * provider this project picked, where the other five each use their own lab's.
 *
 * <p>That asymmetry is real and it is disclosed on the site rather than papered over: on a
 * project whose whole claim is that the opinion is the only variable, a quiet difference in how
 * one model gets its facts would be exactly the kind of thing worth being honest about.
 */
public final class DeepSeekArtDirectionWriter implements ArtDirectionWriter {

    private static final String COMPLETIONS_URL = "https://api.deepseek.com/chat/completions";

    /** Enough rounds for real research, few enough that a tool-calling loop can't run away. */
    private static final int MAX_TOOL_ROUNDS = 8;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;
    private final String model;
    private final TavilySearch search;

    public DeepSeekArtDirectionWriter(String apiKey, String model, String tavilyApiKey) {
        this.apiKey = apiKey;
        this.model = model;
        this.search = new TavilySearch(tavilyApiKey);
    }

    @Override
    public ArtDirection write(WeeklyDigest digest) throws IOException, InterruptedException {
        JsonArray messages = new JsonArray();
        messages.add(message("user", ArtInstruction.render(digest)));

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            // Drop the search tool on the last round so the loop always ends in a real answer
            // rather than an unanswered tool call we'd have to throw away.
            boolean allowTools = round < MAX_TOOL_ROUNDS;
            JsonObject assistant = complete(messages, allowTools);
            JsonArray toolCalls = assistant.getAsJsonArray("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                return ArtDirectionJson.parse(content(assistant), "DeepSeek", model);
            }

            messages.add(assistant);
            for (JsonElement element : toolCalls) {
                JsonObject call = element.getAsJsonObject();
                messages.add(toolResult(call.get("id").getAsString(), runSearch(call)));
            }
        }
        throw new IOException("DeepSeek never produced a final answer within "
                + MAX_TOOL_ROUNDS + " tool rounds");
    }

    private String runSearch(JsonObject toolCall) throws IOException, InterruptedException {
        JsonObject function = toolCall.getAsJsonObject("function");
        String name = function.get("name").getAsString();
        if (!name.equals("web_search")) {
            return "Unknown tool \"" + name + "\". Only web_search is available.";
        }
        String query;
        try {
            // Arguments arrive as a JSON string, and a model can get that string wrong - a bad
            // one is a tool result the model can recover from, not a failed run.
            JsonObject arguments = JsonParser.parseString(function.get("arguments").getAsString())
                    .getAsJsonObject();
            query = arguments.get("query").getAsString();
        } catch (RuntimeException e) {
            return "Could not parse tool arguments. Call web_search with a JSON object like "
                    + "{\"query\": \"...\"}.";
        }
        System.out.println("  deepseek searching: " + query);
        return search.search(query);
    }

    private JsonObject complete(JsonArray messages, boolean allowTools)
            throws IOException, InterruptedException {
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", 1.15);
        body.add("response_format", responseFormat);
        if (allowTools) {
            body.add("tools", searchToolSchema());
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(COMPLETIONS_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("DeepSeek API error for model " + model + ": HTTP "
                    + response.statusCode() + " - " + response.body());
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message");
        } catch (RuntimeException e) {
            throw new IOException("Unexpected DeepSeek response shape: " + response.body(), e);
        }
    }

    private static JsonArray searchToolSchema() {
        JsonObject queryProperty = new JsonObject();
        queryProperty.addProperty("type", "string");
        queryProperty.addProperty("description",
                "What to search the web for, phrased as a search query.");

        JsonObject properties = new JsonObject();
        properties.add("query", queryProperty);

        JsonArray required = new JsonArray();
        required.add("query");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);

        JsonObject function = new JsonObject();
        function.addProperty("name", "web_search");
        function.addProperty("description",
                "Search the web for recent news and background on a story. Use it to find out "
                        + "what was actually said, shipped, or argued behind a headline before "
                        + "forming an opinion. Call it as many times as you need.");
        function.add("parameters", parameters);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);

        JsonArray tools = new JsonArray();
        tools.add(tool);
        return tools;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject toolResult(String toolCallId, String content) {
        JsonObject message = message("tool", content);
        message.addProperty("tool_call_id", toolCallId);
        return message;
    }

    private static String content(JsonObject assistant) throws IOException {
        JsonElement content = assistant.get("content");
        if (content == null || !content.isJsonPrimitive() || content.getAsString().isBlank()) {
            throw new IOException("DeepSeek response had no text content: " + assistant);
        }
        return content.getAsString();
    }
}
