package com.orphos.mobile.data.model;

import java.util.List;

/**
 * A Scribbles capture. The content ingests via doc-ingest (OCR + RAG) and the capture
 * index/threads live on the Mobile BFF; demo mode keeps them on the device instead.
 */
public class Capture {

    // Capture types the ingest pipeline handles.
    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_HANDWRITING = "HANDWRITING";
    public static final String TYPE_IMAGE = "IMAGE";     // uploaded image / whiteboard
    public static final String TYPE_PHOTO = "PHOTO";     // camera photo
    public static final String TYPE_AUDIO = "AUDIO";     // voice recording
    public static final String TYPE_VOICE = "VOICE";     // legacy alias for AUDIO

    public String id;
    public String title;
    public String type;        // TEXT | HANDWRITING | IMAGE | PHOTO | AUDIO
    public String createdAt;   // human label for demo ("10:30 AM", "Yesterday")
    public long createdAtMillis; // BFF epoch millis; 0 when only a label is known (demo, legacy rows)
    public String content;
    /** Voice note: the speech-to-text exactly as returned, before the user's edits to {@link #content}. */
    public String transcript;
    public String summary;
    public List<String> keyPoints;
    /** AI-found tasks and pending decisions; null when the AI never classified the note. */
    public List<String> actions;
    public List<String> decisions;
    public String mimeType;      // doc-ingest content type (e.g. image/jpeg, audio/mp4)
    public String ingestChannel; // NOTE | OCR_IMAGE | TRANSCRIBE_AUDIO
    public boolean hasFile;      // the original bytes (e.g. handwriting image) are stored
    public boolean ingested;     // forwarded to doc-ingest, i.e. searchable on the web
    public String localFilePath; // demo/offline only: the original, kept on the device
    /** Review draft only: the server's OCR read noise (typically handwriting), so it wrote no summary. */
    public transient boolean lowConfidence;

    public Capture() { }

    public Capture(String id, String title, String type, String createdAt) {
        this.id = id; this.title = title; this.type = type; this.createdAt = createdAt;
    }
}
