package org.remus.giteabot.admin;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for encrypting and decrypting sensitive credentials.
 *
 * If APP_ENCRYPTION_KEY environment variable is set, values are encrypted using AES-GCM.
 * If not set, values are stored as plain text (suitable for development, not recommended for production).
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();
    @Getter
    private final boolean encryptionEnabled;

    public EncryptionService(@Value("${app.encryption-key:#{null}}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.warn("No APP_ENCRYPTION_KEY configured. Secrets will be stored as plain text. " +
                    "Set APP_ENCRYPTION_KEY environment variable for production use.");
            this.secretKey = null;
            this.encryptionEnabled = false;
        } else {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] keyBytes = digest.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
                this.secretKey = new SecretKeySpec(keyBytes, "AES");
                this.encryptionEnabled = true;
                log.info("Encryption enabled with APP_ENCRYPTION_KEY");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize encryption key", e);
            }
        }
    }

    /**
     * Encrypts the given plain text if encryption is enabled, otherwise returns it unchanged.
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return plainText;
        }

        if (!encryptionEnabled) {
            // No encryption - return plain text as-is
            return plainText;
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypts the given cipher text if encryption is enabled, otherwise returns it unchanged.
     * If decryption fails (e.g., data wasn't encrypted or wrong key), returns the original value.
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return cipherText;
        }

        if (!encryptionEnabled) {
            // No encryption configured - return as-is (plain text storage)
            // Strip any legacy "ENC:" prefix if present
            if (cipherText.startsWith("ENC:")) {
                log.warn("Found encrypted data but no encryption key configured. " +
                        "Data may be corrupted. Please configure APP_ENCRYPTION_KEY or re-enter the credentials.");
                return cipherText.substring(4); // Return the base64 blob, which won't work but won't crash
            }
            return cipherText;
        }

        // Strip legacy "ENC:" prefix if present
        String data = cipherText.startsWith("ENC:") ? cipherText.substring(4) : cipherText;

        try {
            byte[] combined = Base64.getDecoder().decode(data);

            if (combined.length < IV_LENGTH) {
                // Not encrypted data - return as-is
                return cipherText;
            }

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Not valid base64 - probably plain text, return as-is
            log.debug("Value is not encrypted (not valid base64), returning as-is");
            return cipherText;
        } catch (Exception e) {
            // Decryption failed - might be plain text or wrong key
            log.debug("Decryption failed, returning value as-is: {}", e.getMessage());
            return cipherText;
        }
    }

}
