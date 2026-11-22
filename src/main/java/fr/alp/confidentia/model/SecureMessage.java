package fr.alp.confidentia.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "secure_messages")
public class SecureMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long requestId;

    @Column(nullable = false)
    public Long senderId;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    public String encryptedBody;

    @Column(nullable = false)
    public Instant createdAt;

    /**
     * Renseigné lorsque l'équipe ALP écrit dans le dossier : le message n'est alors pas un échange
     * entre les parties et reste visible des courtiers et administrateurs (supervision ALP).
     */
    @Column(name = "alp_team", nullable = false)
    public boolean alpTeam;
}
