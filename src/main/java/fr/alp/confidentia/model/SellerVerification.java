package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "seller_verifications",
        uniqueConstraints = @UniqueConstraint(name = "uk_seller_verifications_user", columnNames = "user_id"))
public class SellerVerification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public SellerVerificationStatus status;

    @Column(length = 1000)
    public String feedback;

    @Column(name = "updated_by")
    public Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
