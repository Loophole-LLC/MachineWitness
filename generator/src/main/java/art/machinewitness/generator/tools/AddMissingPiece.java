package art.machinewitness.generator.tools;

import art.machinewitness.generator.ArtDirection;
import art.machinewitness.generator.ArtDirectionWriter;
import art.machinewitness.generator.Citation;
import art.machinewitness.generator.Config;
import art.machinewitness.generator.FeedDigest;
import art.machinewitness.generator.FeedRef;
import art.machinewitness.generator.GalleryStore;
import art.machinewitness.generator.GcsGalleryStore;
import art.machinewitness.generator.ImageGenerator;
import art.machinewitness.generator.LocalGalleryStore;
import art.machinewitness.generator.Main;
import art.machinewitness.generator.Manifest;
import art.machinewitness.generator.ManifestEntry;
import art.machinewitness.generator.NewsItem;
import art.machinewitness.generator.OpmlSource;
import art.machinewitness.generator.Piece;
import art.machinewitness.generator.RssFeedFetcher;
import art.machinewitness.generator.Thumbnail;
import art.machinewitness.generator.WeeklyDigest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-off maintenance tool: adds one model's piece to a week that has already been published
 * without it, because that model failed on the scheduled run.
 *
 * <p>The scheduled job never revisits a week once it's in the manifest, so without this a single
 * provider hiccup costs that model its place in the comparison for good.
 *
 * <p>The model gets the same brief the others did, not a fresh one: the brief only names each
 * headline's source and title, and the entry's {@code highlights} record exactly those, in order.
 * The feeds are re-fetched only to recover headline links, so citations resolve against the same
 * ground truth as on the scheduled run; a headline that has since dropped out of its feed simply
 * can't be cited.
 *
 * <p>It rewrites the live manifest, so it writes a local backup copy first and prints the path.
 *
 * Usage (needs the same keys as the job, plus GCS credentials):
 *   GEMINI_API_KEY=... XAI_API_KEY=... GCS_BUCKET=... \
 *     GOOGLE_ACCESS_TOKEN=$(gcloud auth print-access-token) \
 *     java -cp machinewitness-generator-1.0.0.jar \
 *     art.machinewitness.generator.tools.AddMissingPiece 2026-W43 grok
 */
