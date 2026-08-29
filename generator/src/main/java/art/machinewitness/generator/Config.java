package art.machinewitness.generator;

import java.util.Map;

/**
 * Configuration is env-var driven, matching the rest of this workspace's Cloud Run deploys.
 * LOCAL_OUT (or --local-out=DIR) switches persistence to local disk instead of GCS, so a full
 * run only needs GEMINI_API_KEY - no GCP project required for local testing.
 *
 * ANTHROPIC_API_KEY and OPENAI_API_KEY are optional: Gemini always runs (it's required), and
 * Claude/ChatGPT each only join the weekly comparison once their key is set, so this still works
 * with just a Gemini key while the other two are being provisioned.
 */
public record Config(
        String geminiApiKey,
        String geminiModel,
        String imageModel,
        String anthropicApiKey,
        String anthropicModel,
        String openaiApiKey,
        String openaiModel,
        String gcsBucket,
        String localOutDir
) {

    private static final String DEFAULT_GEMINI_MODEL = "gemini-3.6-flash";
    private static final String DEFAULT_IMAGE_MODEL = "gemini-3-pro-image";
    private static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5";
    // Check this against OpenAI's current model list before deploying - unlike GEMINI_MODEL and
    // ANTHROPIC_MODEL, this default hasn't been verified against a current reference.
    private static final String DEFAULT_OPENAI_MODEL = "gpt-5.1";

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
                gcsBucket,
                localOut
        );
    }
}
