package io.orphos.eip.admin.component;

import io.orphos.eip.admin.component.SecretRefService;
import io.orphos.eip.admin.service.EmailSendService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * SMTP password storage backed by OpenBao (Vault-compatible KV v2).
 *
 * <p>Each invocation of {@link #store(String)} writes the raw password to a
 * freshly-generated path under {@code admin/smtp/...} and returns an opaque
 * {@code vault:path#field} reference. The reference is the only thing that
 * lives in the ArcadeDB {@code EmailConfig} document; the secret itself
 * never touches the operational database.</p>
 *
 * <p>An operator rotating the SMTP password through the admin UI calls
 * {@link #rotate(String, String)}, which overwrites the existing vault path
 * in place so historical audit entries that captured the old ref are still
 * resolvable to the current secret (a deliberate trade-off - if you'd rather
 * keep refs immutable per-version, switch to {@link #store(String)} on each
 * rotation and update the {@code passwordRef} on the EmailConfig row).</p>
 *
 * <p>Reads return {@link Optional#empty()} when OpenBao is unreachable or the
 * secret has been deleted; the caller (typically {@link EmailSendService})
 * surfaces a clear error to the operator rather than silently dropping
 * email delivery.</p>
 */
@Component
public class SmtpSecretStore {

    private static final String CATEGORY = "smtp";
    private static final String FIELD    = "password";

    private final SecretRefService secrets;

    public SmtpSecretStore(SecretRefService secrets) {
        this.secrets = secrets;
    }

    /** Persist a brand-new SMTP password. Returns the opaque vault ref to store on the EmailConfig row. */
    public String store(String raw) {
        return secrets.store(CATEGORY, FIELD, raw);
    }

    /** In-place rotation: same vault path, new value. Returns the (unchanged) ref. */
    public String rotate(String existingRef, String raw) {
        return secrets.overwrite(existingRef, raw);
    }

    public Optional<String> resolve(String ref) {
        return secrets.resolve(ref);
    }

    /** Called when an EmailConfig row is deleted - keeps the vault clean. */
    public void purge(String ref) {
        secrets.delete(ref);
    }
}
