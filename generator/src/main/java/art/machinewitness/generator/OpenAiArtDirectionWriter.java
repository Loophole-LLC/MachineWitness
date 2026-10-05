package art.machinewitness.generator;

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

        return ArtDirectionJson.parse(text, "ChatGPT", model);
    }
}
