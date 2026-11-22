package fr.alp.confidentia.service;

import fr.alp.confidentia.model.PasswordResetToken;
import fr.alp.confidentia.model.Role;
import fr.alp.confidentia.model.UserAccount;
import fr.alp.confidentia.testsupport.MockJpa;
import fr.alp.confidentia.testsupport.TestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@DisplayName("PasswordResetService - réinitialisation de mot de passe par lien")
class PasswordResetServiceTest {
    private static final String TOKEN_QUERY =
            "select t from PasswordResetToken t where t.tokenHash = :hash";
    private static final String ACCOUNT_EMAIL_QUERY =
            "select u from UserAccount u where u.email = :email";
    private static final String NEW_PASSWORD = "NouveauMotDePasse!2026";

    private final PasswordResetService service = new PasswordResetService();
    private MockJpa jpa;

    @BeforeEach
    void setUp() {
        TestSupport.configureEncryptionKey();
        System.clearProperty("SMTP_HOST");
        System.clearProperty("SMTP_FROM");
        System.clearProperty("APP_BASE_URL");
        jpa = new MockJpa();
    }

    @AfterEach
    void tearDown() {
        jpa.close();
        System.clearProperty("APP_BASE_URL");
        TestSupport.clearEncryptionKey();
    }

    private EntityManager em() {
        return jpa.em;
    }

    private UserAccount account(String email) {
        UserAccount account = MockJpa.newEntity(UserAccount.class);
        account.id = 42L;
        account.email = email;
        account.fullName = "Utilisateur";
        account.role = Role.BUYER;
        account.passwordHash = "ancien-hash";
        account.passwordChangeRequired = true;
        return account;
    }

    private PasswordResetToken token(long userId, Instant expiresAt) {
        PasswordResetToken token = MockJpa.newEntity(PasswordResetToken.class);
        token.id = 7L;
        token.userId = userId;
        token.createdAt = Instant.now();
        token.expiresAt = expiresAt;
        return token;
    }

    @Test
    @DisplayName("demande : adresse inconnue → réponse identique, aucun jeton créé")
    void requestReset_unknownEmail_noToken() {
        MockJpa.query(em(), ACCOUNT_EMAIL_QUERY, List.of());

        var result = service.requestReset("inconnu@alp.local");

        assertFalse(result.accountFound());
        assertFalse(result.emailSent());
    }

    @Test
    @DisplayName("demande : compte connu sans SMTP → jeton persisté, lien renvoyé à l'interface")
    void requestReset_knownEmail_withoutSmtp_returnsLink() {
        UserAccount account = account("acheteur@alp.local");
        MockJpa.query(em(), ACCOUNT_EMAIL_QUERY, List.of(account));
        System.setProperty("APP_BASE_URL", "https://market.alp.local/");

        var result = service.requestReset("  Acheteur@ALP.local ");

        assertTrue(result.accountFound());
        assertFalse(result.emailSent());
        assertNotNull(result.resetLink());
        assertTrue(result.resetLink().startsWith("https://market.alp.local/reset-password.xhtml?token="));
        verify(em()).persist(isA(PasswordResetToken.class));
    }

    @Test
    @DisplayName("jeton inconnu ou vide → invalide")
    void isValidToken_unknown_invalid() {
        assertFalse(service.isValidToken(null));
        assertFalse(service.isValidToken(" "));
        assertFalse(service.isValidToken("jeton-inexistant"));
    }

    @Test
    @DisplayName("jeton expiré ou déjà utilisé → invalide")
    void isValidToken_expiredOrUsed_invalid() {
        PasswordResetToken expired = token(42L, Instant.now().minusSeconds(60));
        String hash = PasswordResetService.hash("jeton-expire");
        expired.tokenHash = hash;
        MockJpa.query(em(), TOKEN_QUERY, List.of(expired));
        assertFalse(service.isValidToken("jeton-expire"));

        PasswordResetToken used = token(42L, Instant.now().plusSeconds(600));
        used.tokenHash = PasswordResetService.hash("jeton-utilise");
        used.usedAt = Instant.now();
        MockJpa.query(em(), TOKEN_QUERY, List.of(used));
        assertFalse(service.isValidToken("jeton-utilise"));
    }

    @Test
    @DisplayName("jeton valide → mot de passe remplacé et jeton consommé")
    void resetPassword_valid_replacesPasswordAndConsumesToken() {
        PasswordResetToken valid = token(42L, Instant.now().plusSeconds(600));
        valid.tokenHash = PasswordResetService.hash("jeton-valide");
        MockJpa.query(em(), TOKEN_QUERY, List.of(valid));
        UserAccount account = account("acheteur@alp.local");
        account.passwordChangeRequired = true;
        doReturn(account).when(em()).find(UserAccount.class, 42L);

        service.resetPassword("jeton-valide", NEW_PASSWORD, NEW_PASSWORD);

        assertTrue(new BCryptPasswordEncoder().matches(NEW_PASSWORD, account.passwordHash));
        assertFalse(account.passwordChangeRequired);
        assertNotNull(valid.usedAt);
    }

    @Test
    @DisplayName("confirmation différente → refusé")
    void resetPassword_mismatch_refused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("jeton", NEW_PASSWORD, NEW_PASSWORD + "x"));
    }

    @Test
    @DisplayName("mot de passe trop court ou trop long → refusé")
    void resetPassword_invalidPassword_refused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("jeton", "court", "court"));
        assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("jeton", "é".repeat(40), "é".repeat(40)));
    }

    @Test
    @DisplayName("jeton expiré à l'usage → refusé")
    void resetPassword_expiredToken_refused() {
        PasswordResetToken expired = token(42L, Instant.now().minusSeconds(1));
        expired.tokenHash = PasswordResetService.hash("jeton-expire");
        MockJpa.query(em(), TOKEN_QUERY, List.of(expired));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("jeton-expire", NEW_PASSWORD, NEW_PASSWORD));

        assertEquals("Ce lien de réinitialisation n'est plus valable. Demandez-en un nouveau.",
                exception.getMessage());
    }
}
