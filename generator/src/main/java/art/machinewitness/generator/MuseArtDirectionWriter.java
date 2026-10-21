package art.machinewitness.generator;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.WebSearchTool;

import java.io.IOException;

/**
 * Asks Meta's Muse to turn this week's real AI-industry headlines into one piece of art. Meta's
 * Model API serves an OpenAI-Responses-compatible API at api.meta.ai, so like Grok this reuses
 * the ChatGPT writer's openai-java client with a different base URL rather than adding an SDK.
 *
 * <p>Meta's server-side web search is the same {@code {"type": "web_search"}} tool xAI uses,
 * which openai-java's {@code WebSearchTool.Type} enum doesn't know - hence {@code Type.of}; see
 * {@link GrokArtDirectionWriter}.
 *
 * <p>Muse is the second model here from a company whose core business is a social network,
 * alongside Grok - but where Grok's search reads X, Meta's search reads the open web, so what it
 * brings is a different set of incentives rather than a different corpus.
 */
public final class MuseArtDirectionWriter implements ArtDirectionWriter {

    private static final String META_BASE_URL = "https://api.meta.ai/v1";

    private final OpenAIClient client;
    private final String model;

    public MuseArtDirectionWriter(String apiKey, String model) {
        this.client = OpenAIOkHttpClient.builder()
                .baseUrl(META_BASE_URL)
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
        // Last, not first: Muse is a reasoning model with search, and like Grok it can narrate
        // before searching and only give the JSON in a later message.
        String text = response.output().stream()
                .filter(ResponseOutputItem::isMessage)
                .map(ResponseOutputItem::asMessage)
                .flatMap(message -> message.content().stream())
                .filter(content -> content.isOutputText())
                .map(content -> content.asOutputText().text())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IOException("Muse response had no output text"));

        return ArtDirectionJson.parse(text, "Muse", model);
    }
}
