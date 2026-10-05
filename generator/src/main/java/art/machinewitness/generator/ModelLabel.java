package art.machinewitness.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw API model id (e.g. "claude-opus-5-5", "gpt-6-astra", "gemini-3.8-flash") into the
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

    /**
     * Longest numeric segment still treated as part of a version number. Providers use the same
     * hyphen for both jobs: "claude-opus-5-5" means Opus 5.5, while "claude-haiku-4-5-20251001"
     * means Haiku 4.5 released on a date. Two digits covers every major.minor in use and leaves
     * release stamps (20251001) and build numbers (2508) as their own word.
     */
    private static final int MAX_VERSION_PART_DIGITS = 2;

    private ModelLabel() {
    }

    static String humanize(String modelId) {
        List<String> words = new ArrayList<>();
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
            // "5" following "5" is the back half of a version the provider split on a hyphen -
            // rejoin it rather than publishing "Claude Opus 5 5".
            if (!words.isEmpty() && isVersionPart(word) && isVersionPart(words.get(words.size() - 1))) {
                words.set(words.size() - 1, words.get(words.size() - 1) + "." + word);
                continue;
            }
            String special = SPECIAL_CASED.get(word.toLowerCase());
            words.add(special != null ? special : capitalize(word));
        }
        return String.join(" ", words);
    }

    /** A bare number short enough to be a major or minor version rather than a release stamp. */
    private static boolean isVersionPart(String word) {
        if (word.isEmpty() || word.length() > MAX_VERSION_PART_DIGITS) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isDigit(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
