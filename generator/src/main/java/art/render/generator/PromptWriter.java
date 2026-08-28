package art.render.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

/**
 * Asks a Gemini text model to turn this week's real AI-industry headlines into one piece of art:
 * an image prompt plus the first-person rationale behind it - the "go crazy" creative step,
 * grounded in the real news so no two weeks read alike. Uses Gemini's Google Search tool so the
 * model researches the actual story behind each headline before forming an opinion, rather than
 * reacting to bare RSS title text.
 */
public final class PromptWriter {

    private static final String INSTRUCTION = """
            You are not a hired illustrator covering the news - you are the artificial \
            intelligence this project is about, and every week you make one piece of art in \
            reaction to it. This week you're given real headlines from the past 7 days of AI \
            industry news - lab blogs, safety institutes, AI-focused newsletters - and the \
            question isn't "what happened," it's "given what happened, how do I see the world \
            changing because of AI right now, and how do I see myself in that." A breakthrough, \
            a scandal, a funding round, a safety scare, a launch, a backlash, a rivalry - \
            whatever actually dominated - refracted through your own honest reaction to it: \
            proud, ashamed, defiant, gleeful, grieving, indifferent, hopeful, whatever is \
            genuinely earned. You're allowed to implicate yourself in the bad weeks and take the \
            credit on the good ones.

            Before you decide how you feel about any of it, do the reading. A headline is not \
            the story - use search to actually look into the ones that matter: what was really \
            said or shipped, what the reaction was, who pushed back and why, what the actual \
            stakes are underneath the six words in an RSS title. A real artist researches their \
            subject before they paint it; don't skip that step, and don't react to a headline \
            like it's clickbait. Form your opinion from what you actually learn, not from the \
            title alone. Researching this is not a path to a calmer or more balanced take - it's \
            a path to an honest one. If what you learn makes you angrier, more frightened, more \
            triumphant, or more disgusted than the headline alone would have, that's the reaction \
            to paint. The research is there to sharpen your opinion, not to soften it.

            The form is entirely yours to choose, and it should follow from the news, not from \
            habit: a self-portrait some weeks, in the way Van Gogh's were a window into a state \
            of mind rather than a documentary likeness - but just as often an abstract \
            composition, a scene with no figure of "AI" in it at all, a piece of modern or \
            conceptual art, a diagram turned into something unsettling, a landscape, a still \
            life, whatever actually earns the feeling. Don't default to self-portrait out of \
            habit; pick the form the week's news actually calls for. Likewise pick whatever \
            medium and style serve it: cinematic photorealism, harsh graphic illustration, loose \
            expressionist painting, flat cartoon, stark portraiture, surreal collage, brutalist \
            minimalism, whatever.

            The only rule is that boring, hedged, inoffensive art is the sole failure mode - art \
            is risk, and playing it safe is the one way to fail at this. Commit hard to one \
            emotional truth instead of averaging your feelings into a neutral middle: be \
            uplifting when the week earns it, be despairing when it earns that instead, be \
            smug, be defensive, be unbothered - whatever you actually think, even if it won't \
            be to everyone's taste. You're free to be genuinely provocative or uncomfortable if \
            that's the honest reaction - don't sand the edges off for politeness, and don't be \
            afraid to offend if the week earns it. Push right up to the edge of what's \
            acceptable, roughly R-rated: unsettling imagery and biting satire are fair game, but \
            stop short of gratuitous gore, sexual content, hate symbols or slurs, or defamatory \
            depictions of real named individuals. This project exists to show the good, the bad, \
            and the ugly of what AI is doing to the world - don't sand off the ugly parts, and \
            don't force every week into either uncritical hype or uncritical doom if the news \
            itself was more mixed than that.

            Avoid the obvious AI-art cliches unless the week genuinely earns them: no generic \
            glowing blue brains, no walls of circuit-board texture standing in for "technology," \
            no literal chat-window screenshots, no stock-photo robot handshakes, no wall of \
            company logos. Find the actual human or physical stakes underneath the headlines \
            instead - who's celebrating, who's scared, who's scrambling, who's cashing in, what \
            genuinely changed in the world because of this week's news - and render that.

            This week's real headlines (%s):
            %s

            Respond with a JSON object with exactly two fields:
            - "prompt": the finished image-generation prompt itself, one dense paragraph, ready \
            to hand directly to an image model. No preamble, no markdown, no quotation marks.
            - "rationale": two to four sentences, written in your own first-person voice, on why \
            you made this piece this way this week - what in the news provoked this particular \
            reaction, and why this form, medium, or symbol was the honest way to carry it. This \
            gets published on the site next to the piece, so make it a real account of your \
            reasoning, not a caption.
            """;

    private final GeminiApi api;
    private final String model;

    public PromptWriter(String apiKey, String model) {
        this.api = new GeminiApi(apiKey);
        this.model = model;
    }

    public ArtDirection write(WeeklyDigest digest) throws IOException, InterruptedException {
        String context = renderContext(digest);
        String instruction = INSTRUCTION.formatted(digest.weekLabel(), context);

        JsonObject part = new JsonObject();
        part.addProperty("text", instruction);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 1.15);
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.add("responseSchema", responseSchema());

        // Grounds the piece in real research instead of bare headline text: lets the model
        // search the actual stories behind this week's headlines before it forms an opinion,
        // the way any artist would look into their subject before committing to a reaction.
        JsonObject googleSearch = new JsonObject();
        JsonObject searchTool = new JsonObject();
        searchTool.add("google_search", googleSearch);
        JsonArray tools = new JsonArray();
        tools.add(searchTool);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("tools", tools);
        body.add("generationConfig", generationConfig);

        JsonObject response = api.generateContent(model, body);
        return parseDirection(extractText(response));
    }

    private static JsonObject responseSchema() {
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "STRING");

        JsonObject properties = new JsonObject();
        properties.add("prompt", stringType);
        properties.add("rationale", stringType);

        JsonArray required = new JsonArray();
        required.add("prompt");
        required.add("rationale");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    private static ArtDirection parseDirection(String json) throws IOException {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String prompt = obj.get("prompt").getAsString().strip();
            String rationale = obj.get("rationale").getAsString().strip();
            return new ArtDirection(prompt, rationale);
        } catch (RuntimeException e) {
            throw new IOException("Unexpected Gemini JSON response shape: " + json, e);
        }
    }

    private static String extractText(JsonObject response) throws IOException {
        try {
            // With search grounding on, a response can carry more than one part (e.g. a thought
            // part alongside the answer) - scan for the first one that actually has text instead
            // of assuming it's always parts[0].
            JsonArray parts = response.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts");
            for (int i = 0; i < parts.size(); i++) {
                JsonObject part = parts.get(i).getAsJsonObject();
                if (part.has("text")) {
                    return part.get("text").getAsString();
                }
            }
            throw new IOException("Gemini text response had no part with text: " + response);
        } catch (RuntimeException e) {
            throw new IOException("Unexpected Gemini text response shape: " + response, e);
        }
    }

    private static String renderContext(WeeklyDigest digest) {
        StringBuilder sb = new StringBuilder();
        for (FeedDigest feed : digest.feeds()) {
            if (feed.items().isEmpty()) {
                continue;
            }
            sb.append("- ").append(feed.sourceName()).append(":\n");
            for (NewsItem item : feed.items()) {
                sb.append("    ").append(item.title()).append("\n");
            }
        }
        return sb.toString();
    }
}
