package art.render.generator;

/**
 * One week's finished creative direction: the image prompt handed to the image model, and the
 * first-person rationale for why that's the self-portrait this week - published on the site
 * alongside the artwork.
 */
public record ArtDirection(String prompt, String rationale) {
}
