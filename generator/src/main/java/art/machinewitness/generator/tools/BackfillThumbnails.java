package art.machinewitness.generator.tools;

import art.machinewitness.generator.GalleryStore;
import art.machinewitness.generator.GcsGalleryStore;
import art.machinewitness.generator.Manifest;
import art.machinewitness.generator.ManifestEntry;
import art.machinewitness.generator.Piece;
import art.machinewitness.generator.Thumbnail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off maintenance tool: gives already-published pieces the grid-sized thumbnail that only
 * pieces generated after the thumbnail change get automatically.
 *
 * <p>Without it the archive stays on the old path forever - the site falls back to the full PNG
 * when a piece has no thumbnailUrl, so browsing back through the gallery pulls a couple of
 * megabytes per tile for every week published before the switch.
 *
 * <p>Idempotent: a piece that already has a thumbnailUrl is skipped, so a re-run after a partial
 * failure only does what's left. A piece whose image can't be fetched or encoded is left exactly
 * as it was and reported at the end, rather than failing the whole pass.
 *
 * <p>It rewrites the live manifest, so it writes a local backup copy first and prints the path.
 * Run it with --dry-run first to see what it would touch.
 *
 * Usage:
 *   GCS_BUCKET=... GOOGLE_ACCESS_TOKEN=$(gcloud auth print-access-token) \
 *     java -cp machinewitness-generator-1.0.0.jar \
 *     art.machinewitness.generator.tools.BackfillThumbnails [--dry-run]
 */
public final class BackfillThumbnails {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BackfillThumbnails() {
    }

    public static void main(String[] args) throws Exception {
        boolean dryRun = List.of(args).contains("--dry-run");

        String bucket = System.getenv("GCS_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("GCS_BUCKET is required.");
        }

        GalleryStore store = new GcsGalleryStore(bucket);
        Manifest manifest = store.loadManifest();
        List<ManifestEntry> entries = manifest.entries();
        if (entries.isEmpty()) {
            System.out.println("Manifest is empty - nothing to backfill.");
            return;
        }

        Path backup = Path.of("manifest-backup-" + System.currentTimeMillis() + ".json");
        Files.writeString(backup, GSON.toJson(manifest), StandardCharsets.UTF_8);
        System.out.println("Backed up the current manifest to " + backup.toAbsolutePath());
        System.out.println((dryRun ? "DRY RUN: " : "") + "scanning " + entries.size() + " week(s)...\n");

        int done = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            ManifestEntry entry = entries.get(i);
            List<Piece> updated = new ArrayList<>(entry.pieces().size());
            boolean entryChanged = false;

            for (Piece piece : entry.pieces()) {
                if (piece.thumbnailUrl() != null && !piece.thumbnailUrl().isBlank()) {
                    updated.add(piece);
                    skipped++;
                    continue;
                }
                String label = entry.version() + " " + piece.artist();
                try {
                    String slug = artistSlug(entry.version(), piece.imageUrl());
                    if (dryRun) {
                        System.out.println("  would thumbnail " + label + " -> thumbs/"
                                + entry.version() + "-" + slug + ".jpg");
                        updated.add(piece);
                        done++;
                        continue;
                    }
                    byte[] png = download(piece.imageUrl());
                    byte[] jpeg = Thumbnail.jpeg(png);
                    // Reuses the full image's cache-buster so the pair stays in step; the object
                    // itself is new, so any value would do.
                    String thumbnailUrl = store.publishThumbnail(entry.version(), slug, jpeg)
                            + cacheBuster(piece.imageUrl());
                    updated.add(new Piece(piece.artist(), piece.model(), piece.prompt(),
                            piece.rationale(), piece.citations(), piece.imageUrl(), thumbnailUrl));
                    entryChanged = true;
                    done++;
                    System.out.printf("  %-26s %,8d B -> %,7d B%n", label, png.length, jpeg.length);
                } catch (Exception e) {
                    // One unreadable image shouldn't cost the rest of the archive its thumbnails.
                    failures.add(label + ": " + e.getMessage());
                    updated.add(piece);
                }
            }

            if (entryChanged) {
                entries.set(i, new ManifestEntry(entry.version(), entry.date(), entry.sourcePath(),
                        entry.sourceUrl(), updated, entry.highlights(), entry.generatedAt()));
            }
        }

        System.out.println();
        if (dryRun) {
            System.out.println("DRY RUN - nothing uploaded. " + done + " piece(s) would be thumbnailed, "
                    + skipped + " already had one.");
            return;
        }

        if (done > 0) {
            store.saveManifest(manifest);
            // The feed embeds no thumbnails, but it's rebuilt from the manifest - keeping them
            // written together means the two can't drift.
            store.saveFeed(manifest);
            System.out.println("Manifest and feed updated.");
        } else {
            System.out.println("Nothing to do - manifest left untouched.");
        }
        System.out.println("Thumbnailed " + done + ", already had one " + skipped
                + ", failed " + failures.size() + ".");
        failures.forEach(f -> System.out.println("  FAILED " + f));
    }

    /**
     * Takes the slug from the published image's own filename rather than re-deriving it from the
     * artist name: the object the thumbnail sits beside is the ground truth, and an old entry
     * whose artist string no longer matches its filename would otherwise get a thumbnail nothing
     * ever reads.
     */
    private static String artistSlug(String version, String imageUrl) {
        String path = URI.create(imageUrl).getPath();
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        String prefix = version + "-";
        if (!stem.startsWith(prefix)) {
            throw new IllegalStateException("image name '" + stem + "' doesn't start with '" + prefix + "'");
        }
        return stem.substring(prefix.length());
    }

    private static String cacheBuster(String imageUrl) {
        int q = imageUrl.indexOf('?');
        return q < 0 ? "" : imageUrl.substring(q);
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        }
        return response.body();
    }
}
