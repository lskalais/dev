package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "listing_sale_process_history")
public class ListingSaleProcessHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "listing_id", nullable = false)
    public Long listingId;

    @Column(name = "from_stage", length = 40)
    public String fromStage;

    @Column(name = "to_stage", nullable = false, length = 40)
    public String toStage;

    public Long changedBy;

    @Column(nullable = false)
    public Instant changedAt;
}
