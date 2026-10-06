package art.machinewitness.generator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Shrinks a rendered piece into the version the gallery grid actually displays.
 *
 * <p>The image model returns a ~1400px PNG weighing roughly 2MB, and the grid shows it in a tile
 * about 320px wide. At three models that was a tolerable waste; at six it put ~13MB above the
 * fold, which is slow on mobile and counts against the site's Core Web Vitals. The full PNG is
 * still published and still what the dialog, the RSS enclosure and a direct link serve - this is
 * only what the grid loads.
 *
 * <p>JPEG rather than WebP: WebP needs a native encoder that isn't in the JDK, and adding one to
 * the Cloud Run image is a lot of moving parts to save a few more kilobytes on an image this
 * size. Revisit if the grid ever becomes the bottleneck again.
 */
public final class Thumbnail {

    /** Wide enough to stay sharp in a ~320px tile on a 2x display without paying for the full render. */
    private static final int TARGET_WIDTH = 800;

    private static final float JPEG_QUALITY = 0.82f;

    private Thumbnail() {
    }

    public static byte[] jpeg(byte[] pngBytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (source == null) {
            throw new IOException("Could not decode rendered image for thumbnailing");
        }
        BufferedImage scaled = scaleToWidth(source, TARGET_WIDTH);
        return encodeJpeg(scaled);
    }

    private static BufferedImage scaleToWidth(BufferedImage source, int targetWidth) {
        if (source.getWidth() <= targetWidth) {
            // Already small enough - still re-encode, so an unexpectedly tiny render doesn't get
            // upscaled but does get the same JPEG treatment as every other thumbnail.
            targetWidth = source.getWidth();
        }
        int targetHeight = Math.max(1, Math.round(
                source.getHeight() * (targetWidth / (float) source.getWidth())));

        // TYPE_INT_RGB because JPEG has no alpha channel: drawing onto an RGB surface flattens
        // any transparency against black rather than writing a file decoders disagree about.
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        var g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
