package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "listing_sale_processes",
        uniqueConstraints = @UniqueConstraint(name = "uk_listing_sale_process_listing", columnNames = "listing_id"))
public class ListingSaleProcess {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "listing_id", nullable = false)
    public Long listingId;

    @Column(nullable = false, length = 40)
    public String stage;

    public Long updatedBy;

    @Column(nullable = false)
    public Instant updatedAt;
}
