package com.apms.domain.ai.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class AiApiKeyEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final byte[] AAD = "APMS_GEMINI_KEY".getBytes(StandardCharsets.UTF_8);

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiApiKeyEncryptionService(
            @Value("${security.ai.encryption-key:}") String aiKey,
            @Value("${security.totp.encryption-key:}") String totpKey,
            @Value("${jwt.secret:}") String jwtSecret) {
        this.secretKey = resolveSecretKey(aiKey, totpKey, jwtSecret);
    }

    private SecretKey resolveSecretKey(String aiKey, String totpKey, String jwtSecret) {
        if (aiKey != null && !aiKey.isBlank()) {
            return buildKey(aiKey.trim());
        }
        if (totpKey != null && !totpKey.isBlank() && !totpKey.contains("${")) {
            return buildKey(totpKey.trim());
        }
        if (jwtSecret != null && !jwtSecret.isBlank() && !jwtSecret.contains("${")) {
            return buildKeyFromHash(jwtSecret.trim());
        }
        return buildKeyFromHash("apms-gemini-ai-key-secret-seed-fallback");
    }

    private SecretKey buildKey(String raw) {
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            if (decoded.length == 32) {
                return new SecretKeySpec(decoded, "AES");
            }
        } catch (IllegalArgumentException ignored) {
        }
        return buildKeyFromHash(raw);
    }

    private SecretKey buildKeyFromHash(String input) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(input.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public EncryptedSecret encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("API key cannot be empty");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            cipher.updateAAD(AAD);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            return new EncryptedSecret(
                    Base64.getEncoder().encodeToString(cipherText),
                    Base64.getEncoder().encodeToString(iv),
                    "v1"
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt AI API key", e);
        }
    }

    public String decrypt(String cipherTextBase64, String ivBase64, String keyVersion) {
        if (cipherTextBase64 == null || ivBase64 == null) {
            return null;
        }
        try {
            byte[] cipherText = Base64.getDecoder().decode(cipherTextBase64);
            byte[] iv = Base64.getDecoder().decode(ivBase64);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            cipher.updateAAD(AAD);

            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt AI API key. Key might be corrupted or encryption secret changed.");
            throw new IllegalStateException("Failed to decrypt AI API key", e);
        }
    }

    public record EncryptedSecret(String cipherTextBase64, String ivBase64, String keyVersion) {
    }
}
