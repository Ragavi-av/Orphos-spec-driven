package io.orphos.eip.docingest.source;

/**
 * Central list of document extensions the ingestion pipeline can parse. Kept in
 * sync with {@code DocIngestionService.isSupportedFormat} so a remote crawl only
 * downloads files the pipeline will actually process.
 */
public final class SupportedFormats {

    private SupportedFormats() {}

    public static boolean isSupported(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase();
        return n.endsWith(".pdf")
            || n.endsWith(".docx") || n.endsWith(".doc")
            || n.endsWith(".pptx") || n.endsWith(".ppt")
            || n.endsWith(".xlsx") || n.endsWith(".xls")
            || n.endsWith(".csv")
            || n.endsWith(".txt")
            || n.endsWith(".htm")  || n.endsWith(".html")
            || n.endsWith(".png")  || n.endsWith(".jpg") || n.endsWith(".jpeg")
            || n.endsWith(".tif")  || n.endsWith(".tiff")
            || n.endsWith(".gif")  || n.endsWith(".bmp") || n.endsWith(".webp") || n.endsWith(".heic")
            // Audio (transcribed via the Whisper sidecar).
            || n.endsWith(".mp3")  || n.endsWith(".wav") || n.endsWith(".m4a")  || n.endsWith(".mp4")
            || n.endsWith(".aac")  || n.endsWith(".ogg") || n.endsWith(".oga")  || n.endsWith(".opus")
            || n.endsWith(".flac") || n.endsWith(".webm")|| n.endsWith(".amr")  || n.endsWith(".3gp");
    }
}
