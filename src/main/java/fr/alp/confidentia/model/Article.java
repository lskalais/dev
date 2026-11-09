package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "articles")
public class Article {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 180)
    public String title;

    @Column(nullable = false, length = 80)
    public String category;

    @Column(nullable = false, length = 500)
    public String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(nullable = false, length = 20)
    public String status;

    @Column(nullable = false)
    public Long createdBy;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    public Instant publishedAt;
}
