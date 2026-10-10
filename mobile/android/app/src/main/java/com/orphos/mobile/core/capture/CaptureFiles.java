package com.orphos.mobile.core.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves capture sources (a rendered bitmap or a content:// uri) to a real file in the
 * app cache, so the underlying bytes can be uploaded to the ingestion pipeline
 * (OrphosRepository.uploadCapture). Returns null when the source can't be materialised,
 * in which case callers fall back to the metadata-only capture path.
 */
public final class CaptureFiles {

    private CaptureFiles() { }

    /** Writes a bitmap to a PNG file in the cache dir. Returns null on failure. */
    public static File fromBitmap(Context context, Bitmap bitmap, String prefix) {
        if (bitmap == null) return null;
        File out = cacheFile(context, prefix == null ? "capture" : prefix, ".png");
        try (OutputStream os = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Copies a content:// (or file://) uri's bytes into a cache file. Returns null on failure. */
    public static File fromUri(Context context, Uri uri, String prefix) {
        if (uri == null) return null;
        File out = cacheFile(context, prefix == null ? "capture" : prefix, extensionFor(context, uri));
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * An empty cache file for the camera app to write a full-resolution photo into, and the
     * content:// uri it is shared under (FileProvider authority {@code <applicationId>.captures}).
     * Returns null when the file can't be created.
     */
    public static File newPhotoFile(Context context) {
        File out = cacheFile(context, "camera", ".jpg");
        try {
            return out.createNewFile() || out.exists() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static Uri shareableUri(Context context, File file) {
        return androidx.core.content.FileProvider.getUriForFile(context,
                context.getPackageName() + ".captures", file);
    }

    /**
     * Decodes an image for display, downsampled so its longer side is at most {@code maxDim}
     * px: a full-resolution camera photo decoded as-is can exceed the app's memory. The file
     * itself is untouched. Returns null when it is not a decodable image.
     *
     * <p>The camera stores photos sideways with an EXIF rotation flag that BitmapFactory
     * ignores, so the flag is applied here — otherwise a portrait page shows lying down.</p>
     */
    public static Bitmap decodeForDisplay(String path, int maxDim) {
        if (path == null) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, maxDim);
        o.inJustDecodeBounds = false;
        Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path, o);
        try (InputStream in = new FileInputStream(path)) {
            return upright(bmp, in);
        } catch (Exception e) {
            return bmp;
        }
    }

    /** As {@link #decodeForDisplay(String, int)}, for bytes already in memory (a downloaded original). */
    public static Bitmap decodeForDisplay(byte[] bytes, int maxDim) {
        if (bytes == null || bytes.length == 0) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, maxDim);
        o.inJustDecodeBounds = false;
        Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            return upright(bmp, in);
        } catch (Exception e) {
            return bmp;
        }
    }

    /** Applies the image's EXIF orientation to {@code bmp}; returns it unchanged when upright or untagged. */
    private static Bitmap upright(Bitmap bmp, InputStream exifSource) throws java.io.IOException {
        if (bmp == null) return null;
        int orientation = new android.media.ExifInterface(exifSource).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
        android.graphics.Matrix m = new android.graphics.Matrix();
        switch (orientation) {
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180:      m.setRotate(180); break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:   m.setScale(1, -1); break;
            case android.media.ExifInterface.ORIENTATION_TRANSPOSE:       m.setRotate(90); m.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_90:       m.setRotate(90); break;
            case android.media.ExifInterface.ORIENTATION_TRANSVERSE:      m.setRotate(-90); m.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270:      m.setRotate(-90); break;
            default: return bmp;
        }
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != bmp) bmp.recycle();
        return rotated;
    }

    private static int sampleSize(int w, int h, int maxDim) {
        int sample = 1;
        while (w > 0 && h > 0 && Math.max(w, h) / sample > maxDim) sample *= 2;
        return sample;
    }

    /**
     * Writes handwriting strokes ({x, y, t, …} per stroke) to a cache file. Passed between
     * screens by path: a paragraph of ink easily exceeds the Binder limit on Intent extras.
     * Returns null on failure.
     */
    public static File writeInk(Context context, List<float[]> strokes) {
        File out = cacheFile(context, "ink", ".bin");
        try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            os.writeInt(strokes.size());
            for (float[] s : strokes) {
                os.writeInt(s.length);
                for (float v : s) os.writeFloat(v);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads strokes written by {@link #writeInk}; empty when the file is missing or unreadable. */
    public static List<float[]> readInk(String path) {
        List<float[]> strokes = new ArrayList<>();
        if (path == null) return strokes;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                float[] s = new float[in.readInt()];
                for (int j = 0; j < s.length; j++) s[j] = in.readFloat();
                strokes.add(s);
            }
        } catch (Exception e) {
            strokes.clear();
        }
        return strokes;
    }

    private static File cacheFile(Context context, String prefix, String ext) {
        File dir = new File(context.getCacheDir(), "captures");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, prefix + "-" + System.currentTimeMillis() + ext);
    }

    private static String extensionFor(Context context, Uri uri) {
        try {
            String type = context.getContentResolver().getType(uri);
            if (type != null) {
                String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
                if (ext != null && !ext.isEmpty()) return "." + ext;
            }
        } catch (Exception ignored) { }
        return ".bin";
    }
}
