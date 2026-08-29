package art.machinewitness.generator;

/**
 * One model's finished creative direction: the image prompt handed to the image model, the
 * first-person rationale for why, and which model wrote it - published on the site alongside the
 * artwork.
 */
public record ArtDirection(String prompt, String rationale, String writtenBy) {
}
