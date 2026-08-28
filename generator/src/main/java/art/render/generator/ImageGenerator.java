package art.render.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Base64;

/** Renders a prompt with Gemini's image model and returns raw PNG bytes. */
public final class ImageGenerator {

    private final GeminiApi api;
    private final String model;

    public ImageGenerator(String apiKey, String model) {
        this.api = new GeminiApi(apiKey);
        this.model = model;
    }

    public byte[] generate(String prompt) throws IOException, InterruptedException {
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonArray responseModalities = new JsonArray();
        responseModalities.add("IMAGE");
        JsonObject generationConfig = new JsonObject();
        generationConfig.add("responseModalities", responseModalities);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        JsonObject response = api.generateContent(model, body);
        return extractImageBytes(response);
    }

    private static byte[] extractImageBytes(JsonObject response) throws IOException {
        try {
            JsonArray parts = response.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts");
            for (int i = 0; i < parts.size(); i++) {
                JsonObject part = parts.get(i).getAsJsonObject();
                if (part.has("inlineData")) {
                    String base64Data = part.getAsJsonObject("inlineData").get("data").getAsString();
                    return Base64.getDecoder().decode(base64Data);
                }
            }
            throw new IOException("Gemini image response had no inlineData part: " + response);
        } catch (RuntimeException e) {
            throw new IOException("Unexpected Gemini image response shape: " + response, e);
        }
    }
}
