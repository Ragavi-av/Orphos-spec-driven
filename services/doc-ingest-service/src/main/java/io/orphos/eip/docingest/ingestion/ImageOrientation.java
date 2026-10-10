package io.orphos.eip.docingest.ingestion;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a photo upright before OCR. Phone cameras store the sensor's pixels as-is
 * and record the way the phone was held in the EXIF Orientation tag; viewers honour
 * the tag, but neither Tesseract nor Docling does, so a portrait photo of a page is
 * OCR'd lying on its side and comes back as noise.
 *
 * <p>Best-effort: any image without the tag, already upright, or unreadable is
 * returned unchanged. The original file is never modified — a rotated copy is
 * written next to the system temp files and the caller deletes it.</p>
 */
final class ImageOrientation {

    private static final Logger log = LogManager.getLogger(ImageOrientation.class);

    /** Formats that carry EXIF and that ImageIO can decode. */
    private static final Set<String> EXIF_FORMATS = Set.of("jpg", "jpeg", "tif", "tiff");

    private ImageOrientation() {}

    /**
     * An upright copy of {@code file} when its EXIF orientation says it is rotated
     * or mirrored, otherwise {@code null} (use the file as it is).
     */
    static Path uprightCopy(Path file) {
        String ext = extension(file);
        if (!EXIF_FORMATS.contains(ext)) return null;
        try {
            int orientation = orientation(file);
            if (orientation <= 1 || orientation > 8) return null;
            BufferedImage src = ImageIO.read(file.toFile());
            if (src == null) return null;
            BufferedImage upright = transform(src, orientation);
            // PNG: lossless, so OCR sees exactly the camera's pixels.
            Path out = Files.createTempFile("doc-upright-", ".png");
            ImageIO.write(upright, "png", out.toFile());
            log.debug("Applied EXIF orientation {} to {} before OCR", orientation, file.getFileName());
            return out;
        } catch (Exception | OutOfMemoryError e) {
            log.warn("Could not normalise orientation of {} ({}); OCR uses it as stored",
                    file.getFileName(), e.getMessage());
            return null;
        }
    }

    private static int orientation(Path file) throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
        ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (dir == null || !dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) return 1;
        return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
    }

    /** Applies EXIF orientation 2–8 (mirror and/or 90/180/270° rotation). */
    static BufferedImage transform(BufferedImage src, int orientation) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { return src; }
        }
        int type = src.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : src.getType();
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, type);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1) : "";
    }
}
