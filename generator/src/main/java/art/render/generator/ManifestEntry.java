package art.render.generator;

import java.util.List;

/** One published artwork, as stored in manifest.json and rendered by the site's gallery. */
public record ManifestEntry(
        String version,
        String date,
        String sourcePath,
        String sourceUrl,
        String prompt,
        String rationale,
        List<String> highlights,
        String imageUrl,
        String generatedAt
) {
}
