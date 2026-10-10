package io.orphos.eip.admin.component;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssetValidatorTest {

    private AssetValidator v;

    @BeforeEach
    void setUp() {
        v = new AssetValidator();
        ReflectionTestUtils.setField(v, "maxLogoBytes", 1024L * 1024L);
        ReflectionTestUtils.setField(v, "maxBackgroundBytes", 4L * 1024 * 1024);
        ReflectionTestUtils.setField(v, "allowedMime",
                List.of("image/png", "image/jpeg", "image/svg+xml", "image/webp"));
    }

    @Test
    void rejectsEmptyUpload() {
        assertThrows(IllegalArgumentException.class,
                () -> v.validate("logo", "image/png", new byte[0]));
    }

    @Test
    void rejectsMimeMismatch() {
        // Declared PNG, but bytes are not PNG magic.
        byte[] notPng = "hello".getBytes();
        assertThrows(IllegalArgumentException.class,
                () -> v.validate("logo", "image/png", notPng));
    }

    @Test
    void acceptsPngMagic() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};
        assertDoesNotThrow(() -> v.validate("logo", "image/png", png));
    }

    @Test
    void rejectsExoticMimes() {
        assertThrows(IllegalArgumentException.class,
                () -> v.validate("logo", "application/x-shellscript", new byte[]{1, 2, 3}));
    }
}
