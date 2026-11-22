package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Jeton de réinitialisation de mot de passe. Seul l'empreinte SHA-256 du jeton est stockée :
 * le jeton en clair n'existe que dans le lien envoyé par courriel.
 */
@Entity
@Table(name = "password_reset_tokens", indexes =
        @Index(name = "idx_password_reset_user_created", columnList = "user_id, created_at"))
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    public String tokenHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    /** Renseigné dès que le jeton a servi : un jeton est à usage unique. */
    @Column(name = "used_at")
    public Instant usedAt;
}
