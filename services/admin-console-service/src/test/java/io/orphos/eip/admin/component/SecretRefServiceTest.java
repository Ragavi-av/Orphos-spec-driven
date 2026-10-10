package io.orphos.eip.admin.component;

import io.orphos.eip.secrets.SecretsClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class SecretRefServiceTest {

    @Test
    void writesFailClosedWhenOpenBaoNotConfigured() {
        // Blank token/url -> client.isConfigured() == false.
        var svc = new SecretRefService(new SecretsClient("", "", "", "", ""));
        ReflectionTestUtils.setField(svc, "namespace", "admin");
        assertThrows(IllegalStateException.class, () -> svc.store("smtp", "password", "pw"));
    }

    @Test
    void readsForUnconfiguredClientReturnEmpty() {
        var svc = new SecretRefService(new SecretsClient("", "", "", "", ""));
        ReflectionTestUtils.setField(svc, "namespace", "admin");
        assertTrue(svc.resolve("vault:admin/smtp/abc#password").isEmpty());
        assertTrue(svc.resolve(null).isEmpty());
        assertTrue(svc.resolve("plain-string-not-a-ref").isEmpty());
    }

    @Test
    void detectsManagedRefsByPrefix() {
        assertTrue(SecretRefService.isManaged("vault:foo/bar#field"));
        assertFalse(SecretRefService.isManaged("ref:foo"));
        assertFalse(SecretRefService.isManaged(""));
        assertFalse(SecretRefService.isManaged(null));
    }
}
