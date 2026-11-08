package fr.alp.confidentia.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "secure_documents")
public class SecureDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "listing_id")
    public Long listingId;
    @Column(name = "request_id")
    public Long requestId;

    @Column(name = "uploaded_by", nullable = false)
    public Long uploadedBy;

    @Column(nullable = false, length = 1000)
    public String originalName;

    @Column(nullable = false, unique = true, length = 36)
    public String storageKey;

    @Column(nullable = false, length = 120)
    public String contentType;

    @Column(nullable = false)
    public long sizeBytes;

    @Column(nullable = false)
    public Instant uploadedAt;
}
