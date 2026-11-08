package fr.alp.confidentia.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "deal_requests", uniqueConstraints =
        @UniqueConstraint(name = "uk_deal_listing_buyer", columnNames = {"listing_id", "buyer_id"}))
public class DealRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "listing_id", nullable = false)
    public Long listingId;

    @Column(name = "buyer_id", nullable = false)
    public Long buyerId;

    @Column(nullable = false, length = 20)
    public String status;

    @Column(length = 30)
    public String ndaVersion;

    @Column(length = 160)
    public String buyerSignatureName;

    public Instant buyerSignedAt;

    @Column(length = 160)
    public String sellerSignatureName;

    public Instant sellerSignedAt;

    @Column(nullable = false)
    public Instant createdAt;

    public boolean isNdaSigned() {
        return buyerSignedAt != null && sellerSignedAt != null;
    }
}
