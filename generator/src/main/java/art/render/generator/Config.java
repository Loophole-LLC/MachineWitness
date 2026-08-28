package art.render.generator;

import java.util.Map;

/**
 * Configuration is env-var driven, matching the rest of this workspace's Cloud Run deploys.
 * LOCAL_OUT (or --local-out=DIR) switches persistence to local disk instead of GCS, so a full
 * run only needs GEMINI_API_KEY - no GCP project required for local testing.
 */
public record Config(
        String geminiApiKey,
        String textModel,
        String imageModel,
        String gcsBucket,
        String localOutDir
) {

    private static final String DEFAULT_TEXT_MODEL = "gemini-3.6-flash";
    private static final String DEFAULT_IMAGE_MODEL = "gemini-3-pro-image";

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
                env.getOrDefault("TEXT_MODEL", DEFAULT_TEXT_MODEL),
                env.getOrDefault("IMAGE_MODEL", DEFAULT_IMAGE_MODEL),
                gcsBucket,
                localOut
        );
    }
}
