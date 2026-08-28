package art.render.generator;

import java.util.List;

/** Everything notable across all feeds for one ISO week. */
public record WeeklyDigest(String weekId, String weekLabel, List<FeedDigest> feeds, int totalItems) {
}
