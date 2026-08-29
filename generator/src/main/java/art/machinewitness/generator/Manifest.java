package art.machinewitness.generator;

import java.util.ArrayList;
import java.util.List;

/** The full gallery manifest: newest artwork first. */
public final class Manifest {

    private List<ManifestEntry> entries = new ArrayList<>();

    public List<ManifestEntry> entries() {
        return entries;
    }

    public boolean hasSource(String sourcePath) {
        return entries.stream().anyMatch(e -> e.sourcePath().equals(sourcePath));
    }

    public void prepend(ManifestEntry entry) {
        entries.addFirst(entry);
    }
}
