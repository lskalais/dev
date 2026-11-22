package fr.alp.confidentia.service;

import fr.alp.confidentia.model.PasswordResetToken;
import fr.alp.confidentia.model.UserAccount;
import fr.alp.confidentia.persistence.Jpa;
import jakarta.enterprise.context.ApplicationScoped;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Logger;

/**
 * Réinitialisation de mot de passe par lien à usage unique envoyé par courriel.
 * <p>
 * Le jeton en clair n'est jamais stocké : seule son empreinte SHA-256 est
 * persistée. La demande
 * réussit toujours côté interface, qu'un compte existe ou non, pour ne pas
 * révéler les adresses
 * inscrites. L'envoi effectif nécessite SMTP et l'URL publique
 * {@code APP_BASE_URL}.
 */
@ApplicationScoped
public class PasswordResetService {
    private static final Logger LOGGER = Logger.getLogger(PasswordResetService.class.getName());
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration TOKEN_LIFETIME = Duration.ofHours(2);

    public record ResetRequestResult(boolean accountFound, boolean emailSent, String resetLink) {
    }

    public ResetRequestResult requestReset(String email) {
        String normalized = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("Saisissez votre adresse courriel.");
        }
        String token = newToken();
        String tokenHash = hash(token);
        String recipientEmail = Jpa.transaction(em -> {
            List<UserAccount> accounts = em.createQuery(
                    "select u from UserAccount u where u.email = :email", UserAccount.class)
                    .setParameter("email", normalized).getResultList();
            if (accounts.isEmpty()) {
                LOGGER.info("Demande de réinitialisation pour une adresse inconnue");
                return null;
            }
            // Un seul jeton actif à la fois : les précédents sont invalidés.
            jakarta.persistence.Query invalidation = em.createQuery(
                    "delete from PasswordResetToken t where t.userId = :id and t.usedAt is null");
            if (invalidation != null) {
                invalidation.setParameter("id", accounts.get(0).id).executeUpdate();
            }
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.userId = accounts.get(0).id;
            resetToken.tokenHash = tokenHash;
            resetToken.createdAt = Instant.now();
            resetToken.expiresAt = Instant.now().plus(TOKEN_LIFETIME);
            em.persist(resetToken);
            return accounts.get(0).email;
        });
        if (recipientEmail == null) {
            return new ResetRequestResult(false, false, null);
        }
        String baseUrl = NotificationEmailSender.baseUrl();
        String link = baseUrl == null ? null : baseUrl + "/reset-password.xhtml?token=" + token;
        boolean sent = false;
        if (link != null && NotificationEmailSender.isConfigured()) {
            try {
                NotificationEmailSender.send(recipientEmail, "Réinitialisation de votre mot de passe",
                        "Un changement de mot de passe a été demandé pour votre compte Africa Legacy Market.\n\n"
                                + "Ouvrez ce lien pour choisir un nouveau mot de passe (valable "
                                + TOKEN_LIFETIME.toHours() + " heures) :\n" + link
                                + "\n\nSi vous n'êtes pas à l'origine de cette demande, ignorez ce message.");
                sent = true;
            } catch (IllegalStateException exception) {
                LOGGER.log(java.util.logging.Level.WARNING,
                        "Échec de l'envoi du courriel de réinitialisation", exception);
            }
        } else {
            LOGGER.warning("Réinitialisation de mot de passe demandée sans envoi de courriel : "
                    + "SMTP et/ou APP_BASE_URL ne sont pas configurés.");
        }
        // Le lien n'est renvoyé à l'interface que lorsque aucun courriel n'a pu partir,
        // afin que
        // l'exploitant puisse le communiquer sans écrire de secret dans les journaux.
        return new ResetRequestResult(true, sent, sent ? null : link);
    }

    /** Vérifie le jeton avant affichage du formulaire ; ne le consomme pas. */
    public boolean isValidToken(String token) {
        String tokenHash = hashOrNull(token);
        if (tokenHash == null) {
            return false;
        }
        return Jpa.transaction(em -> {
            List<PasswordResetToken> matches = em.createQuery(
                    "select t from PasswordResetToken t where t.tokenHash = :hash", PasswordResetToken.class)
                    .setParameter("hash", tokenHash).getResultList();
            Instant now = Instant.now();
            return matches.stream().anyMatch(t -> t.usedAt == null && t.expiresAt.isAfter(now));
        });
    }

    public void resetPassword(String token, String newPassword, String confirmedPassword) {
        if (newPassword == null || !newPassword.equals(confirmedPassword)) {
            throw new IllegalArgumentException("Les deux nouveaux mots de passe ne correspondent pas.");
        }
        validatePassword(newPassword);
        String tokenHash = hashOrNull(token);
        if (tokenHash == null) {
            throw new IllegalArgumentException("Ce lien de réinitialisation n'est plus valable.");
        }
        Jpa.transaction(em -> {
            List<PasswordResetToken> matches = em.createQuery(
                    "select t from PasswordResetToken t where t.tokenHash = :hash", PasswordResetToken.class)
                    .setParameter("hash", tokenHash).getResultList();
            Instant now = Instant.now();
            PasswordResetToken valid = matches.stream()
                    .filter(t -> t.usedAt == null && t.expiresAt.isAfter(now))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Ce lien de réinitialisation n'est plus valable. Demandez-en un nouveau."));
            UserAccount account = em.find(UserAccount.class, valid.userId);
            if (account == null) {
                throw new IllegalArgumentException("Ce lien de réinitialisation n'est plus valable.");
            }
            account.passwordHash = PASSWORD_ENCODER.encode(newPassword);
            account.passwordChangeRequired = false;
            valid.usedAt = now;
            return null;
        });
    }

    /**
     * Même règle que pour les autres mots de passe : 12 caractères minimum, 72
     * octets UTF-8 maximum.
     */
    static void validatePassword(String password) {
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException(
                    "Le mot de passe doit contenir au moins 12 caractères et au plus 72 octets UTF-8.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException(
                    "Le mot de passe doit contenir au moins 12 caractères et au plus 72 octets UTF-8.");
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashOrNull(String token) {
        return token == null || token.isBlank() ? null : hash(token.trim());
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponible", exception);
        }
    }
}