public final class AddMissingPiece {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AddMissingPiece() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: AddMissingPiece <week-id> <model-slug>");
        }
        String weekId = args[0];
        String slug = args[1];

        Config config = Config.fromEnv(new String[0]);
        GalleryStore store = config.localOutDir() != null
                ? new LocalGalleryStore(config.localOutDir())
                : new GcsGalleryStore(config.gcsBucket());

        Map<String, ArtDirectionWriter> writers = Main.writers(config);
        ArtDirectionWriter writer = writers.get(slug);
        if (writer == null) {
            throw new IllegalStateException("No writer for '" + slug + "' - is its API key set?");
        }

        Manifest manifest = store.loadManifest();
        List<ManifestEntry> entries = manifest.entries();
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).sourcePath().equals(weekId)) {
                index = i;
            }
        }
        if (index < 0) {
            throw new IllegalStateException(weekId + " isn't in the manifest.");
        }
        ManifestEntry entry = entries.get(index);
        for (Piece piece : entry.pieces()) {
            if (slug.equals(slugOf(entry.version(), piece.imageUrl()))) {
                System.out.println(weekId + " already has a " + slug + " piece - nothing to do.");
                return;
            }
        }

        // Rebuild the brief from the published highlights, preserving their source grouping.
        Map<String, List<String>> titlesBySource = new LinkedHashMap<>();
        for (String highlight : entry.highlights()) {
            int split = highlight.indexOf(": ");
            titlesBySource.computeIfAbsent(highlight.substring(0, split), k -> new ArrayList<>())
                    .add(highlight.substring(split + 2));
        }
        Map<String, String> linkByTitle = currentLinks(Instant.parse(entry.generatedAt()));
        Map<String, NewsItem> itemsByHeadline = new LinkedHashMap<>();
        Map<String, String> sourceByHeadline = new LinkedHashMap<>();
        List<FeedDigest> feedDigests = new ArrayList<>();
        for (Map.Entry<String, List<String>> source : titlesBySource.entrySet()) {
            List<NewsItem> items = new ArrayList<>();
            for (String title : source.getValue()) {
                NewsItem item = new NewsItem(title, linkByTitle.get(title), null);
                items.add(item);
                if (item.link() != null) {
                    itemsByHeadline.putIfAbsent(title, item);
                    sourceByHeadline.putIfAbsent(title, source.getKey());
                }
            }
            feedDigests.add(new FeedDigest(source.getKey(), items));
        }
        WeeklyDigest digest = new WeeklyDigest(weekId, entry.date(), feedDigests, entry.highlights().size());
        System.out.println("Rebuilt the " + weekId + " brief: " + entry.highlights().size()
                + " headlines, " + itemsByHeadline.size() + " with links.");

        System.out.println("Asking " + slug + " to make " + weekId + "'s piece...");
        ArtDirection direction = writer.write(digest);
        // Same guard as the scheduled run - see Main.
        if (direction.prompt().length() < 40 || direction.rationale().length() < 20) {
            throw new IllegalStateException("response looks truncated (prompt="
                    + direction.prompt().length() + " chars, rationale="
                    + direction.rationale().length() + " chars)");
        }
        System.out.println(direction.writtenBy() + " prompt: " + direction.prompt());
        System.out.println(direction.writtenBy() + " rationale: " + direction.rationale());

        System.out.println("Rendering with " + config.imageModel() + "...");
        byte[] png = new ImageGenerator(config.geminiApiKey(), config.imageModel()).generate(direction.prompt());
        String cacheBuster = "?v=" + Instant.now().toEpochMilli();
        String imageUrl = store.publishImage(weekId, slug, png) + cacheBuster;
        String thumbnailUrl = store.publishThumbnail(weekId, slug, Thumbnail.jpeg(png)) + cacheBuster;
        List<Citation> citations = Main.resolveCitations(
                direction.citations(), direction.rationale(), itemsByHeadline, sourceByHeadline);
        Piece added = new Piece(direction.writtenBy(), direction.model(), direction.prompt(),
                direction.rationale(), citations, imageUrl, thumbnailUrl);

        // Slot it where the scheduled run would have put it, so the week reads in the usual order.
        List<String> order = new ArrayList<>(writers.keySet());
        List<Piece> pieces = new ArrayList<>(entry.pieces());
        int at = pieces.size();
        for (int i = 0; i < pieces.size(); i++) {
            if (order.indexOf(slugOf(entry.version(), pieces.get(i).imageUrl())) > order.indexOf(slug)) {
                at = i;
                break;
            }
        }
        pieces.add(at, added);

        Path backup = Path.of("manifest-backup-" + System.currentTimeMillis() + ".json");
        Files.writeString(backup, GSON.toJson(manifest), StandardCharsets.UTF_8);
        System.out.println("Backed up the current manifest to " + backup.toAbsolutePath());

        entries.set(index, new ManifestEntry(entry.version(), entry.date(), entry.sourcePath(),
                entry.sourceUrl(), pieces, entry.highlights(), entry.generatedAt()));
        store.saveManifest(manifest);
        store.saveFeed(manifest);
        System.out.println("Added " + direction.writtenBy() + " to " + weekId + " ("
                + citations.size() + " citation(s)). Manifest and feed updated.");
    }

    /** Headline title -> link for everything still in the feeds from the original window. */
    private static Map<String, String> currentLinks(Instant generatedAt) throws Exception {
        Map<String, String> links = new LinkedHashMap<>();
        RssFeedFetcher fetcher = new RssFeedFetcher();
        for (FeedRef feed : new OpmlSource().fetchFeeds()) {
            try {
                for (NewsItem item : fetcher.fetchSince(feed, generatedAt.minus(7, ChronoUnit.DAYS))) {
                    links.putIfAbsent(item.title(), item.link());
                }
            } catch (Exception e) {
                System.out.println("  no links from " + feed.title() + ": " + e.getMessage());
            }
        }
        return links;
    }

    /** The slug a piece was published under, from its image filename - see BackfillThumbnails. */
    private static String slugOf(String version, String imageUrl) {
        String path = URI.create(imageUrl).getPath();
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        return stem.startsWith(version + "-") ? stem.substring(version.length() + 1) : stem;
    }
}
