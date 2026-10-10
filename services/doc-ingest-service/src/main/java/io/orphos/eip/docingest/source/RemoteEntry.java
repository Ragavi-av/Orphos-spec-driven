package io.orphos.eip.docingest.source;

import java.time.Instant;

/**
 * One file discovered in a remote source, normalised across connector kinds.
 *
 * @param key          connector-native identifier (e.g. an S3 object key)
 * @param path         human-readable source path, used for provenance/dedupe (e.g. {@code s3://bucket/key})
 * @param fileName     leaf file name, used for the document title and format detection
 * @param sizeBytes    object size in bytes ({@code -1} when unknown)
 * @param lastModified last-modified timestamp — the incremental-sync watermark driver
 */
public record RemoteEntry(String key, String path, String fileName, long sizeBytes, Instant lastModified) {}
