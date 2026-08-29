package art.render.generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Production gallery storage: a public-read GCS bucket. Reads are anonymous HTTPS GETs (the
 * bucket must have allUsers granted objectViewer, see README); writes use an access token from
 * the Cloud Run Job's attached service account via GcpAuth - no GCS client library dependency.
 */
public final class GcsGalleryStore implements GalleryStore {

    private final String bucket;
    private final HttpClient http = HttpClient.newHttpClient();
    private final GcpAuth auth = new GcpAuth();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public GcsGalleryStore(String bucket) {
        this.bucket = bucket;
    }

    @Override
    public Manifest loadManifest() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(publicUrl("manifest.json")))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return new Manifest();
        }
        if (response.statusCode() != 200) {
            throw new IOException("Failed to load manifest.json: HTTP " + response.statusCode());
        }
        return gson.fromJson(response.body(), Manifest.class);
    }

    @Override
    public void saveManifest(Manifest manifest) throws IOException, InterruptedException {
        // Short-lived cache: manifest.json changes with every new piece, and GCS's public-object
        // default (Cache-Control: public, max-age=3600) would otherwise let Google's edge serve a
        // stale manifest for up to an hour after each publish - the site.js fetch also cache-busts
        // with a query param, but this keeps direct/GCS-side requests fresh too.
        upload("manifest.json", "application/json; charset=utf-8", "no-cache, max-age=60",
                gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String publishImage(String version, String artistSlug, byte[] pngBytes) throws IOException, InterruptedException {
        String name = "images/" + version + "-" + artistSlug + ".png";
        // Main.java appends a ?v=<generatedAt> cache-buster to every imageUrl it publishes, so
        // the URL a viewer loads is unique per generation even though the underlying object name
        // is reused for a given week - safe to cache this as long as GCS/browsers will hold it.
        upload(name, "image/png", "public, max-age=31536000, immutable", pngBytes);
        return publicUrl(name);
    }

    private void upload(String objectName, String contentType, String cacheControl, byte[] body)
            throws IOException, InterruptedException {
        String uploadUrl = "https://storage.googleapis.com/upload/storage/v1/b/" + bucket
                + "/o?uploadType=media&name=" + encode(objectName);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Authorization", "Bearer " + auth.accessToken())
                .header("Content-Type", contentType);
        if (cacheControl != null) {
            builder.header("Cache-Control", cacheControl);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Failed to upload " + objectName + ": HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    private String publicUrl(String objectName) {
        return "https://storage.googleapis.com/" + bucket + "/" + objectName;
    }

    private static String encode(String objectName) {
        return URLEncoder.encode(objectName, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }
}
