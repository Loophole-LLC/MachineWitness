package art.render.generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.WebSearchTool;

import java.io.IOException;

/**
 * Asks ChatGPT to turn this week's real AI-industry headlines into one piece of art. Uses
 * OpenAI's Responses API with its native web search tool so the model researches the actual
 * story behind each headline before forming an opinion, same as every other model in the weekly
 * comparison. (Verified against the real openai-java 2.13.0 jar via javap - the Chat Completions
 * endpoint used in the first cut of this class had no built-in search tool.)
 */
public final class OpenAiArtDirectionWriter implements ArtDirectionWriter {

    private final OpenAIClient client;
    private final String model;

    public OpenAiArtDirectionWriter(String apiKey, String model) {
        this.client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    @Override
    public ArtDirection write(WeeklyDigest digest) throws IOException {
        String instruction = ArtInstruction.render(digest);

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .addTool(WebSearchTool.builder()
                        .type(WebSearchTool.Type.WEB_SEARCH_PREVIEW)
                        .build())
                .input(instruction)
                .build();

        Response response = client.responses().create(params);
        String text = response.output().stream()
                .filter(ResponseOutputItem::isMessage)
                .map(ResponseOutputItem::asMessage)
                .flatMap(message -> message.content().stream())
                .filter(content -> content.isOutputText())
                .map(content -> content.asOutputText().text())
                .findFirst()
                .orElseThrow(() -> new IOException("ChatGPT response had no output text"));

        return parseDirection(text);
    }

    private static ArtDirection parseDirection(String text) throws IOException {
        try {
            JsonObject obj = JsonParser.parseString(extractJsonObject(text)).getAsJsonObject();
            String prompt = obj.get("prompt").getAsString().strip();
            String rationale = obj.get("rationale").getAsString().strip();
            return new ArtDirection(prompt, rationale, "ChatGPT");
        } catch (RuntimeException e) {
            throw new IOException("Unexpected ChatGPT JSON response shape: " + text, e);
        }
    }

    /**
     * ChatGPT isn't schema-constrained here, so defensively pull out just the {...} body in case
     * it wraps the JSON in prose or a markdown code fence despite being told not to.
     */
    private static String extractJsonObject(String text) throws IOException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IOException("No JSON object found in ChatGPT response: " + text);
        }
        return text.substring(start, end + 1);
    }
}
