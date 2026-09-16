package art.machinewitness.generator;

/**
 * Points one short, verbatim quote from a piece's rationale back at the specific headline that
 * provoked it. {@code url} and {@code source} are only populated once {@link Main} has resolved
 * the model-supplied {@code headline} text against that week's real feed items - never trust a
 * model-supplied URL directly, since it can hallucinate one.
 */
public record Citation(String quote, String headline, String url, String source) {
}
