package art.machinewitness.generator;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fetches one RSS/Atom feed (via Rome) and keeps only the items published on/after a cutoff. */
public final class RssFeedFetcher {

    private final HttpClient http = HttpClient.newHttpClient();

    public List<NewsItem> fetchSince(FeedRef feed, Instant cutoff) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(feed.xmlUrl())).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        SyndFeed syndFeed;
        try {
            syndFeed = new SyndFeedInput().build(new StringReader(response.body()));
        } catch (Exception e) {
            throw new IOException("Failed to parse feed " + feed.xmlUrl() + ": " + e.getMessage(), e);
        }

        List<NewsItem> recent = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SyndEntry entry : syndFeed.getEntries()) {
            String title = entry.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            Date publishedDate = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
            if (publishedDate == null) {
                continue;
            }
            Instant published = publishedDate.toInstant();
            if (published.isBefore(cutoff)) {
                continue;
            }
            String link = entry.getLink() == null ? "" : entry.getLink();
            String dedupeKey = link.isBlank() ? title.strip() : link.strip();
            if (seen.add(dedupeKey)) {
                recent.add(new NewsItem(title.strip(), link.strip(), published));
            }
        }
        return recent;
    }
}
