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

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    public BuyerType buyerType;

    @Column(length = 40)
    public String phone;

    @Column(length = 100)
    public String nationality;

    @Column(length = 120)
    public String residence;

    @Column(length = 160)
    public String profession;

    @Column(precision = 16, scale = 2)
    public java.math.BigDecimal indicativeFinancialCapacity;

    @Column(length = 100)
    public String legalForm;

    @Column(length = 100)
    public String country;

    @Column(length = 2)
    public String countryCode;

    @Column(length = 3)
    public String financialCapacityCurrencyCode = "XOF";

    @Column(length = 120)
    public String registrationNumber;

    @Column(length = 120)
    public String taxIdentifier;

    @Column(length = 240)
    public String headOffice;

    @Column(length = 120)
    public String representativeFunction;

    @Column(length = 500)
    public String sectors;

    @Column(length = 120)
    public String preferredRegion;

    @Column(precision = 16, scale = 2)
    public java.math.BigDecimal minInvestment;

    @Column(precision = 16, scale = 2)
    public java.math.BigDecimal maxInvestment;

    @Column(length = 3)
    public String investmentCurrencyCode = "EUR";
}
