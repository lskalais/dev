package fr.alp.confidentia.security;

import fr.alp.confidentia.config.AppConfiguration;
import fr.alp.confidentia.testsupport.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires de {@link CryptoService} (AES-GCM).
 * <p>
 * La clé est lue via {@code AppConfiguration.get("APP_ENCRYPTION_KEY", null)}
 * qui consulte, dans l'ordre,
 * le ServletContext (non initialisé ici), les propriétés système puis les
 * variables d'environnement.
 * Chaque test pose donc une propriété système, et restaure l'état initial après
 * exécution.
 */
@DisplayName("CryptoService - chiffrement AES-GCM")
class CryptoServiceTest {

    private static final String KEY_PROPERTY = "APP_ENCRYPTION_KEY";
    private String originalKey;

    @BeforeEach
    void setUp() {
        originalKey = System.getProperty(KEY_PROPERTY);
        System.setProperty(KEY_PROPERTY, TestSupport.VALID_ENCRYPTION_KEY);
    }

    @AfterEach
    void tearDown() {
        if (originalKey == null) {
            System.clearProperty(KEY_PROPERTY);
        } else {
            System.setProperty(KEY_PROPERTY, originalKey);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cas nominaux
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("encrypt/decrypt(String) : une chaîne chiffrée puis déchiffrée restitue l'original")
    void encryptPuisDecrypt_chaineValide_restitueLAChaineOriginale() {
        // Préparation : texte UTF-8 incluant accents et emoji (le nonce est aléatoire
        // mais le contenu doit survivre).
        String plaintext = "Société Ébène & Fils — données confidentielles 🔐";

        String ciphertext = CryptoService.encrypt(plaintext);

        assertNotEquals(plaintext, ciphertext, "Le texte chiffré ne doit pas être lisible");
        assertEquals(plaintext, CryptoService.decrypt(ciphertext), "Le déchiffrement doit restituer l'original");
    }

    @Test
    @DisplayName("encrypt(String) : le résultat est bien du Base64 décodable")
    void encrypt_chaineValide_retourneDuBase64Valide() {
        String ciphertext = CryptoService.encrypt("valeur sensible");

        assertDoesNotThrow(() -> Base64.getDecoder().decode(ciphertext),
                "La sortie doit être une chaîne Base64 valide (stockable en colonne texte)");
    }

    @Test
    @DisplayName("encrypt(String) : deux chiffrements du même texte produisent des résultats différents (nonce aléatoire)")
    void encrypt_memeTexteDeuxFois_produitDesChiffresDifferents() {
        String first = CryptoService.encrypt("identique");
        String second = CryptoService.encrypt("identique");

        assertNotEquals(first, second, "Un nonce aléatoire doit être utilisé à chaque chiffrement");
    }

    @Test
    @DisplayName("encrypt/decrypt(byte[]) : un tableau d'octets est restitué à l'identique")
    void encryptPuisDecrypt_tableauDOctets_restitueLesOctets() {
        byte[] plaintext = "Contenu binaire du document".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = CryptoService.encrypt(plaintext);
        byte[] decrypted = CryptoService.decrypt(ciphertext);

        // Le texte chiffré contient le nonce (12 octets) + les données + le tag GCM (16
        // octets)
        assertEquals(plaintext.length + 12 + 16, ciphertext.length,
                "La sortie doit contenir nonce + données + tag d'authentification");
        assertFalse(Arrays.equals(plaintext, ciphertext), "Les octets doivent réellement être chiffrés");
        assertArrayEquals(plaintext, decrypted, "Le déchiffrement doit restituer les octets initiaux");
    }

    // ---------------------------------------------------------------------------------------------
    // Cas limites
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("encrypt/decrypt(String) : la chaîne vide est supportée")
    void encryptPuisDecrypt_chaineVide_fonctionne() {
        String ciphertext = CryptoService.encrypt("");

        assertNotNull(ciphertext);
        assertEquals("", CryptoService.decrypt(ciphertext), "Une chaîne vide doit être chiffrée et restituée vide");
    }

    @Test
    @DisplayName("encrypt/decrypt(byte[]) : un tableau vide est supporté")
    void encryptPuisDecrypt_tableauVide_fonctionne() {
        byte[] ciphertext = CryptoService.encrypt(new byte[0]);

        assertEquals(12 + 16, ciphertext.length, "Seul le nonce et le tag doivent rester");
        assertArrayEquals(new byte[0], CryptoService.decrypt(ciphertext));
    }

    @Test
    @DisplayName("encrypt(String) : un texte très long est traité sans erreur")
    void encrypt_chaineTresLongue_fonctionne() {
        String plaintext = "x".repeat(100_000);

        String ciphertext = CryptoService.encrypt(plaintext);

        assertEquals(plaintext, CryptoService.decrypt(ciphertext));
    }

    @Test
    @DisplayName("encrypt(null) : retourne null (les champs optionnels ne sont pas chiffrés)")
    void encrypt_null_retourneNull() {
        assertNull(CryptoService.encrypt((String) null));
    }

    @Test
    @DisplayName("decrypt(null) : retourne null")
    void decrypt_null_retourneNull() {
        assertNull(CryptoService.decrypt((String) null));
    }

    @Test
    @DisplayName("encrypt(byte[]) : un tableau null est rejeté par le fournisseur JCE (IllegalArgumentException)")
    void encrypt_tableauNull_leveIllegalArgumentException() {
        // La surcharge byte[] n'a pas de garde null et ne lève pas d'IllegalStateException :
        // c'est le Cipher qui rejette l'entrée via une IllegalArgumentException. Contrat à connaître
        // pour les appelants susceptibles de transmettre une entrée non validée.
        assertThrows(IllegalArgumentException.class, () -> CryptoService.encrypt((byte[]) null));
    }

    // ---------------------------------------------------------------------------------------------
    // Cas d'erreur
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Erreurs de configuration de la clé")
    class CleDeChiffrementInvalide {

        @Test
        @DisplayName("clé absente : IllegalStateException explicite")
        void encrypt_cleAbsente_leveIllegalStateException() {
            // La résolution consulte le contexte, puis la propriété système, puis l'environnement :
            // on neutralise les trois sources pour simuler une configuration réellement absente.
            try (MockedStatic<AppConfiguration> configuration = clearConfiguration()) {
                configuration.when(() -> AppConfiguration.get(KEY_PROPERTY, null)).thenReturn(null);

                IllegalStateException exception = assertThrows(IllegalStateException.class,
                        () -> CryptoService.encrypt("secret"));

                // Le message est vérifié car il guide l'exploitant vers la variable manquante.
                assertTrue(exception.getMessage().contains("APP_ENCRYPTION_KEY"));
            }
        }

        @Test
        @DisplayName("clé vide/blanche : IllegalStateException")
        void encrypt_cleBlanche_leveIllegalStateException() {
            try (MockedStatic<AppConfiguration> configuration = clearConfiguration()) {
                configuration.when(() -> AppConfiguration.get(KEY_PROPERTY, null)).thenReturn("   ");

                assertThrows(IllegalStateException.class, () -> CryptoService.encrypt("secret"));
            }
        }

        /** Isole la lecture de configuration statique d'une éventuelle variable d'environnement de la machine. */
        private MockedStatic<AppConfiguration> clearConfiguration() {
            System.clearProperty(KEY_PROPERTY);
            return Mockito.mockStatic(AppConfiguration.class);
        }

        @Test
        @DisplayName("clé non Base64 : IllegalStateException")
        void encrypt_cleNonBase64_leveIllegalStateException() {
            System.setProperty(KEY_PROPERTY, "ceci-n-est-pas-du-base64!!");

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> CryptoService.encrypt("secret"));

            assertTrue(exception.getMessage().contains("Base64"));
        }

        @ParameterizedTest(name = "clé de {0} octets rejetée")
        @ValueSource(ints = { 16, 24, 31, 33 })
        @DisplayName("clé de taille incorrecte : IllegalStateException (32 octets exigés)")
        void encrypt_cleTailleIncorrecte_leveIllegalStateException(int keyLength) {
            System.setProperty(KEY_PROPERTY, Base64.getEncoder().encodeToString(new byte[keyLength]));

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> CryptoService.encrypt("secret"));

            assertTrue(exception.getMessage().contains("32 octets"));
        }

        @Test
        @DisplayName("8 octets (AES-128 possible en Java mais refusé par la politique applicative)")
        void encrypt_cleAes128_refusee() {
            System.setProperty(KEY_PROPERTY, Base64.getEncoder().encodeToString(new byte[8]));

            assertThrows(IllegalStateException.class, () -> CryptoService.encrypt("secret"));
        }
    }

