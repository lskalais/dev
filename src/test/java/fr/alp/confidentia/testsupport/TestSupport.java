package fr.alp.confidentia.testsupport;

import fr.alp.confidentia.service.ViewModels.UserView;

/**
 * Fabriques d'objets et helpers de configuration partagés par les tests.
 */
public final class TestSupport {

    public static final String ENCRYPTION_KEY_PROPERTY = "APP_ENCRYPTION_KEY";

    /** Clé AES-256 valide, encodée en Base64 (32 octets). */
    public static final String VALID_ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** Mot de passe conforme : au moins 12 caractères et au plus 72 octets UTF-8. */
    public static final String VALID_PASSWORD = "MotDePasse!2026";

    private static int idSequence = 100;

    private TestSupport() {
    }

    public static Long nextId() {
        return (long) idSequence++;
    }

    /**
     * Force la clé de chiffrement via les propriétés système (lues par
     * {@code AppConfiguration} avant les variables d'environnement).
     */
    public static void configureEncryptionKey() {
        System.setProperty(ENCRYPTION_KEY_PROPERTY, VALID_ENCRYPTION_KEY);
    }

    public static void clearEncryptionKey() {
        System.clearProperty(ENCRYPTION_KEY_PROPERTY);
    }

    /** Utilisateur de test avec l'identifiant et le nom de rôle fournis. */
    public static UserView user(Long id, String role) {
        return new UserView(id, role.toLowerCase() + "@alp.fr", "Nom " + role, "Organisation",
                role, false);
    }

    public static UserView seller(Long id) {
        return user(id, "SELLER");
    }

    public static UserView buyer(Long id) {
        return user(id, "BUYER");
    }

    public static UserView broker(Long id) {
        return user(id, "BROKER");
    }

    public static UserView superUser(Long id) {
        return user(id, "SUPER");
    }
}
