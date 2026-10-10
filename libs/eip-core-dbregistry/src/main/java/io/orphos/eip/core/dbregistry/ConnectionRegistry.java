package io.orphos.eip.core.dbregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * In-memory shape of the connections registry file (default
 * {@code /run/eip/connections.json}). This is the <em>bootstrap</em> source of
 * truth for which database a service connects to — it is read before, and
 * independently of, the databases it configures, which is why it lives in a
 * file rather than in ArcadeDB.
 *
 * <p>Only non-secret metadata lives here. Passwords are never stored; a
 * {@link Connection#credentialRef()} points at an Infisical secret
 * ({@code path#field}) that the existing {@code eip-secrets} pipeline resolves
 * at boot.</p>
 *
 * <p>All records are lenient on unknown properties so the file format can grow
 * without breaking older services that still read it.</p>
 */
public final class ConnectionRegistry {

    private ConnectionRegistry() {}

    /** Connection type. Only {@link #ARCADEDB} has consumers today; the others are
     *  accepted and validated by the admin UI but not yet wired into any service. */
    public enum Type { ARCADEDB, POSTGRES, NEO4J }

    /** How a service resolves the database/schema it uses on an assigned connection. */
    public enum SchemaMode { DEFAULT, EXISTING }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record File(
            List<Connection> connections,
            Map<String, Assignment> assignments) {

        public File {
            connections = connections == null ? List.of() : connections;
            assignments = assignments == null ? Map.of() : assignments;
        }

        public Connection connection(String id) {
            if (id == null) return null;
            for (Connection c : connections) {
                if (c != null && id.equals(c.id())) return c;
            }
            return null;
        }
    }

    /**
     * A named database endpoint. {@code url} is optional — when blank it is
     * derived from {@code host}/{@code port} per {@code type}
     * (ArcadeDB {@code http://host:port}, Postgres {@code jdbc:postgresql://host:port/},
     * Neo4J {@code bolt://host:port}). Provide {@code url} explicitly for cloud
     * endpoints that need TLS or a non-standard shape.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Connection(
            String id,
            String name,
            String type,
            String host,
            Integer port,
            String user,
            String url,
            Map<String, String> options,
            String credentialRef) {

        public Connection {
            options = options == null ? Map.of() : options;
        }

        public Type typeEnum() {
            if (type == null) return null;
            try {
                return Type.valueOf(type.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Binds a service (by {@code spring.application.name}) to a connection + its database/schema. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assignment(
            String connectionId,
            String database,
            String schemaMode) {

        public SchemaMode schemaModeEnum() {
            if (schemaMode == null || schemaMode.isBlank()) return SchemaMode.DEFAULT;
            try {
                return SchemaMode.valueOf(schemaMode.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return SchemaMode.DEFAULT;
            }
        }
    }
}