    @Nested
    @DisplayName("Erreurs de déchiffrement")
    class ContenuIllisible {

        @Test
        @DisplayName("données chiffrées avec une autre clé : UnreadableCiphertextException")
        void decrypt_cleDifferente_leveUnreadableCiphertextException() {
            String ciphertext = CryptoService.encrypt("secret");
            // Rotation de clé : on déchiffre avec une clé qui n'est pas celle utilisée pour
            // le chiffrement.
            System.setProperty(KEY_PROPERTY, "HxsaGhwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc=");

            assertThrows(CryptoService.UnreadableCiphertextException.class,
                    () -> CryptoService.decrypt(ciphertext));
        }

        @Test
        @DisplayName("altération d'un octet du chiffré : UnreadableCiphertextException (authentification GCM)")
        void decrypt_chiffreAltere_leveUnreadableCiphertextException() {
            byte[] ciphertext = CryptoService.encrypt("message confidentiel".getBytes(StandardCharsets.UTF_8));
            ciphertext[ciphertext.length - 1] ^= 0x01; // corruption du tag d'authentification

            assertThrows(CryptoService.UnreadableCiphertextException.class,
                    () -> CryptoService.decrypt(ciphertext));
        }

        @Test
        @DisplayName("nonce altéré : UnreadableCiphertextException")
        void decrypt_nonceAltere_leveUnreadableCiphertextException() {
            byte[] ciphertext = CryptoService.encrypt("message".getBytes(StandardCharsets.UTF_8));
            ciphertext[0] ^= 0xFF; // corruption du nonce

            assertThrows(CryptoService.UnreadableCiphertextException.class,
                    () -> CryptoService.decrypt(ciphertext));
        }

        @Test
        @DisplayName("ciphertext trop court pour contenir un nonce : BufferUnderflowException")
        void decrypt_ciphertextTropCourt_leveBufferUnderflowException() {
            // 5 octets < 12 octets de nonce : la lecture du ByteBuffer échoue avant le déchiffrement.
            // Le BufferUnderflowException n'est ni converti ni enveloppé : il remonte tel quel.
            assertThrows(java.nio.BufferUnderflowException.class, () -> CryptoService.decrypt(new byte[5]));
        }

        @Test
        @DisplayName("chaîne qui n'est pas du Base64 : IllegalArgumentException")
        void decrypt_chaineNonBase64_leveIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> CryptoService.decrypt("@@pas-du-base64@@"));
        }

        @Test
        @DisplayName("UnreadableCiphertextException expose la cause technique")
        void unreadableCiphertextException_conserveLaCause() {
            String ciphertext = CryptoService.encrypt("secret");
            System.setProperty(KEY_PROPERTY, "HxsaGhwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc=");

            var exception = assertThrows(CryptoService.UnreadableCiphertextException.class,
                    () -> CryptoService.decrypt(ciphertext));

            assertNotNull(exception.getCause(), "La cause technique doit être conservée pour le diagnostic");
        }
    }
}
