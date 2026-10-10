package io.orphos.eip.docingest.storage;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM file encryption for documents at rest.
 * On-disk format: [12-byte random IV][GCM ciphertext + 16-byte auth tag].
 */
final class AesGcmFileCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES  = 12;
    private static final int TAG_BITS  = 128;
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmFileCrypto() {}

    static SecretKey keyFromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                "doc-ingest.storage.encryption-key-base64 is required when encryption is enabled");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("doc-ingest.storage.encryption-key-base64 is not valid Base64", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("encryption key must decode to 32 bytes (AES-256), got " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    static void encrypt(SecretKey key, Path source, Path target) throws IOException {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try (OutputStream raw = Files.newOutputStream(target);
             OutputStream out = new CipherOutputStream(raw, cipher(Cipher.ENCRYPT_MODE, key, iv));
             InputStream in = Files.newInputStream(source)) {
            raw.write(iv);
            in.transferTo(out);
        }
    }

    static InputStream decryptingStream(SecretKey key, InputStream in) throws IOException {
        byte[] iv = in.readNBytes(IV_BYTES);
        if (iv.length != IV_BYTES) throw new IOException("Encrypted file truncated: missing IV");
        return new CipherInputStream(in, cipher(Cipher.DECRYPT_MODE, key, iv));
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] iv) throws IOException {
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IOException("AES-256-GCM init failed", e);
        }
    }
}
