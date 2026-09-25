package com.apms.domain.security.service;

import com.apms.domain.security.config.TotpProperties;
import com.apms.domain.security.exception.TotpException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TotpSecretEncryptionServiceTest {

    private TotpProperties totpProperties;
    private TotpSecretEncryptionService service;

    @BeforeEach
    void setUp() {
        totpProperties = new TotpProperties();
    }

    private String generateValidKey(int length) {
        byte[] keyBytes = new byte[length];
        new SecureRandom().nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    @Test
    void init_MissingKey_ThrowsException() {
        totpProperties.setEncryptionKey(null);
        service = new TotpSecretEncryptionService(totpProperties);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.init());
        assertTrue(ex.getMessage().contains("is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void init_BlankKey_ThrowsException(String blankKey) {
        totpProperties.setEncryptionKey(blankKey);
        service = new TotpSecretEncryptionService(totpProperties);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.init());
        assertTrue(ex.getMessage().contains("is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"${TOTP_ENCRYPTION_KEY}", "$TOTP_ENCRYPTION_KEY", "TOTP_ENCRYPTION_KEY"})
    void init_UnresolvedPlaceholder_ThrowsException(String placeholder) {
        totpProperties.setEncryptionKey(placeholder);
        service = new TotpSecretEncryptionService(totpProperties);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.init());
        assertTrue(ex.getMessage().contains("unresolved placeholder"));
    }

    @Test
    void init_InvalidBase64_ThrowsException() {
        // contains '$'
        totpProperties.setEncryptionKey("Abcdefgh$JKLMNOPQRSTUVWXYZ1234567890=");
        service = new TotpSecretEncryptionService(totpProperties);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.init());
        assertTrue(ex.getMessage().contains("must be a valid standard Base64 string"));
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 24, 31, 33})
    void init_WrongLength_ThrowsException(int length) {
        totpProperties.setEncryptionKey(generateValidKey(length));
        service = new TotpSecretEncryptionService(totpProperties);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.init());
        assertTrue(ex.getMessage().contains("must decode to exactly 32 bytes"));
    }

    @Test
    void init_Valid32ByteKey_Success() {
        totpProperties.setEncryptionKey(generateValidKey(32));
        service = new TotpSecretEncryptionService(totpProperties);

        assertDoesNotThrow(() -> service.init());
    }

    @Test
    void encryptAndDecrypt_Success() {
        totpProperties.setEncryptionKey(generateValidKey(32));
        service = new TotpSecretEncryptionService(totpProperties);
        service.init();

        String plainSecret = "MY_SUPER_SECRET_TOTP_KEY";
        Long accountId = 12345L;

        TotpSecretEncryptionService.EncryptedSecret encrypted = service.encrypt(plainSecret, accountId);
        
        assertNotNull(encrypted.cipherText());
        assertNotNull(encrypted.iv());
        assertEquals(service.getCurrentKeyVersion(), encrypted.keyVersion());

        String decryptedSecret = service.decrypt(encrypted.cipherText(), encrypted.iv(), encrypted.keyVersion(), accountId);
        
        assertEquals(plainSecret, decryptedSecret);
    }

    @Test
    void encrypt_DifferentIVEachTime() {
        totpProperties.setEncryptionKey(generateValidKey(32));
        service = new TotpSecretEncryptionService(totpProperties);
        service.init();

        String plainSecret = "SECRET";
        Long accountId = 1L;

        TotpSecretEncryptionService.EncryptedSecret enc1 = service.encrypt(plainSecret, accountId);
        TotpSecretEncryptionService.EncryptedSecret enc2 = service.encrypt(plainSecret, accountId);

        assertFalse(java.util.Arrays.equals(enc1.iv(), enc2.iv()));
        assertFalse(java.util.Arrays.equals(enc1.cipherText(), enc2.cipherText()));
    }

    @Test
    void decrypt_WrongAccountId_ThrowsException() {
        totpProperties.setEncryptionKey(generateValidKey(32));
        service = new TotpSecretEncryptionService(totpProperties);
        service.init();

        TotpSecretEncryptionService.EncryptedSecret encrypted = service.encrypt("SECRET", 100L);

        // Try decrypting with wrong AAD
        TotpException ex = assertThrows(TotpException.class, () -> 
            service.decrypt(encrypted.cipherText(), encrypted.iv(), encrypted.keyVersion(), 200L)
        );
        assertTrue(ex.getMessage().contains("Failed to decrypt TOTP secret"));
    }
}
