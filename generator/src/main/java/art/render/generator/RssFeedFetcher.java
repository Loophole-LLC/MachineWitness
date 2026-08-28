package art.render.generator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fetches one RSS 2.0 feed and keeps only the items published on/after a cutoff instant. */
public final class RssFeedFetcher {

    private final HttpClient http = HttpClient.newHttpClient();

    public List<NewsItem> fetchSince(FeedRef feed, Instant cutoff) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(feed.xmlUrl())).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        Document doc = SafeXml.parse(response.body());
        NodeList items = doc.getElementsByTagName("item");
        List<NewsItem> recent = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = firstChildText(item, "title");
            String link = firstChildText(item, "link");
            String pubDateRaw = firstChildText(item, "pubDate");
            if (title.isBlank() || pubDateRaw.isBlank()) {
                continue;
            }
            Instant published = parseDate(pubDateRaw);
            if (published == null || published.isBefore(cutoff)) {
                continue;
            }
            String dedupeKey = link.isBlank() ? title.strip() : link.strip();
            if (seen.add(dedupeKey)) {
                recent.add(new NewsItem(title.strip(), link.strip(), published));
            }
        }
        return recent;
    }

    private static String firstChildText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static Instant parseDate(String raw) {
        try {
            return ZonedDateTime.parse(raw.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
