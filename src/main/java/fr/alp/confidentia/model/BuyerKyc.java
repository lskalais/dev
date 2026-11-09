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
@Table(name = "buyer_kyc",
        uniqueConstraints = @UniqueConstraint(name = "uk_buyer_kyc_user", columnNames = "user_id"))
public class BuyerKyc {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public BuyerKycStatus status;

    @Column(name = "email_verified", nullable = false)
    public boolean emailVerified;

    @Column(name = "phone_verified", nullable = false)
    public boolean phoneVerified;

    @Column(name = "source_of_funds_required", nullable = false)
    public boolean sourceOfFundsRequired;

    @Column(name = "beneficial_owners_required", nullable = false)
    public boolean beneficialOwnersRequired;

    @Column(length = 1000)
    public String feedback;

    @Column(name = "updated_by")
    public Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
