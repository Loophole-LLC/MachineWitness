package art.machinewitness.generator;

import java.util.Map;

/**
 * Turns a raw API model id (e.g. "claude-opus-5", "gpt-5.6", "gemini-3.7-flash") into the
 * human-readable label published next to each piece, so the site always shows exactly which
 * model version made it instead of just the generic provider name - and so that label updates
 * itself automatically whenever any of the *_MODEL defaults move to a new flagship, with
 * nothing to hand-maintain here.
 */
final class ModelLabel {

    /** Words whose conventional capitalization isn't just "first letter upper". */
    private static final Map<String, String> SPECIAL_CASED = Map.of(
            "gpt", "GPT",
            "deepseek", "DeepSeek");

    private ModelLabel() {
    }

    static String humanize(String modelId) {
        StringBuilder label = new StringBuilder();
        for (String word : modelId.split("-")) {
            if (word.isEmpty()) {
                continue;
            }
            // A "-latest" alias (Mistral publishes these instead of a stable flagship id) names
            // no version, so reading it out as "Latest" would be worse than saying nothing.
            // Writers that can resolve the alias to a concrete id do; this is the fallback.
            if (word.equalsIgnoreCase("latest")) {
                continue;
            }
            if (label.length() > 0) {
                label.append(' ');
            }
            String special = SPECIAL_CASED.get(word.toLowerCase());
            label.append(special != null ? special : capitalize(word));
        }
        return label.toString();
    }

    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
