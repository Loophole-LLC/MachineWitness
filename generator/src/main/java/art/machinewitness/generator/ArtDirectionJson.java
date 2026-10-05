package art.machinewitness.generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {prompt, rationale, citations} object out of a model's reply for every writer that
 * can't be held to a response schema server-side. Gemini and Claude each constrain the shape at
 * the API level (responseSchema / outputConfig) and parse inline; ChatGPT, Grok, DeepSeek and
 * Mistral all come back as free text that merely promises to be JSON, so they share this.
 */
final class ArtDirectionJson {

    private ArtDirectionJson() {
    }

    static ArtDirection parse(String text, String writtenBy, String model) throws IOException {
        try {
            JsonObject obj = JsonParser.parseString(extractJsonObject(text, writtenBy)).getAsJsonObject();
            String prompt = obj.get("prompt").getAsString().strip();
            String rationale = obj.get("rationale").getAsString().strip();
            List<Citation> citations = new ArrayList<>();
            if (obj.has("citations") && obj.get("citations").isJsonArray()) {
                for (var element : obj.getAsJsonArray("citations")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject c = element.getAsJsonObject();
                    if (c.has("quote") && c.has("headline")) {
                        citations.add(new Citation(
                                c.get("quote").getAsString(), c.get("headline").getAsString(), null, null));
                    }
                }
            }
            return new ArtDirection(prompt, rationale, writtenBy, ModelLabel.humanize(model), citations);
        } catch (RuntimeException e) {
            throw new IOException("Unexpected " + writtenBy + " JSON response shape: " + text, e);
        }
    }

    /**
     * Pulls out just the {...} body in case the model wraps its JSON in prose or a markdown code
     * fence despite being told not to - the usual failure mode for a model answering a long
     * creative brief with a search tool in the loop.
     */
    private static String extractJsonObject(String text, String writtenBy) throws IOException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IOException("No JSON object found in " + writtenBy + " response: " + text);
        }
        return text.substring(start, end + 1);
    }
}
