package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "partner_experts")
public class PartnerExpert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 180)
    public String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public PartnerExpertCategory category;

    @Column(nullable = false, length = 100)
    public String country;

    @Column(length = 160)
    public String location;

    @Column(columnDefinition = "TEXT")
    public String summary;

    @Column(length = 254)
    public String contactEmail;

    @Column(length = 40)
    public String phone;

    @Column(length = 300)
    public String website;

    @Column(nullable = false)
    public boolean active;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;
}
