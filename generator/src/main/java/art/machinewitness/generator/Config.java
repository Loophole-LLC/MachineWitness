package art.machinewitness.generator;

import java.util.Map;

/**
 * Configuration is env-var driven, matching the rest of this workspace's Cloud Run deploys.
 * LOCAL_OUT (or --local-out=DIR) switches persistence to local disk instead of GCS, so a full
 * run only needs GEMINI_API_KEY - no GCP project required for local testing.
 *
 * Only GEMINI_API_KEY is required: Gemini both writes a take and renders every image, so it
 * always runs. Every other model joins the weekly comparison once its own key is set and is
 * skipped with a log line otherwise, so this still works with one key while the rest are being
 * provisioned, and a provider can be dropped for a week by unsetting one variable.
 *
 * DeepSeek additionally needs TAVILY_API_KEY: it's the only model here with no web search of its
 * own, and a DeepSeek that can't do the reading isn't answering the same brief as the other
 * five - see DeepSeekArtDirectionWriter.
 */
public record Config(
        String geminiApiKey,
        String geminiModel,
        String imageModel,
        String anthropicApiKey,
        String anthropicModel,
        String openaiApiKey,
        String openaiModel,
        String xaiApiKey,
        String xaiModel,
        String deepseekApiKey,
        String deepseekModel,
        String mistralApiKey,
        String mistralModel,
        String tavilyApiKey,
        String gcsBucket,
        String localOutDir
) {

    private static final String DEFAULT_IMAGE_MODEL = "gemini-3-pro-image";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-3.8-flash";
    private static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5-5";
    private static final String DEFAULT_OPENAI_MODEL = "gpt-6-astra";
    private static final String DEFAULT_XAI_MODEL = "grok-4.7";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-pro";
    // Mistral publishes "-latest" aliases rather than a stable flagship id; the Conversations
    // response echoes back the concrete version it resolved to, which is what gets published.
    private static final String DEFAULT_MISTRAL_MODEL = "mistral-medium-latest";

    public static Config fromEnv(String[] args) {
        Map<String, String> env = System.getenv();
        String localOut = env.get("LOCAL_OUT");
        for (String arg : args) {
            if (arg.startsWith("--local-out=")) {
                localOut = arg.substring("--local-out=".length());
            }
        }

        String geminiApiKey = env.get("GEMINI_API_KEY");
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required (Google AI Studio API key).");
        }

        String gcsBucket = env.get("GCS_BUCKET");
        if ((localOut == null || localOut.isBlank()) && (gcsBucket == null || gcsBucket.isBlank())) {
            throw new IllegalStateException("Set GCS_BUCKET (production) or LOCAL_OUT/--local-out=DIR (local testing).");
        }

        return new Config(
                geminiApiKey,
                env.getOrDefault("GEMINI_MODEL", DEFAULT_GEMINI_MODEL),
                env.getOrDefault("IMAGE_MODEL", DEFAULT_IMAGE_MODEL),
                env.get("ANTHROPIC_API_KEY"),
                env.getOrDefault("ANTHROPIC_MODEL", DEFAULT_ANTHROPIC_MODEL),
                env.get("OPENAI_API_KEY"),
                env.getOrDefault("OPENAI_MODEL", DEFAULT_OPENAI_MODEL),
                env.get("XAI_API_KEY"),
                env.getOrDefault("XAI_MODEL", DEFAULT_XAI_MODEL),
                env.get("DEEPSEEK_API_KEY"),
                env.getOrDefault("DEEPSEEK_MODEL", DEFAULT_DEEPSEEK_MODEL),
                env.get("MISTRAL_API_KEY"),
                env.getOrDefault("MISTRAL_MODEL", DEFAULT_MISTRAL_MODEL),
                env.get("TAVILY_API_KEY"),
                gcsBucket,
                localOut
        );
    }
}
