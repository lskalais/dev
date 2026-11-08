package fr.alp.confidentia.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires de la logique portée par l'entité {@link DealRequest} : la règle
 * « NDA signé » exige les deux signatures (acheteur et cédant).
 */
@DisplayName("DealRequest - état de signature du NDA")
class DealRequestTest {

    @Test
    @DisplayName("isNdaSigned : aucune signature -> false")
    void isNdaSigned_aucuneSignature_retourneFalse() {
        DealRequest request = new DealRequest();

        assertFalse(request.isNdaSigned());
    }

    @Test
    @DisplayName("isNdaSigned : seule la signature acheteur -> false (signature partielle)")
    void isNdaSigned_seulementAcheteur_retourneFalse() {
        DealRequest request = new DealRequest();
        request.buyerSignatureName = "Acheteur";
        request.buyerSignedAt = Instant.now();

        assertFalse(request.isNdaSigned(), "Une signature unique ne suffit pas");
    }

    @Test
    @DisplayName("isNdaSigned : seule la signature cédant -> false")
    void isNdaSigned_seulementCedant_retourneFalse() {
        DealRequest request = new DealRequest();
        request.sellerSignatureName = "Cédant";
        request.sellerSignedAt = Instant.now();

        assertFalse(request.isNdaSigned(), "Une signature unique ne suffit pas");
    }

    @Test
    @DisplayName("isNdaSigned : les deux signatures -> true")
    void isNdaSigned_lesDeuxSignatures_retourneTrue() {
        DealRequest request = new DealRequest();
        request.buyerSignedAt = Instant.now();
        request.sellerSignedAt = Instant.now();

        assertTrue(request.isNdaSigned());
    }

    @Test
    @DisplayName("isNdaSigned : un nom de signature sans horodatage ne compte pas")
    void isNdaSigned_nomsSansHorodatage_retourneFalse() {
        // Contrat : seul l'horodatage fait foi (un nom seul peut provenir d'une saisie non validée).
        DealRequest request = new DealRequest();
        request.buyerSignatureName = "Acheteur";
        request.sellerSignatureName = "Cédant";

        assertFalse(request.isNdaSigned());
    }
}
