package art.render.generator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.WebSearchTool20260209;

/**
 * Asks Claude to turn this week's real AI-industry headlines into one piece of art. Uses
 * Claude's native web search tool so the model researches the actual story behind each headline
 * before forming an opinion, same as every other model in the weekly comparison.
 */
public final class ClaudeArtDirectionWriter implements ArtDirectionWriter {

    /** Structured-output target for Claude's response - schema is derived automatically. */
    private record ClaudeOutput(String prompt, String rationale) {
    }

    private final AnthropicClient client;
    private final String model;

    public ClaudeArtDirectionWriter(String apiKey, String model) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    @Override
    public ArtDirection write(WeeklyDigest digest) {
        String instruction = ArtInstruction.render(digest);

        StructuredMessageCreateParams<ClaudeOutput> params = MessageCreateParams.builder()
                .model(model)
                // Research + adaptive thinking + a verbose model eats a lot of the budget before
                // it even gets to the final JSON - 8192 truncated mid-response in testing
                // (a cut-off prompt and an empty rationale). 16000 is the skill-recommended
                // non-streaming default; revisit if this still truncates.
                .maxTokens(16000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .addTool(WebSearchTool20260209.builder().build())
                .outputConfig(ClaudeOutput.class)
                .addUserMessage(instruction)
                .build();

        ClaudeOutput output = client.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude response had no text block"));

        return new ArtDirection(output.prompt().strip(), output.rationale().strip(), "Claude");
    }
}
