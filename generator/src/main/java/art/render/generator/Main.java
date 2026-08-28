package art.render.generator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for the Render generator. Run on a schedule (Cloud Scheduler -> Cloud Run Job in
 * production): once a week, pulls the last 7 days of headlines from the Turing Institute's AI
 * RSS feed list and turns them into one new piece of art.
 */
public final class Main {

    private static final int MAX_ITEMS_PER_FEED = 12;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv(args);

        GalleryStore store = config.localOutDir() != null
                ? new LocalGalleryStore(config.localOutDir())
                : new GcsGalleryStore(config.gcsBucket());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        WeekFields weekFields = WeekFields.ISO;
        String weekId = String.format("%d-W%02d",
                today.get(weekFields.weekBasedYear()), today.get(weekFields.weekOfWeekBasedYear()));

        Manifest manifest = store.loadManifest();
        if (manifest.hasSource(weekId)) {
            System.out.println("Up to date: " + weekId + " has already been turned into art.");
            return;
        }

        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        System.out.println("Fetching this week's AI news (" + weekId + ")...");

        List<FeedRef> feeds = new OpmlSource().fetchFeeds();
        RssFeedFetcher fetcher = new RssFeedFetcher();
        List<FeedDigest> feedDigests = new ArrayList<>();
        int total = 0;
        for (FeedRef feed : feeds) {
            List<NewsItem> items;
            try {
                items = fetcher.fetchSince(feed, cutoff);
            } catch (Exception e) {
                System.out.println("  skipping " + feed.title() + ": " + e.getMessage());
                continue;
            }
            if (items.size() > MAX_ITEMS_PER_FEED) {
                items = items.subList(items.size() - MAX_ITEMS_PER_FEED, items.size());
            }
            if (!items.isEmpty()) {
                feedDigests.add(new FeedDigest(feed.title(), items));
                total += items.size();
                System.out.println("  " + feed.title() + ": " + items.size() + " headlines");
            }
        }

        if (total == 0) {
            System.out.println("No AI news found in the past 7 days - skipping.");
            return;
        }

        String weekLabel = today.minusDays(6).format(DateTimeFormatter.ISO_LOCAL_DATE)
                + " to " + today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        WeeklyDigest digest = new WeeklyDigest(weekId, weekLabel, feedDigests, total);
        System.out.println("Found " + total + " headlines across " + feedDigests.size() + " sources for " + weekId);

        System.out.println("Asking Gemini to make this week's piece...");
        ArtDirection direction = new PromptWriter(config.geminiApiKey(), config.textModel()).write(digest);
        System.out.println("Prompt: " + direction.prompt());
        System.out.println("Rationale: " + direction.rationale());

        System.out.println("Rendering the image with " + config.imageModel() + "...");
        byte[] png = new ImageGenerator(config.geminiApiKey(), config.imageModel()).generate(direction.prompt());

        Instant generatedAt = Instant.now();
        // Cache-bust with the generation time: GCS serves images/*.png with a default 1h cache,
        // and every regeneration for a given week reuses the same object name, so without this a
        // corrected/regenerated piece would keep showing viewers the stale cached PNG for up to
        // an hour even after manifest.json (short-cached, and fetched cache-busted by site.js)
        // had already moved on.
        String imageUrl = store.publishImage(weekId, png) + "?v=" + generatedAt.toEpochMilli();
        List<String> highlights = feedDigests.stream()
                .flatMap(feed -> feed.items().stream().map(item -> feed.sourceName() + ": " + item.title()))
                .toList();
        ManifestEntry entry = new ManifestEntry(
                weekId,
                weekLabel,
                weekId,
                "https://github.com/alan-turing-institute/ai-rss-feeds",
                direction.prompt(),
                direction.rationale(),
                highlights,
                imageUrl,
                generatedAt.toString()
        );
        manifest.prepend(entry);
        store.saveManifest(manifest);

        System.out.println("Published new artwork for " + weekId + ": " + imageUrl);
    }
}
