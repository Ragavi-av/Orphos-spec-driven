package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.secrets.SecretRefService;
import io.orphos.eip.secrets.SecretsClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IdpSecretStoreTest {

    private final SecretRefService secrets = newUnconfiguredSecrets();
    private final IdpSecretStore store = new IdpSecretStore(secrets);

    @Test
    void extractRejectsRawSecretsWhenOpenBaoNotConfigured() {
        Map<String, Object> cfg = Map.of(
                "issuerUri", "https://idp.example",
                "clientId", "abc",
                "clientSecret", "raw-secret");
        // Fails closed: refuses to persist plaintext if OpenBao isn't available.
        assertThrows(IllegalStateException.class, () -> store.extractAndStore("p1", cfg));
    }

    @Test
    void existingVaultRefsArePassedThroughUnchanged() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("issuerUri", "https://idp.example");
        cfg.put("clientId", "abc");
        cfg.put("clientSecret", "vault:admin/idp/p1/clientSecret/xyz#value");
        Map<String, Object> out = store.extractAndStore("p1", cfg);
        assertEquals("vault:admin/idp/p1/clientSecret/xyz#value", out.get("clientSecret"));
    }

    @Test
    void resolveAllStripsUnresolvableVaultRefs() {
        Map<String, Object> cfg = Map.of(
                "url", "ldap://host",
                "managerPassword", "vault:admin/idp/p1/managerPassword/xyz#value");
        Map<String, Object> out = store.resolveAll(cfg);
        // Unresolvable (OpenBao isn't configured) -> key removed; calling
        // code must fail closed when the secret it needs isn't available.
        assertFalse(out.containsKey("managerPassword"));
        assertEquals("ldap://host", out.get("url"));
    }

    @Test
    void nonStringConfigValuesAreLeftAlone() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("clientSecret", 42);   // numeric -> not a secret to extract
        Map<String, Object> out = store.extractAndStore("p1", cfg);
        assertEquals(42, out.get("clientSecret"));
    }

    private static SecretRefService newUnconfiguredSecrets() {
        var s = new SecretRefService(new SecretsClient("", "", "", "", ""));
        ReflectionTestUtils.setField(s, "namespace", "admin");
        return s;
    }
}
