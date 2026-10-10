package io.orphos.eip.docingest.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL audit trail for file operations.
 * Mirrors Ragas MVP's FileAuditService; writes one JSON object per line.
 */
@Component
public class FileAuditService {

    private static final Logger log = LogManager.getLogger(FileAuditService.class);

    @Value("${doc-ingest.audit.log-path:/data/doc-ingest/audit.jsonl}")
    private String auditLogPath;

    private final ObjectMapper mapper;

    public FileAuditService() {
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public void record(String eventType, String documentId, String tenantId,
                       String fileName, String performedBy, String detail) {
        AuditEvent event = new AuditEvent(
            eventType, documentId, tenantId, fileName, performedBy, detail, Instant.now());
        try {
            Path path = Paths.get(auditLogPath);
            Files.createDirectories(path.getParent());
            String line = mapper.writeValueAsString(event) + "\n";
            Files.writeString(path, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Audit write failed for event {} on document {}: {}", eventType, documentId, e.getMessage());
        }
    }

    public List<AuditEvent> readAll(String tenantId) {
        List<AuditEvent> events = new ArrayList<>();
        Path path = Paths.get(auditLogPath);
        if (!Files.exists(path)) return events;
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) continue;
                AuditEvent event = mapper.readValue(line, AuditEvent.class);
                if (tenantId == null || tenantId.equals(event.tenantId())) {
                    events.add(event);
                }
            }
        } catch (IOException e) {
            log.warn("Could not read audit log: {}", e.getMessage());
        }
        return events;
    }
}
