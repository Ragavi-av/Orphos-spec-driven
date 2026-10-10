package io.orphos.eip.docingest.ingestion;

import java.time.LocalDate;

/**
 * Metadata carried through the ingestion pipeline and stored as Qdrant payload.
 * tenantId replaces Ragas's departmentCode, wiring into Orphos multi-tenancy.
 */
public class DocumentMetadata {

    private String documentId;
    private String tenantId;
    private String documentType;
    private String referenceNumber;
    private String title;
    private String fileName;
    private String uploadedBy;
    private String language;
    private String category;
    private String department;
    private String folder;
    private int pageNumber;
    private LocalDate effectiveDate;
    // How this item entered the platform and what kind of raw input it was — the
    // new capture classification. inputType: DOCUMENT | IMAGE | HANDWRITING | AUDIO
    // (default DOCUMENT). sourceChannel: WEB | MOBILE | CONNECTOR (default WEB).
    private String inputType;
    private String sourceChannel;
    // Keycloak sub of the ONE user this document belongs to (a mobile capture), or
    // null for the shared tenant corpus. Search shows an owned document only to its
    // owner; Copilot retrieval skips owned documents entirely.
    private String ownerSub;

    private DocumentMetadata() {}

    public static Builder builder() { return new Builder(); }

    public String getDocumentId()    { return documentId; }
    public String getTenantId()      { return tenantId; }
    public String getDocumentType()  { return documentType; }
    public String getReferenceNumber() { return referenceNumber; }
    public String getTitle()         { return title; }
    public String getFileName()      { return fileName; }
    public String getUploadedBy()    { return uploadedBy; }
    public String getLanguage()      { return language; }
    public String getCategory()      { return category; }
    public String getDepartment()    { return department; }
    public String getFolder()        { return folder; }
    public int    getPageNumber()    { return pageNumber; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public String getInputType()     { return inputType == null || inputType.isBlank() ? "DOCUMENT" : inputType; }
    public String getSourceChannel() { return sourceChannel == null || sourceChannel.isBlank() ? "WEB" : sourceChannel; }
    public String getOwnerSub()      { return ownerSub == null || ownerSub.isBlank() ? null : ownerSub; }

    public void setTitle(String title) { this.title = title; }
    public void setReferenceNumber(String ref) { this.referenceNumber = ref; }
    public void setDocumentType(String type) { this.documentType = type; }
    public void setLanguage(String lang) { this.language = lang; }
    public void setCategory(String v) { this.category = v; }
    public void setDepartment(String v) { this.department = v; }
    public void setFolder(String v) { this.folder = v; }

    public static final class Builder {
        private final DocumentMetadata m = new DocumentMetadata();
        public Builder documentId(String v)     { m.documentId = v; return this; }
        public Builder tenantId(String v)        { m.tenantId = v; return this; }
        public Builder documentType(String v)    { m.documentType = v; return this; }
        public Builder referenceNumber(String v) { m.referenceNumber = v; return this; }
        public Builder title(String v)           { m.title = v; return this; }
        public Builder fileName(String v)        { m.fileName = v; return this; }
        public Builder uploadedBy(String v)      { m.uploadedBy = v; return this; }
        public Builder language(String v)        { m.language = v; return this; }
        public Builder category(String v)        { m.category = v; return this; }
        public Builder department(String v)      { m.department = v; return this; }
        public Builder folder(String v)          { m.folder = v; return this; }
        public Builder pageNumber(int v)         { m.pageNumber = v; return this; }
        public Builder effectiveDate(LocalDate v){ m.effectiveDate = v; return this; }
        public Builder inputType(String v)       { m.inputType = v; return this; }
        public Builder sourceChannel(String v)   { m.sourceChannel = v; return this; }
        public Builder ownerSub(String v)        { m.ownerSub = v; return this; }
        public DocumentMetadata build()          { return m; }
    }
}
