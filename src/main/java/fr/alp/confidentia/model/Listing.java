package fr.alp.confidentia.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "listings")
public class Listing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long sellerId;

    public Long brokerId;

    @Column(length = 30)
    public String sectorCategory;

    @Column(length = 1000)
    public String activityDomains;

    @Column(nullable = false, length = 120)
    public String sector;

    @Column(length = 120)
    public String secondarySector;

    public Integer foundedYear;

    public Integer approximateEmployeeCount;

    @Column(nullable = false)
    public boolean publicRevenueRangeAllowed;

    @Column(nullable = false, length = 4000)
    public String description;

    @Column(nullable = false, length = 120)
    public String region;

    @Column(length = 2000)
    public String customerSummary;

    @Column(length = 2000)
    public String advantages;

    @Column(length = 1000)
    public String saleReason;

    @Column(precision = 16, scale = 2)
    public BigDecimal revenue;

    @Column(precision = 16, scale = 2)
    public BigDecimal ebitda;

    @Column(precision = 16, scale = 2)
    public BigDecimal askingPrice;

    @Column(precision = 7, scale = 2)
    public BigDecimal profitMargin;

    @Column(length = 1000)
    public String saleConditions;

    @Column(length = 500)
    public String supportPeriod;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    public ListingTransactionType transactionType = ListingTransactionType.FULL_SALE;

    @Column(length = 2000)
    public String companyNameEncrypted;

    @Column(length = 2000)
    public String exactLocationEncrypted;

    @Column(nullable = false, length = 40)
    public String status;

    @Column(nullable = false)
    public long views;

    @Column(nullable = false)
    public Instant createdAt;
}
