package art.machinewitness.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Renders a prompt with Gemini's image model and returns raw PNG bytes. */
public final class ImageGenerator {

    private final GeminiApi api;
    private final String model;

    public ImageGenerator(String apiKey, String model) {
        this.api = new GeminiApi(apiKey);
        this.model = model;
    }

    public byte[] generate(String prompt) throws IOException, InterruptedException {
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonArray responseModalities = new JsonArray();
        responseModalities.add("IMAGE");
        JsonObject generationConfig = new JsonObject();
        generationConfig.add("responseModalities", responseModalities);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        JsonObject response = api.generateContent(model, body);
        return toPng(extractImageBytes(response));
    }

    private static byte[] extractImageBytes(JsonObject response) throws IOException {
        try {
            JsonArray parts = response.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts");
            for (int i = 0; i < parts.size(); i++) {
                JsonObject part = parts.get(i).getAsJsonObject();
                if (part.has("inlineData")) {
                    String base64Data = part.getAsJsonObject("inlineData").get("data").getAsString();
                    return Base64.getDecoder().decode(base64Data);
                }
            }
            throw new IOException("Gemini image response had no inlineData part: " + response);
        } catch (RuntimeException e) {
            throw new IOException("Unexpected Gemini image response shape: " + response, e);
        }
    }

    // The API's declared mimeType (currently image/jpeg, despite what the model name suggests) is
    // not the format the rest of the pipeline is contracted to - GcsGalleryStore/LocalGalleryStore
    // name every object *.png and upload it as image/png. Re-encode here so those bytes are always
    // genuinely PNG, regardless of what Gemini actually returns.
    private static byte[] toPng(byte[] imageBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IOException("Gemini image response bytes could not be decoded as an image");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
