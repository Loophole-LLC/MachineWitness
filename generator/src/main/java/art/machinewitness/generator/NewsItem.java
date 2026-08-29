package art.machinewitness.generator;

import java.time.Instant;

/** One headline from an RSS feed. */
public record NewsItem(String title, String link, Instant published) {
}
