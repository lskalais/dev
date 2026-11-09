package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "listing_financial_diagnoses",
        uniqueConstraints = @UniqueConstraint(name = "uk_listing_financial_diagnosis_listing",
                columnNames = "listing_id"))
public class ListingFinancialDiagnosis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "listing_id", nullable = false)
    public Long listingId;

    @Column(length = 2)
    public String countryCode;

    @Column(length = 3, nullable = false)
    public String currencyCode = "EUR";

    @Column(precision = 16, scale = 2)
    public BigDecimal netIncome;

    @Column(precision = 16, scale = 2)
    public BigDecimal totalAssets;

    @Column(precision = 16, scale = 2)
    public BigDecimal totalDebts;

    @Column(precision = 16, scale = 2)
    public BigDecimal fixedAssets;

    @Column(columnDefinition = "TEXT")
    public String financialOutlook;

    @Column(precision = 5, scale = 2)
    public BigDecimal valuationMultiple;

    public Long valuationValidatedBy;

    public Instant valuationValidatedAt;
}
