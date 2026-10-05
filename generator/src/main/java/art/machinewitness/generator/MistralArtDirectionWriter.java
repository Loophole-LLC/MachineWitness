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
 * Asks Mistral to turn this week's real AI-industry headlines into one piece of art, and brings
 * a European vantage into a comparison that is otherwise entirely US and Chinese labs - the
 * week's AI news reads differently from inside the jurisdiction that regulates it first.
 *
 * <p>Mistral's built-in web search is only wired up on the Conversations API
 * ({@code /v1/conversations}), not Chat Completions - the docs are explicit that the search
 * tools "aren't supported" there because those responses carry no result references. This uses
 * the agent-less form of Conversations (a {@code model} plus {@code tools} inline, rather than
 * an {@code agent_id}) with {@code store: false}, so nothing is persisted on Mistral's side and
 * there's no agent object to create, reuse or leak once per weekly run.
 *
 * <p>Plain REST rather than the Mistral SDK, matching {@link GeminiApi}: one endpoint, one call,
 * no dependency worth adding for it.
 */
public final class MistralArtDirectionWriter implements ArtDirectionWriter {

    private static final String CONVERSATIONS_URL = "https://api.mistral.ai/v1/conversations";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;
    private final String model;

    public MistralArtDirectionWriter(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public ArtDirection write(WeeklyDigest digest) throws IOException, InterruptedException {
        JsonObject searchTool = new JsonObject();
        searchTool.addProperty("type", "web_search");
        JsonArray tools = new JsonArray();
        tools.add(searchTool);

        JsonObject completionArgs = new JsonObject();
        completionArgs.addProperty("temperature", 1.0);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("inputs", ArtInstruction.render(digest));
        body.add("tools", tools);
        body.add("completion_args", completionArgs);
        // Nothing here needs to outlive the run, and the brief is the whole conversation.
        body.addProperty("store", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(CONVERSATIONS_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Mistral API error for model " + model + ": HTTP "
                    + response.statusCode() + " - " + response.body());
        }

        JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject message = lastMessageOutput(parsed);
        // Prefer the concrete model id the API echoes back, so a "-latest" alias still gets
        // published as the exact version that made the piece - the whole point of ModelLabel.
        String resolvedModel = message.has("model") && message.get("model").isJsonPrimitive()
                ? message.get("model").getAsString()
                : model;
        return ArtDirectionJson.parse(extractText(message), "Mistral", resolvedModel);
    }

    /**
     * A Conversations response is a list of entries, not a single message: every web search the
     * model ran shows up as its own {@code tool.execution} entry alongside the answer, so take
     * the last {@code message.output} rather than assuming a position.
     */
    private static JsonObject lastMessageOutput(JsonObject response) throws IOException {
        try {
            JsonArray outputs = response.getAsJsonArray("outputs");
            for (int i = outputs.size() - 1; i >= 0; i--) {
                JsonObject entry = outputs.get(i).getAsJsonObject();
                String type = entry.has("type") ? entry.get("type").getAsString() : "";
                if (type.equals("message.output")) {
                    return entry;
                }
            }
            throw new IOException("Mistral response had no message.output entry: " + response);
        } catch (RuntimeException e) {
            throw new IOException("Unexpected Mistral response shape: " + response, e);
        }
    }

    /**
     * An entry's {@code content} is either a bare string or a list of chunks - {@code text} ones
     * carrying the answer and {@code tool_reference} ones carrying the cited sources. Only the
     * text chunks are the reply; concatenating in order rebuilds it.
     */
    private static String extractText(JsonObject message) throws IOException {
        JsonElement content = message.get("content");
        if (content == null) {
            throw new IOException("Mistral message.output had no content: " + message);
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement element : content.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject chunk = element.getAsJsonObject();
                boolean isText = !chunk.has("type") || chunk.get("type").getAsString().equals("text");
                if (isText && chunk.has("text")) {
                    text.append(chunk.get("text").getAsString());
                }
            }
            if (text.length() > 0) {
                return text.toString();
            }
        }
        throw new IOException("Mistral message.output had no text content: " + message);
    }
}
