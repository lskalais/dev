package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "contact_messages")
public class ContactMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 1200)
    public String senderNameEncrypted;

    @Column(nullable = false, length = 1500)
    public String senderEmailEncrypted;

    @Column(length = 500)
    public String senderPhoneEncrypted;

    @Column(nullable = false, length = 20)
    public String type;

    @Column(nullable = false, length = 1500)
    public String subjectEncrypted;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String messageTextEncrypted;

    @Column(nullable = false, length = 20)
    public String status;

    @Column(length = 3000)
    public String adminNote;

    public Long handledBy;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;
}
