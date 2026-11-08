package fr.alp.confidentia.model;

import jakarta.persistence.*;

@Entity
@Table(name = "buyer_profiles")
public class BuyerProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    public Long userId;

    @Column(length = 500)
    public String sectors;

    @Column(length = 120)
    public String preferredRegion;

    @Column(precision = 16, scale = 2)
    public java.math.BigDecimal minInvestment;

    @Column(precision = 16, scale = 2)
    public java.math.BigDecimal maxInvestment;
}
