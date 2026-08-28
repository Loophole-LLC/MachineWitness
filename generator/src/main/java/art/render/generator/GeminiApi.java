package art.render.generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Shared plain-REST plumbing for calling the Gemini API - no SDK dependency. */
final class GeminiApi {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;

    GeminiApi(String apiKey) {
        this.apiKey = apiKey;
    }

    JsonObject generateContent(String model, JsonObject requestBody) throws IOException, InterruptedException {
        String url = BASE_URL + model + ":generateContent?key=" + apiKey;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Gemini API error for model " + model + ": HTTP " + response.statusCode() + " - " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
