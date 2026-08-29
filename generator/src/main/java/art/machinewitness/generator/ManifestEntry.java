package art.machinewitness.generator;

import java.util.List;

/** One published week, as stored in manifest.json and rendered by the site's gallery. */
public record ManifestEntry(
        String version,
        String date,
        String sourcePath,
        String sourceUrl,
        List<Piece> pieces,
        List<String> highlights,
        String generatedAt
) {
}
