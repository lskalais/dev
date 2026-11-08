package fr.alp.confidentia.security;

import fr.alp.confidentia.config.AppConfiguration;
import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class CryptoService {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoService() {
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(encrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        return new String(decrypt(Base64.getDecoder().decode(ciphertext)), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] encrypt(byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Le chiffrement des données sensibles a échoué.", exception);
        }
    }

    public static byte[] decrypt(byte[] ciphertext) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
            byte[] nonce = new byte[NONCE_LENGTH];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(encrypted);
        } catch (AEADBadTagException exception) {
            throw new UnreadableCiphertextException(exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Le déchiffrement des données sensibles a échoué.", exception);
        }
    }

    public static final class UnreadableCiphertextException extends IllegalStateException {
        private UnreadableCiphertextException(AEADBadTagException cause) {
            super("Les données chiffrées ne correspondent pas à la clé AES configurée.", cause);
        }
    }

    private static SecretKeySpec key() {
        String encoded = AppConfiguration.get("APP_ENCRYPTION_KEY", null);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("La variable APP_ENCRYPTION_KEY (clé Base64 de 32 octets) est requise.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY doit être encodée en Base64.", exception);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY doit décoder en exactement 32 octets.");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
