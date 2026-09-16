package art.machinewitness.generator;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds an RSS 2.0 feed from the manifest so past weeks' full text (prompts, rationales, image
 * links) are discoverable by feed readers and crawlers that don't execute the site's client-side
 * JS. One item per model per week, newest first (manifest.entries() is already stored that way).
 * There's no per-week permalink yet, so every item links back to the homepage - but each still
 * carries a stable, unique guid, the full prompt/rationale text, and an enclosure pointing at
 * that piece's actual rendered image.
 */
final class RssFeed {

    private static final String SITE_URL = "https://machinewitness.art/";
    private static final DateTimeFormatter RFC_822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private RssFeed() {
    }

    static String build(Manifest manifest) {
        StringBuilder items = new StringBuilder();
        for (ManifestEntry entry : manifest.entries()) {
            for (Piece piece : entry.pieces()) {
                items.append(item(entry, piece));
            }
        }

        String lastBuildDate = manifest.entries().isEmpty()
                ? RFC_822.format(Instant.now())
                : RFC_822.format(Instant.parse(manifest.entries().get(0).generatedAt()));

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n"
                + "<channel>\n"
                + "<title>Machine Witness</title>\n"
                + "<link>" + SITE_URL + "</link>\n"
                + "<description>" + escape("Every week, Gemini, Claude, and ChatGPT each research real AI "
                + "industry news and turn their own opinion into art, with a published rationale for why.")
                + "</description>\n"
                + "<language>en-us</language>\n"
                + "<atom:link href=\"" + SITE_URL + "feed.xml\" rel=\"self\" type=\"application/rss+xml\" />\n"
                + "<lastBuildDate>" + lastBuildDate + "</lastBuildDate>\n"
                + items
                + "</channel>\n"
                + "</rss>\n";
    }

    private static String item(ManifestEntry entry, Piece piece) {
        String artistSlug = piece.artist().toLowerCase(Locale.US);
        String label = piece.model() != null && !piece.model().isBlank() ? piece.model() : piece.artist();
        String guid = SITE_URL + "#" + entry.version() + "-" + artistSlug;
        String pubDate = RFC_822.format(Instant.parse(entry.generatedAt()));
        String description = "Prompt: " + piece.prompt() + " — Why " + label
                + " made this: " + piece.rationale();
        return "<item>\n"
                + "<title>" + escape(label + "'s take on AI news, week " + entry.version()
                        + " (" + entry.date() + ")") + "</title>\n"
                + "<link>" + SITE_URL + "</link>\n"
                + "<guid isPermaLink=\"false\">" + escape(guid) + "</guid>\n"
                + "<pubDate>" + pubDate + "</pubDate>\n"
                + "<category>" + escape(piece.artist()) + "</category>\n"
                + "<enclosure url=\"" + escape(piece.imageUrl()) + "\" type=\"image/png\" length=\"0\" />\n"
                + "<description>" + escape(description) + "</description>\n"
                + "</item>\n";
    }

    private static String escape(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
