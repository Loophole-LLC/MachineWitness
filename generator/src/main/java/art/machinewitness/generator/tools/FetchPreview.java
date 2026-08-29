package art.machinewitness.generator.tools;

import art.machinewitness.generator.FeedRef;
import art.machinewitness.generator.NewsItem;
import art.machinewitness.generator.OpmlSource;
import art.machinewitness.generator.RssFeedFetcher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Dev-only tool: prints what this week's OPML + RSS fetch would collect, with no Gemini calls
 * and no cost. Useful for sanity-checking the feed pipeline before spending on a real run.
 *
 * Usage: java -cp machinewitness-generator-1.0.0.jar art.machinewitness.generator.tools.FetchPreview [days]
 */
public final class FetchPreview {

    private FetchPreview() {
    }

    public static void main(String[] args) throws Exception {
        int days = args.length > 0 ? Integer.parseInt(args[0]) : 7;
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);

        List<FeedRef> feeds = new OpmlSource().fetchFeeds();
        System.out.println("Feeds in OPML: " + feeds.size());

        RssFeedFetcher fetcher = new RssFeedFetcher();
        int total = 0;
        for (FeedRef feed : feeds) {
            List<NewsItem> items;
            try {
                items = fetcher.fetchSince(feed, cutoff);
            } catch (Exception e) {
                System.out.println();
                System.out.println("## " + feed.title() + "  (failed: " + e.getMessage() + ")");
                continue;
            }
            System.out.println();
            System.out.println("## " + feed.title() + "  (" + items.size() + " items in last " + days + "d)");
            for (NewsItem item : items) {
                System.out.println("  - " + item.title());
            }
            total += items.size();
        }
        System.out.println();
        System.out.println("Total headlines: " + total);
    }
}
