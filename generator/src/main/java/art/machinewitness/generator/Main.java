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
import java.util.function.Supplier;

/**
 * Entry point for the Machine Witness generator. Run on a schedule (Cloud Scheduler -> Cloud Run Job in
 * production): once a week, pulls the last 7 days of headlines from the Turing Institute's AI
 * RSS feed list and asks Gemini, Claude, ChatGPT, Grok, DeepSeek and Mistral to each
 * independently turn them into their own piece of art - same headlines, same instruction, six
 * takes. The brief they get is identical and deliberately unprescriptive about form and style
 * (see ArtInstruction): whatever each model's work has in common with its own past work is
 * something it brought, not something this project told it to be.
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

        // Label the window the headlines actually came from - the seven days ending today - and
        // not the calendar week weekId names. The scheduler only gets past the hasSource() check
        // on the first firing of a new ISO week, i.e. a Monday, so a Monday-to-Sunday label
        // described a week that had barely started: a model that did the research it was asked to
        // do would find every article dated before the range it had been handed, and spend its
        // published rationale arguing with the brief instead of reacting to the news.
        LocalDate windowStart = LocalDate.ofInstant(cutoff, ZoneOffset.UTC);
        String weekLabel = windowStart.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + " to " + today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        WeeklyDigest digest = new WeeklyDigest(weekId, weekLabel, feedDigests, total);
        System.out.println("Found " + total + " headlines across " + feedDigests.size() + " sources for " + weekId);

        // Six independent takes on one brief. Gemini is unconditional - it renders every
        // image regardless of who wrote the prompt, so its key is the one hard requirement.
        Map<String, ArtDirectionWriter> writers = new LinkedHashMap<>();
        writers.put("gemini", new GeminiArtDirectionWriter(config.geminiApiKey(), config.geminiModel()));
        addIfKeyed(writers, "claude", "Claude", "ANTHROPIC_API_KEY", config.anthropicApiKey(),
                () -> new ClaudeArtDirectionWriter(config.anthropicApiKey(), config.anthropicModel()));
        addIfKeyed(writers, "chatgpt", "ChatGPT", "OPENAI_API_KEY", config.openaiApiKey(),
                () -> new OpenAiArtDirectionWriter(config.openaiApiKey(), config.openaiModel()));
        addIfKeyed(writers, "grok", "Grok", "XAI_API_KEY", config.xaiApiKey(),
                () -> new GrokArtDirectionWriter(config.xaiApiKey(), config.xaiModel()));
        // DeepSeek is the one model with no web search of its own, so it needs a search key on
        // top of its own - without one it would be answering a different brief than the rest.
        if (config.deepseekApiKey() != null && !config.deepseekApiKey().isBlank()
                && (config.tavilyApiKey() == null || config.tavilyApiKey().isBlank())) {
            System.out.println("Skipping DeepSeek - DEEPSEEK_API_KEY is set but TAVILY_API_KEY "
                    + "isn't, and DeepSeek has no web search of its own to fall back on.");
        } else {
            addIfKeyed(writers, "deepseek", "DeepSeek", "DEEPSEEK_API_KEY", config.deepseekApiKey(),
                    () -> new DeepSeekArtDirectionWriter(
                            config.deepseekApiKey(), config.deepseekModel(), config.tavilyApiKey()));
        }
        addIfKeyed(writers, "mistral", "Mistral", "MISTRAL_API_KEY", config.mistralApiKey(),
                () -> new MistralArtDirectionWriter(config.mistralApiKey(), config.mistralModel()));

        // Looked up by exact headline title so a citation can only ever point at a real item from
        // this week's actual feeds, never a model-hallucinated one - see resolveCitations().
        Map<String, NewsItem> itemsByHeadline = new LinkedHashMap<>();
        Map<String, String> sourceByHeadline = new LinkedHashMap<>();
        for (FeedDigest feed : feedDigests) {
            for (NewsItem item : feed.items()) {
                itemsByHeadline.putIfAbsent(item.title(), item);
                sourceByHeadline.putIfAbsent(item.title(), feed.sourceName());
            }
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
                String cacheBuster = "?v=" + generatedAt.toEpochMilli();
                String imageUrl = store.publishImage(weekId, slug, png) + cacheBuster;
                // The grid loads this instead of the ~2MB render; see Thumbnail. A failure here
                // costs the piece its thumbnail, not its place in the gallery - the site falls
                // back to the full image when thumbnailUrl is absent.
                String thumbnailUrl = null;
                try {
                    thumbnailUrl = store.publishThumbnail(weekId, slug, Thumbnail.jpeg(png)) + cacheBuster;
                } catch (Exception e) {
                    System.out.println("  no thumbnail for " + slug + " - " + e.getMessage());
                }
                List<Citation> citations = resolveCitations(
                        direction.citations(), direction.rationale(), itemsByHeadline, sourceByHeadline);
                pieces.add(new Piece(direction.writtenBy(), direction.model(), direction.prompt(),
                        direction.rationale(), citations, imageUrl, thumbnailUrl));
            } catch (Exception e) {
                // One provider's outage, billing issue, or bad response shouldn't cost the
                // others their completed work - skip it and publish whichever pieces succeeded.
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
        store.saveFeed(manifest);

        System.out.println("Published " + pieces.size() + " piece(s) for " + weekId + ".");
    }

    /**
     * Adds one model to the weekly comparison if its key is present, and says so in the log if
     * it isn't. Every provider past Gemini is optional on purpose: a model can be added while
     * its key is still being provisioned, or dropped for a week, by one env var.
     */
    private static void addIfKeyed(Map<String, ArtDirectionWriter> writers, String slug, String name,
            String envVar, String apiKey, Supplier<ArtDirectionWriter> writer) {
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping " + name + " - no " + envVar + " set.");
            return;
        }
        writers.put(slug, writer.get());
    }

    private static final int MAX_CITATIONS_PER_PIECE = 3;

    /**
     * Keeps only citations that check out against ground truth: the quote must actually appear
     * verbatim in the rationale that was just published (so the site can locate and highlight it),
     * and the headline must match a real item from this week's feeds (so the link is real, not a
     * model-hallucinated URL). Everything else is silently dropped rather than published broken.
     */
    private static List<Citation> resolveCitations(List<Citation> raw, String rationale,
            Map<String, NewsItem> itemsByHeadline, Map<String, String> sourceByHeadline) {
        List<Citation> resolved = new ArrayList<>();
        for (Citation c : raw) {
            if (resolved.size() >= MAX_CITATIONS_PER_PIECE) {
                break;
            }
            if (c.quote() == null || c.headline() == null || c.quote().isBlank() || !rationale.contains(c.quote())) {
                continue;
            }
            NewsItem item = itemsByHeadline.get(c.headline());
            if (item == null) {
                continue;
            }
            resolved.add(new Citation(c.quote(), c.headline(), item.link(), sourceByHeadline.get(c.headline())));
        }
        return resolved;
    }
}
