package fr.alp.confidentia.model;

import jakarta.persistence.*;

@Entity
@Table(name = "buyer_favorites", uniqueConstraints =
        @UniqueConstraint(name = "uk_buyer_favorite_listing", columnNames = {"buyer_id", "listing_id"}))
public class BuyerFavorite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "buyer_id", nullable = false)
    public Long buyerId;

    @Column(name = "listing_id", nullable = false)
    public Long listingId;
}
