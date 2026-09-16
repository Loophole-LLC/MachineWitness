package art.machinewitness.generator;

import java.util.List;

/**
 * One model's finished creative direction: the image prompt handed to the image model, the
 * first-person rationale for why, which model wrote it (both the provider name and the exact
 * model version), and which headlines it's citing - published on the site alongside the artwork.
 */
public record ArtDirection(String prompt, String rationale, String writtenBy, String model, List<Citation> citations) {
}
