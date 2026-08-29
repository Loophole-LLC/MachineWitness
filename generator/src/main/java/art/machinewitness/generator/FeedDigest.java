package art.machinewitness.generator;

import java.util.List;

/** One feed's headlines that fell inside this week's window. */
public record FeedDigest(String sourceName, List<NewsItem> items) {
}
