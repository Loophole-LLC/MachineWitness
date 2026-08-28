package art.render.generator;

import java.io.IOException;

/** Where generated artwork and the gallery manifest are persisted. */
public interface GalleryStore {

    Manifest loadManifest() throws IOException, InterruptedException;

    void saveManifest(Manifest manifest) throws IOException, InterruptedException;

    /** Uploads the PNG for this version and returns its public URL. */
    String publishImage(String version, byte[] pngBytes) throws IOException, InterruptedException;
}
