package art.machinewitness.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks Gemini to turn this week's real AI-industry headlines into one piece of art. Uses
 * Gemini's Google Search tool so the model researches the actual story behind each headline
 * before forming an opinion, rather than reacting to bare RSS title text.
 */
public final class GeminiArtDirectionWriter implements ArtDirectionWriter {

    private final GeminiApi api;
    private final String model;

    public GeminiArtDirectionWriter(String apiKey, String model) {
        this.api = new GeminiApi(apiKey);
        this.model = model;
    }

    @Override
    public ArtDirection write(WeeklyDigest digest) throws IOException, InterruptedException {
        String instruction = ArtInstruction.render(digest);

        JsonObject part = new JsonObject();
        part.addProperty("text", instruction);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 1.15);
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.add("responseSchema", responseSchema());

        // Grounds the piece in real research instead of bare headline text: lets the model
        // search the actual stories behind this week's headlines before it forms an opinion,
        // the way any artist would look into their subject before committing to a reaction.
        JsonObject googleSearch = new JsonObject();
        JsonObject searchTool = new JsonObject();
        searchTool.add("google_search", googleSearch);
        JsonArray tools = new JsonArray();
        tools.add(searchTool);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("tools", tools);
        body.add("generationConfig", generationConfig);

        JsonObject response = api.generateContent(model, body);
        return parseDirection(extractText(response), model);
    }

    private static JsonObject responseSchema() {
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "STRING");

        JsonObject properties = new JsonObject();
        properties.add("prompt", stringType);
        properties.add("rationale", stringType);
        properties.add("citations", citationsSchema());

        JsonArray required = new JsonArray();
        required.add("prompt");
        required.add("rationale");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    private static JsonObject citationsSchema() {
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "STRING");

        JsonObject citationProperties = new JsonObject();
        citationProperties.add("quote", stringType);
        citationProperties.add("headline", stringType);

        JsonArray citationRequired = new JsonArray();
        citationRequired.add("quote");
        citationRequired.add("headline");

        JsonObject citationItem = new JsonObject();
        citationItem.addProperty("type", "OBJECT");
        citationItem.add("properties", citationProperties);
        citationItem.add("required", citationRequired);

        JsonObject citationsArray = new JsonObject();
        citationsArray.addProperty("type", "ARRAY");
        citationsArray.add("items", citationItem);
        return citationsArray;
    }

    private static ArtDirection parseDirection(String json, String model) throws IOException {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String prompt = obj.get("prompt").getAsString().strip();
            String rationale = obj.get("rationale").getAsString().strip();
            List<Citation> citations = new ArrayList<>();
            if (obj.has("citations") && obj.get("citations").isJsonArray()) {
                for (var element : obj.getAsJsonArray("citations")) {
                    JsonObject c = element.getAsJsonObject();
                    citations.add(new Citation(
                            c.get("quote").getAsString(), c.get("headline").getAsString(), null, null));
                }
            }
            return new ArtDirection(prompt, rationale, "Gemini", ModelLabel.humanize(model), citations);
        } catch (RuntimeException e) {
            throw new IOException("Unexpected Gemini JSON response shape: " + json, e);
        }
    }

    private static String extractText(JsonObject response) throws IOException {
        try {
            // With search grounding on, a response can carry more than one part (e.g. a thought
            // part alongside the answer) - scan for the first one that actually has text instead
            // of assuming it's always parts[0].
            JsonArray parts = response.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts");
            for (int i = 0; i < parts.size(); i++) {
                JsonObject part = parts.get(i).getAsJsonObject();
                if (part.has("text")) {
                    return part.get("text").getAsString();
                }
            }
            throw new IOException("Gemini text response had no part with text: " + response);
        } catch (RuntimeException e) {
            throw new IOException("Unexpected Gemini text response shape: " + response, e);
        }
    }
}
