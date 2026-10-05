package art.machinewitness.generator;

import java.util.List;

/**
 * One model's take on the week: which model made it (provider name plus the exact model
 * version), its prompt, rationale, headline citations, and rendered image.
 *
 * <p>{@code imageUrl} is the full-size PNG - what the dialog, a direct link and the RSS
 * enclosure serve. {@code thumbnailUrl} is the grid-sized JPEG. Entries published before
 * thumbnails existed have no {@code thumbnailUrl}, so the site falls back to {@code imageUrl}.
 */
public record Piece(String artist, String model, String prompt, String rationale,
        List<Citation> citations, String imageUrl, String thumbnailUrl) {
}
