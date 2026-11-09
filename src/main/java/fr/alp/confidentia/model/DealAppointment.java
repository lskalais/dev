package fr.alp.confidentia.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "deal_appointments")
public class DealAppointment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "request_id", nullable = false)
    public Long requestId;

    @Column(name = "proposed_by", nullable = false)
    public Long proposedBy;

    @Column(name = "appointment_at", nullable = false)
    public Instant appointmentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public AppointmentStatus status;

    @Column(nullable = false)
    public Instant createdAt;
}
