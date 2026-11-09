package fr.alp.confidentia.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "user_notifications", indexes =
        @Index(name = "idx_notifications_recipient_created", columnList = "recipient_id, created_at"))
public class UserNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "recipient_id", nullable = false)
    public Long recipientId;

    @Column(nullable = false, length = 160)
    public String title;

    @Column(nullable = false, length = 500)
    public String body;

    @Column(nullable = false, length = 40)
    public String emailDeliveryStatus;

    @Column(nullable = false)
    public Instant createdAt;

    public Instant readAt;
}
