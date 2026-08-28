package art.render.generator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the Turing Institute's curated list of AI RSS feeds - one flat OPML file naming every
 * feed this project reads for the week's news, see README.
 */
public final class OpmlSource {

    private static final String OPML_URL =
            "https://raw.githubusercontent.com/alan-turing-institute/ai-rss-feeds/refs/heads/main/feeds.opml";

    private final HttpClient http = HttpClient.newHttpClient();

    public List<FeedRef> fetchFeeds() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(OPML_URL)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch OPML feed list: HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    static List<FeedRef> parse(String xml) throws IOException {
        Document doc = SafeXml.parse(xml);
        NodeList outlines = doc.getElementsByTagName("outline");
        List<FeedRef> feeds = new ArrayList<>();
        for (int i = 0; i < outlines.getLength(); i++) {
            Element el = (Element) outlines.item(i);
            String xmlUrl = el.getAttribute("xmlUrl");
            if (xmlUrl.isBlank()) {
                continue; // a folder-only outline with no feed of its own
            }
            String title = el.hasAttribute("title") ? el.getAttribute("title") : el.getAttribute("text");
            feeds.add(new FeedRef(title.isBlank() ? xmlUrl : title, xmlUrl));
        }
        return feeds;
    }
}
