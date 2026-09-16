package art.machinewitness.generator;

import java.util.List;

/**
 * One model's take on the week: which model made it (provider name plus the exact model
 * version), its prompt, rationale, headline citations, and rendered image.
 */
public record Piece(String artist, String model, String prompt, String rationale, List<Citation> citations, String imageUrl) {
}
