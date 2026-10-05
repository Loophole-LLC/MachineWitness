package art.machinewitness.generator;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.WebSearchTool;

import java.io.IOException;

/**
 * Asks Grok to turn this week's real AI-industry headlines into one piece of art. xAI serves an
 * OpenAI-Responses-compatible API at api.x.ai, so this reuses the same openai-java client as the
 * ChatGPT writer with a different base URL rather than adding a second SDK.
 *
 * <p>Grok's own server-side web search is enabled with a {@code {"type": "web_search"}} tool, so
 * like every other model here it researches the stories behind the headlines itself. The tool
 * type string isn't in openai-java's {@code WebSearchTool.Type} enum (it ships OpenAI's
 * {@code web_search_preview} values only - verified via javap against the real 2.13.0 core jar),
 * hence {@code Type.of}, which is the SDK's supported escape hatch for a value it doesn't know.
 *
 * <p>Grok's search reads X alongside the open web, which is the point of including it: it's the
 * one model in the comparison forming its opinion from a materially different corpus rather than
 * a different weighting of the same one.
 */
public final class GrokArtDirectionWriter implements ArtDirectionWriter {

    private static final String XAI_BASE_URL = "https://api.x.ai/v1";

    private final OpenAIClient client;
    private final String model;

    public GrokArtDirectionWriter(String apiKey, String model) {
        this.client = OpenAIOkHttpClient.builder()
                .baseUrl(XAI_BASE_URL)
                .apiKey(apiKey)
                .build();
        this.model = model;
    }

    @Override
    public ArtDirection write(WeeklyDigest digest) throws IOException {
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .addTool(WebSearchTool.builder()
                        .type(WebSearchTool.Type.of("web_search"))
                        .build())
                .input(ArtInstruction.render(digest))
                .build();

        Response response = client.responses().create(params);
        String text = response.output().stream()
                .filter(ResponseOutputItem::isMessage)
                .map(ResponseOutputItem::asMessage)
                .flatMap(message -> message.content().stream())
                .filter(content -> content.isOutputText())
                .map(content -> content.asOutputText().text())
                .findFirst()
                .orElseThrow(() -> new IOException("Grok response had no output text"));

        return ArtDirectionJson.parse(text, "Grok", model);
    }
}
