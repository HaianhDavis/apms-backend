package com.apms.domain.security.service;

import com.apms.domain.security.config.TotpProperties;
import com.apms.domain.security.exception.TotpException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class TotpSecretEncryptionService {

    private final TotpProperties totpProperties;

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12; // 12 bytes is standard for GCM

    private SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @Getter
    private final String currentKeyVersion = "v1";

    @PostConstruct
    public void init() {
        String configuredKey = totpProperties.getEncryptionKey();

        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException(
                "TOTP_ENCRYPTION_KEY is required. " +
                "Configure a Base64-encoded 32-byte AES key."
            );
        }

        String normalizedKey = configuredKey.trim();

        if (normalizedKey.contains("${") 
                || normalizedKey.startsWith("$") 
                || normalizedKey.equalsIgnoreCase("TOTP_ENCRYPTION_KEY")) {
            throw new IllegalStateException(
                "TOTP_ENCRYPTION_KEY contains an unresolved placeholder. " +
                "Check application configuration and environment variables."
            );
        }

        final byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(normalizedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "TOTP_ENCRYPTION_KEY must be a valid standard Base64 string.",
                exception
            );
        }

        if (decodedKey.length != 32) {
            throw new IllegalStateException(
                "TOTP_ENCRYPTION_KEY must decode to exactly 32 bytes for AES-256. " +
                "Current decoded length: " + decodedKey.length + " bytes."
            );
        }

        this.secretKey = new SecretKeySpec(decodedKey, "AES");
    }

    public EncryptedSecret encrypt(String plainSecret, Long accountId) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            // Use accountId as AAD
            byte[] aad = String.valueOf(accountId).getBytes(StandardCharsets.UTF_8);
            cipher.updateAAD(aad);

            byte[] cipherText = cipher.doFinal(plainSecret.getBytes(StandardCharsets.UTF_8));

            return new EncryptedSecret(cipherText, iv, currentKeyVersion);
        } catch (Exception e) {
            throw new TotpException("Failed to encrypt TOTP secret", e);
        }
    }

    public String decrypt(byte[] cipherText, byte[] iv, String keyVersion, Long accountId) {
        if (!currentKeyVersion.equals(keyVersion)) {
            // Future-proofing for key rotation
            throw new TotpException("Unsupported encryption key version: " + keyVersion);
        }

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            // Use accountId as AAD
            byte[] aad = String.valueOf(accountId).getBytes(StandardCharsets.UTF_8);
            cipher.updateAAD(aad);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new TotpException("Failed to decrypt TOTP secret. The data might be corrupted or the key changed.");
        }
    }

    public record EncryptedSecret(byte[] cipherText, byte[] iv, String keyVersion) {
    }
}
