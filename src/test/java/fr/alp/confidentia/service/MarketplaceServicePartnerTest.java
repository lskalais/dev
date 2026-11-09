package fr.alp.confidentia.service;

import fr.alp.confidentia.model.BuyerKyc;
import fr.alp.confidentia.model.BuyerKycStatus;
import fr.alp.confidentia.model.PartnerExpert;
import fr.alp.confidentia.model.PartnerExpertCategory;
import fr.alp.confidentia.service.ViewModels.PartnerExpertView;
import fr.alp.confidentia.service.ViewModels.UserView;
import fr.alp.confidentia.testsupport.MockJpa;
import fr.alp.confidentia.testsupport.TestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@DisplayName("MarketplaceService - annuaire des partenaires")
class MarketplaceServicePartnerTest {
    private final MarketplaceService service = new MarketplaceService();
    private MockJpa jpa;
    private final UserView administrator = new UserView(1L, "admin@alp.test", "Admin", "ALP", "SUPER", false);
    private final UserView buyer = new UserView(2L, "buyer@alp.test", "Acheteur", null, "BUYER", false);

    @BeforeEach
    void setUp() {
        TestSupport.configureEncryptionKey();
        jpa = new MockJpa();
    }

    @AfterEach
    void tearDown() {
        jpa.close();
        TestSupport.clearEncryptionKey();
    }

    private EntityManager em() {
        return jpa.em;
    }

    @Test
    @DisplayName("annuaire : acquéreur validé consulte les partenaires actifs")
    void partnerExpertsFor_acquereurValide_retourneLesFichesActives() {
        BuyerKyc kyc = new BuyerKyc();
        kyc.userId = buyer.id();
        kyc.status = BuyerKycStatus.VALIDATED;
        PartnerExpert partner = new PartnerExpert();
        partner.id = 7L;
        partner.name = "Cabinet Exemple";
        partner.category = PartnerExpertCategory.ACCOUNTANT;
        partner.country = "Côte d'Ivoire";
        partner.active = true;
        MockJpa.query(em(), "select k from BuyerKyc k where k.userId = :id", List.of(kyc));
        MockJpa.query(em(), "select p from PartnerExpert p where p.active = true order by p.category, p.name",
                List.of(partner));

        List<PartnerExpertView> partners = service.partnerExpertsFor(buyer);

        assertEquals(1, partners.size());
        assertEquals("Cabinet Exemple", partners.get(0).name());
        assertEquals("Expert-comptable", partners.get(0).categoryLabel());
    }

    @Test
    @DisplayName("annuaire : acquéreur non validé n'accède pas aux coordonnées")
    void partnerExpertsFor_acquereurNonValide_refuse() {
        assertThrows(SecurityException.class, () -> service.partnerExpertsFor(buyer));
    }

    @Test
    @DisplayName("administration : création d'une fiche partenaire valide")
    void savePartnerExpert_administrateur_creeLaFiche() {
        PartnerExpertView result = service.savePartnerExpert(administrator, null, "Cabinet Exemple",
                PartnerExpertCategory.ACCOUNTANT, "Côte d'Ivoire", "Abidjan", "Conseil aux PME",
                "contact@example.com", "+22501020304", "https://example.com", true);

        assertEquals("Cabinet Exemple", result.name());
        assertTrue(result.active());
        verify(em()).persist(org.mockito.ArgumentMatchers.argThat(entity ->
                entity instanceof PartnerExpert partner
                        && "Cabinet Exemple".equals(partner.name)
                        && partner.createdAt != null && partner.updatedAt != null));
    }

    @Test
    @DisplayName("administration : site web non sûr refusé")
    void savePartnerExpert_siteNonHttpRefuse() {
        assertThrows(IllegalArgumentException.class, () -> service.savePartnerExpert(administrator, null,
                "Cabinet Exemple", PartnerExpertCategory.ACCOUNTANT, "Côte d'Ivoire", null, null,
                null, null, "javascript:alert(1)", true));
    }
}
