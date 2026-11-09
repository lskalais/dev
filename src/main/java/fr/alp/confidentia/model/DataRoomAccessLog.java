package fr.alp.confidentia.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "data_room_access_logs", indexes = {
        @Index(name = "idx_data_room_log_request_time", columnList = "request_id, accessed_at")
})
public class DataRoomAccessLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "document_id", nullable = false)
    public Long documentId;

    @Column(name = "request_id", nullable = false)
    public Long requestId;

    @Column(name = "accessed_by", nullable = false)
    public Long accessedBy;

    @Column(name = "accessed_by_email", nullable = false, length = 320)
    public String accessedByEmail;

    @Column(name = "accessed_at", nullable = false)
    public Instant accessedAt;
}
