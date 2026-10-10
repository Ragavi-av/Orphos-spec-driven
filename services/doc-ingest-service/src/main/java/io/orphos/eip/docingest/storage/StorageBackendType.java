package io.orphos.eip.docingest.storage;

/** Where a stored original lives. CONNECTION = a remote folder connection (S3/FTP/…) from the shared pool. */
public enum StorageBackendType { NONE, LOCAL, CONNECTION }
