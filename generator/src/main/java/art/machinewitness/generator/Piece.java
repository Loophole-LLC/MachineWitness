package art.machinewitness.generator;

/** One model's take on the week: which model made it, its prompt, rationale, and rendered image. */
public record Piece(String artist, String prompt, String rationale, String imageUrl) {
}
