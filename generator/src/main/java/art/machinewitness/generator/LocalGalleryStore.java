package art.machinewitness.generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Dev/test storage: writes to a local directory so a full run needs only GEMINI_API_KEY. */
public final class LocalGalleryStore implements GalleryStore {

    private final Path outDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public LocalGalleryStore(String outDir) throws IOException {
        this.outDir = Path.of(outDir);
        Files.createDirectories(this.outDir.resolve("images"));
    }

    @Override
    public Manifest loadManifest() throws IOException {
        Path manifestPath = outDir.resolve("manifest.json");
        if (!Files.exists(manifestPath)) {
            return new Manifest();
        }
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        return gson.fromJson(json, Manifest.class);
    }

    @Override
    public void saveManifest(Manifest manifest) throws IOException {
        Files.writeString(outDir.resolve("manifest.json"), gson.toJson(manifest), StandardCharsets.UTF_8);
    }

    @Override
    public void saveFeed(Manifest manifest) throws IOException {
        Files.writeString(outDir.resolve("feed.xml"), RssFeed.build(manifest), StandardCharsets.UTF_8);
    }

    @Override
    public String publishImage(String version, String artistSlug, byte[] pngBytes) throws IOException {
        String relativePath = "images/" + version + "-" + artistSlug + ".png";
        Files.write(outDir.resolve(relativePath), pngBytes);
        return relativePath;
    }
}
