package art.machinewitness.generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Fetches an access token for the Cloud Run Job's attached service account, no key file needed. */
final class GcpAuth {

    private static final String METADATA_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";

    private final HttpClient http = HttpClient.newHttpClient();

    String accessToken() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(METADATA_URL))
                .header("Metadata-Flavor", "Google")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch GCP access token from metadata server: HTTP " + response.statusCode());
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        return body.get("access_token").getAsString();
    }
}
