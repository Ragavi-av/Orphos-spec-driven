package io.orphos.eip.admin.dbconnections.service;

import io.orphos.eip.core.dbregistry.ConnectionRegistry;

import java.util.List;
import java.util.Map;

/**
 * The connections the platform ships with out of the box — the bundled ArcadeDB
 * and the bundled Postgres containers (compose profile {@code bundled-db}).
 *
 * <p>This is the single Java source of truth for those defaults;
 * {@code config/connections/connections.default.json} carries the same two
 * entries for the {@code connections-seed} container that seeds a <em>fresh</em>
 * install. {@link DbConnectionService} also ensures these exist on startup so
 * pre-existing installs (whose {@code connections.json} predates a new default)
 * pick them up on the next restart. Keep the two in sync when adding a default.</p>
 */
final class BundledConnections {

    private BundledConnections() {}

    static final String ARCADEDB_ID = "arcadedb-bundled";
    static final String POSTGRES_ID = "postgres-bundled";

    /** Shared root credential — the bundled Postgres uses {@code POSTGRES_PASSWORD=${ARCADEDB_PASSWORD}}. */
    private static final String BUNDLED_CREDENTIAL_REF = "eip/arcadedb#password";

    static List<ConnectionRegistry.Connection> all() {
        return List.of(
                new ConnectionRegistry.Connection(
                        ARCADEDB_ID, "Bundled ArcadeDB", ConnectionRegistry.Type.ARCADEDB.name(),
                        "eip-arcadedb", 2480, "root", null, Map.of(), BUNDLED_CREDENTIAL_REF),
                new ConnectionRegistry.Connection(
                        POSTGRES_ID, "Bundled PostgreSQL", ConnectionRegistry.Type.POSTGRES.name(),
                        "eip-postgres", 5432, "postgres", null, Map.of(), BUNDLED_CREDENTIAL_REF));
    }
}
