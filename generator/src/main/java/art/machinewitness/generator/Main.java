package art.machinewitness.generator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the Machine Witness generator. Run on a schedule (Cloud Scheduler -> Cloud Run Job in
 * production): once a week, pulls the last 7 days of headlines from the Turing Institute's AI
 * RSS feed list and asks Gemini, Claude, and ChatGPT to each independently turn them into their
 * own piece of art - same headlines, same instruction, three takes.
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

        LocalDate weekStart = today.with(weekFields.dayOfWeek(), 1);
        LocalDate weekEnd = weekStart.plusDays(6);
        String weekLabel = weekStart.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + " to " + weekEnd.format(DateTimeFormatter.ISO_LOCAL_DATE);
        WeeklyDigest digest = new WeeklyDigest(weekId, weekLabel, feedDigests, total);
        System.out.println("Found " + total + " headlines across " + feedDigests.size() + " sources for " + weekId);

        Map<String, ArtDirectionWriter> writers = new LinkedHashMap<>();
        writers.put("gemini", new GeminiArtDirectionWriter(config.geminiApiKey(), config.geminiModel()));
        if (config.anthropicApiKey() != null && !config.anthropicApiKey().isBlank()) {
            writers.put("claude", new ClaudeArtDirectionWriter(config.anthropicApiKey(), config.anthropicModel()));
        } else {
            System.out.println("Skipping Claude - no ANTHROPIC_API_KEY set.");
        }
        if (config.openaiApiKey() != null && !config.openaiApiKey().isBlank()) {
            writers.put("chatgpt", new OpenAiArtDirectionWriter(config.openaiApiKey(), config.openaiModel()));
        } else {
            System.out.println("Skipping ChatGPT - no OPENAI_API_KEY set.");
        }

        ImageGenerator imageGenerator = new ImageGenerator(config.geminiApiKey(), config.imageModel());
        Instant generatedAt = Instant.now();
        List<Piece> pieces = new ArrayList<>();
        for (Map.Entry<String, ArtDirectionWriter> entry : writers.entrySet()) {
            String slug = entry.getKey();
            try {
                System.out.println("Asking " + slug + " to make this week's piece...");
                ArtDirection direction = entry.getValue().write(digest);
                // A model that hits its token ceiling mid-response can come back with a
                // technically-valid but garbage object (seen in testing: a cut-off prompt and a
                // rationale of just ",") rather than throwing - catch that here too, not just
                // exceptions.
                if (direction.prompt().length() < 40 || direction.rationale().length() < 20) {
                    throw new IllegalStateException("response looks truncated (prompt="
                            + direction.prompt().length() + " chars, rationale="
                            + direction.rationale().length() + " chars)");
                }
                System.out.println(direction.writtenBy() + " prompt: " + direction.prompt());
                System.out.println(direction.writtenBy() + " rationale: " + direction.rationale());

                System.out.println("Rendering " + direction.writtenBy() + "'s piece with " + config.imageModel() + "...");
                byte[] png = imageGenerator.generate(direction.prompt());

                // Cache-bust with the generation time: GCS serves images/*.png with a default 1h
                // cache, and every regeneration for a given week reuses the same object name, so
                // without this a corrected/regenerated piece would keep showing viewers the stale
                // cached PNG for up to an hour even after manifest.json had already moved on.
                String imageUrl = store.publishImage(weekId, slug, png) + "?v=" + generatedAt.toEpochMilli();
                pieces.add(new Piece(direction.writtenBy(), direction.prompt(), direction.rationale(), imageUrl));
            } catch (Exception e) {
                // One provider's outage, billing issue, or bad response shouldn't cost the other
                // two their completed work - skip it and publish whichever pieces did succeed.
                System.out.println("Skipping " + slug + " this week - " + e.getMessage());
            }
        }

        if (pieces.isEmpty()) {
            System.out.println("No pieces were successfully generated this week - not publishing.");
            return;
        }

        List<String> highlights = feedDigests.stream()
                .flatMap(feed -> feed.items().stream().map(item -> feed.sourceName() + ": " + item.title()))
                .toList();
        ManifestEntry entry = new ManifestEntry(
                weekId,
                weekLabel,
                weekId,
                "https://github.com/alan-turing-institute/ai-rss-feeds",
                pieces,
                highlights,
                generatedAt.toString()
        );
        manifest.prepend(entry);
        store.saveManifest(manifest);

        System.out.println("Published " + pieces.size() + " piece(s) for " + weekId + ".");
    }
}
