package io.orphos.eip.core.dbregistry;

import org.springframework.core.env.Environment;

/**
 * Reads the effective schema mode a service should honour for its assigned
 * database connection. The value is contributed at bootstrap by
 * {@link ConnectionRegistryEnvironmentPostProcessor} under
 * {@link #PROPERTY} from the service's registry assignment; it is absent (and
 * therefore {@link ConnectionRegistry.SchemaMode#DEFAULT DEFAULT}) when the
 * service has no explicit assignment and falls back to the bundled default.
 *
 * <h2>Contract for schema-bootstrap runners</h2>
 * A service whose {@code ApplicationRunner} creates databases / applies DDL must
 * call {@link #isExisting(Environment)} at the top of its {@code run(...)} and
 * return early when it is {@code true}: in {@code EXISTING} mode the operator has
 * pointed the service at a pre-provisioned database they manage, so the platform
 * must neither create the database nor run any {@code CREATE ...} DDL against it.
 * This is the guard that makes {@code EXISTING} actually protect a populated,
 * operator-owned database from having platform types created into it.
 */
public final class SchemaModeGuard {

    /** Property key the binder contributes; read as {@code ${eip.connections.schema-mode:DEFAULT}}. */
    public static final String PROPERTY = "eip.connections.schema-mode";

    private SchemaModeGuard() {}

    /** The resolved mode for this service; {@code DEFAULT} when unset/blank/unrecognised. */
    public static ConnectionRegistry.SchemaMode resolve(Environment environment) {
        String v = environment == null ? null : environment.getProperty(PROPERTY);
        if (v == null || v.isBlank()) {
            return ConnectionRegistry.SchemaMode.DEFAULT;
        }
        try {
            return ConnectionRegistry.SchemaMode.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ConnectionRegistry.SchemaMode.DEFAULT;
        }
    }

    /**
     * True when the assigned connection is in {@code EXISTING} mode — the signal
     * for a schema-bootstrap runner to skip database creation and all DDL.
     */
    public static boolean isExisting(Environment environment) {
        return resolve(environment) == ConnectionRegistry.SchemaMode.EXISTING;
    }
}
