package art.machinewitness.generator;

/**
 * Turns a raw API model id (e.g. "claude-opus-5", "gpt-5.6", "gemini-3.7-flash") into the
 * human-readable label published next to each piece, so the site always shows exactly which
 * model version made it instead of just the generic provider name - and so that label updates
 * itself automatically whenever GEMINI_MODEL/ANTHROPIC_MODEL/OPENAI_MODEL move to a new default,
 * with nothing to hand-maintain here.
 */
final class ModelLabel {

    private ModelLabel() {
    }

    static String humanize(String modelId) {
        StringBuilder label = new StringBuilder();
        for (String word : modelId.split("-")) {
            if (word.isEmpty()) {
                continue;
            }
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(word.equalsIgnoreCase("gpt") ? "GPT" : capitalize(word));
        }
        return label.toString();
    }

    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
