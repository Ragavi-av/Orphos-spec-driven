package io.orphos.eip.docingest.source;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.docingest.storage.LocalDocumentStorage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The bundled local filesystem exposed as a folder connection, so "Local storage" is just one
 * entry in the shared folder-connection pool alongside S3/FTP/... The browse/createFolder calls
 * delegate to {@link LocalDocumentStorage} for the current tenant (path-traversal safe there).
 *
 * <p>It is a storage location, not an ingestion source — hence {@link #capabilities()} advertises
 * BROWSE only (list/open are left unsupported).</p>
 */
@Component
public class LocalFolderConnector implements SourceConnector {

    private final LocalDocumentStorage local;

    public LocalFolderConnector(LocalDocumentStorage local) {
        this.local = local;
    }

    @Override
    public SourceKind kind() { return SourceKind.LOCAL; }

    @Override
    public Set<Capability> capabilities() { return EnumSet.of(Capability.BROWSE); }

    @Override
    public ProbeResult test(Descriptor desc) {
        return ProbeResult.ok(0); // the local volume is always reachable in-process
    }

    @Override
    public List<FolderEntry> browse(Descriptor desc, String path) {
        String tenant = TenantContextHolder.requireTenantId().toString();
        return local.browse(tenant, path).stream()
                .map(f -> new FolderEntry(f.name(), f.path(), f.hasChildren()))
                .toList();
    }

    @Override
    public void createFolder(Descriptor desc, String path) throws IOException {
        local.createFolder(TenantContextHolder.requireTenantId().toString(), path);
    }
}
