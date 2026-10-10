package io.orphos.eip.docingest.source;

/**
 * Kinds of folder/storage backend a tenant can attach as a reusable "folder
 * connection" — usable both as a pull-based ingestion source AND as a document
 * storage location (see {@link SourceConnector.Capability}).
 *
 * <p>Implemented today: {@link #LOCAL}, {@link #S3} (also any S3-compatible store
 * such as MinIO/Ceph via an endpoint override) and {@link #FTP}. {@link #SFTP},
 * {@link #GDRIVE}, {@link #SHAREPOINT}, {@link #CONFLUENCE} and {@link #TEAMS} are
 * declared so connection rows and the admin UI round-trip them, but their
 * {@code SourceConnector} beans land in later phases — the registry reports them
 * as unsupported until then (SFTP/GDrive need their client libs installed;
 * SharePoint/Teams use app-only Microsoft Graph credentials).</p>
 */
public enum SourceKind {
    LOCAL,
    S3,
    FTP,
    SFTP,
    GDRIVE,
    SHAREPOINT,
    CONFLUENCE,
    TEAMS
}
