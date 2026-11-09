package fr.alp.confidentia.service;

import fr.alp.confidentia.model.BuyerKyc;
import fr.alp.confidentia.model.BuyerKycDocumentType;
import fr.alp.confidentia.model.BuyerKycStatus;
import fr.alp.confidentia.model.BuyerProfile;
import fr.alp.confidentia.model.BuyerType;
import fr.alp.confidentia.model.SecureDocument;
import fr.alp.confidentia.model.UserAccount;
import fr.alp.confidentia.service.ViewModels.BuyerRegistration;
import fr.alp.confidentia.service.ViewModels.UserView;
import fr.alp.confidentia.testsupport.MockJpa;
import fr.alp.confidentia.testsupport.TestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MarketplaceService - KYC des acquéreurs personnes physiques et morales")
class BuyerKycServiceTest {
    private static final String PROFILE_QUERY = "select p from BuyerProfile p where p.userId = :id";
    private static final String KYC_QUERY = "select k from BuyerKyc k where k.userId = :id";
    private static final String BUYER_PROFILES_QUERY =
            "select p from BuyerProfile p where p.buyerType is not null order by p.userId desc";
    private static final String ACCOUNTS_QUERY = "select u from UserAccount u where u.id in :ids";
    private static final String KYC_LIST_QUERY = "select k from BuyerKyc k where k.userId in :ids";
    private static final String DOCUMENTS_QUERY =
            "select d from SecureDocument d where d.documentPurpose = 'BUYER_KYC' "
                    + "and d.uploadedBy in :ids order by d.uploadedAt desc";

    private final MarketplaceService service = new MarketplaceService();
    private final UserView buyer = TestSupport.buyer(20L);
    private final UserView broker = TestSupport.broker(30L);
    private final UserView superUser = TestSupport.superUser(1L);
    private MockJpa jpa;
    private String previousStorageDirectory;

    @TempDir
    private Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        TestSupport.configureEncryptionKey();
        previousStorageDirectory = System.getProperty("PRIVATE_STORAGE_DIR");
        System.setProperty("PRIVATE_STORAGE_DIR", temporaryDirectory.toString());
        jpa = new MockJpa();
    }

    @AfterEach
    void tearDown() {
        jpa.close();
        TestSupport.clearEncryptionKey();
        if (previousStorageDirectory == null) {
            System.clearProperty("PRIVATE_STORAGE_DIR");
        } else {
            System.setProperty("PRIVATE_STORAGE_DIR", previousStorageDirectory);
        }
    }

    private EntityManager em() {
        return jpa.em;
    }

    private BuyerProfile profile(BuyerType type) {
        BuyerProfile profile = MockJpa.newEntity(BuyerProfile.class);
        profile.userId = buyer.id();
        profile.buyerType = type;
        profile.phone = "+225 0102030405";
        profile.nationality = "Ivoirienne";
        profile.residence = "Côte d’Ivoire";
        profile.profession = "Entrepreneure";
        profile.legalForm = "SARL";
        profile.country = "Côte d’Ivoire";
        profile.registrationNumber = "CI-ABJ-2026-B-12345";
        profile.taxIdentifier = "1234567A";
        profile.headOffice = "Abidjan";
        profile.representativeFunction = "Gérante";
        return profile;
    }

    private BuyerKyc kyc(BuyerKycStatus status) {
        BuyerKyc kyc = MockJpa.newEntity(BuyerKyc.class);
        kyc.userId = buyer.id();
        kyc.status = status;
        kyc.updatedAt = Instant.parse("2026-11-01T10:00:00Z");
        MockJpa.query(em(), KYC_QUERY, List.of(kyc));
        return kyc;
    }

    private void stubProfile(BuyerProfile profile) {
        MockJpa.query(em(), PROFILE_QUERY, List.of(profile));
    }

    private void stubDocumentCount(BuyerKycDocumentType type, long count) {
        String query = "select count(d) from SecureDocument d where d.documentPurpose = 'BUYER_KYC' "
                + "and d.uploadedBy = :id and d.verificationType = '" + type.name() + "'";
        MockJpa.query(em(), query, List.of(count));
    }

    private void stubCompanyDocumentCounts(long count, boolean includeBeneficialOwners) {
        stubDocumentCount(BuyerKycDocumentType.COMPANY_REGISTRATION, count);
        stubDocumentCount(BuyerKycDocumentType.COMPANY_STATUTES, count);
        stubDocumentCount(BuyerKycDocumentType.LEGAL_REPRESENTATIVE_ID, count);
        stubDocumentCount(BuyerKycDocumentType.REPRESENTATION_AUTHORITY, count);
        if (includeBeneficialOwners) {
            stubDocumentCount(BuyerKycDocumentType.BENEFICIAL_OWNERS, count);
        }
    }

    @Test
    @DisplayName("l’inscription d’un acquéreur physique crée son dossier KYC en attente")
    void register_individualBuyer_createsPendingKyc() {
        MockJpa.query(em(), "select count(u) from UserAccount u where u.email = :email", List.of(0L));
        BuyerRegistration registration = new BuyerRegistration(BuyerType.INDIVIDUAL,
                "+2250102030405", "Ivoirienne", "Côte d’Ivoire", "Entrepreneure",
                null, null, null, null, null, null, null, null);

        service.register("alice@alp.fr", "MotDePasseSecurise123!", "Alice", null,
                fr.alp.confidentia.model.Role.BUYER, registration);

        org.mockito.ArgumentCaptor<BuyerKyc> captor = org.mockito.ArgumentCaptor.forClass(BuyerKyc.class);
        verify(em()).persist(captor.capture());
        assertEquals(BuyerKycStatus.DOCUMENTS_PENDING, captor.getValue().status);
        assertNotNull(captor.getValue().updatedAt);
    }

    @Test
    @DisplayName("l’inscription d’une personne morale crée son dossier KYC en attente")
    void register_companyBuyer_createsPendingKyc() {
        MockJpa.query(em(), "select count(u) from UserAccount u where u.email = :email", List.of(0L));
        BuyerRegistration registration = new BuyerRegistration(BuyerType.COMPANY, "+2250102030405",
                null, null, null, null, null, "SARL", "Côte d’Ivoire", "CI-ABJ-2026-B-12345",
                "1234567A", "Abidjan", "Gérante");

        service.register("societe@alp.fr", "MotDePasseSecurise123!", "Awa Koné",
                "Société ALP", fr.alp.confidentia.model.Role.BUYER, registration);

        org.mockito.ArgumentCaptor<BuyerKyc> captor = org.mockito.ArgumentCaptor.forClass(BuyerKyc.class);
        verify(em()).persist(captor.capture());
        assertEquals(BuyerKycStatus.DOCUMENTS_PENDING, captor.getValue().status);
    }

    @Test
    @DisplayName("le dossier KYC d’une personne morale expose ses informations légales")
    void buyerKycStatus_company_includesLegalInformation() {
        BuyerProfile profile = profile(BuyerType.COMPANY);
        stubProfile(profile);
        BuyerKyc kyc = kyc(BuyerKycStatus.DOCUMENTS_PENDING);
        MockJpa.query(em(), "select d from SecureDocument d where d.documentPurpose = 'BUYER_KYC' "
                + "and d.uploadedBy = :id order by d.uploadedAt desc", List.of());
        UserAccount account = MockJpa.newEntity(UserAccount.class);
        account.id = buyer.id();
        account.email = buyer.email();
        account.fullName = buyer.name();
        account.organization = "Société ALP";
        when(em().find(UserAccount.class, buyer.id())).thenReturn(account);

        var status = service.buyerKycStatus(buyer);

        assertEquals(BuyerType.COMPANY, status.buyerType());
        assertEquals("Société ALP", status.organization());
        assertEquals("SARL", status.legalForm());
        assertEquals(profile.registrationNumber, status.registrationNumber());
        assertEquals(kyc.status, status.status());
    }

    @Test
    @DisplayName("le courtier voit les dossiers KYC des personnes physiques et morales")
    void buyerKycQueue_includesIndividualAndCompanyBuyers() {
        BuyerProfile profile = profile(BuyerType.INDIVIDUAL);
        BuyerProfile companyProfile = profile(BuyerType.COMPANY);
        companyProfile.userId = 21L;
        MockJpa.query(em(), BUYER_PROFILES_QUERY, List.of(profile, companyProfile));
        UserAccount account = MockJpa.newEntity(UserAccount.class);
        account.id = buyer.id();
        account.email = buyer.email();
        account.fullName = buyer.name();
        UserAccount companyAccount = MockJpa.newEntity(UserAccount.class);
        companyAccount.id = 21L;
        companyAccount.email = "societe@alp.fr";
        companyAccount.fullName = "Awa Koné";
        companyAccount.organization = "Société ALP";
        MockJpa.query(em(), ACCOUNTS_QUERY, List.of(account, companyAccount));
        MockJpa.query(em(), KYC_LIST_QUERY, List.of());
        MockJpa.query(em(), DOCUMENTS_QUERY, List.of());

        var queue = service.buyerKycQueue(broker);

        assertEquals(2, queue.size());
        assertEquals(BuyerKycStatus.DOCUMENTS_PENDING, queue.get(0).status());
        assertEquals(buyer.email(), queue.get(0).email());
        assertEquals(profile.phone, queue.get(0).phone());
        assertFalse(queue.get(0).emailVerified());
        assertEquals(BuyerType.COMPANY, queue.get(1).buyerType());
        assertEquals("Société ALP", queue.get(1).organization());
    }

    @Test
    @DisplayName("le courtier ne peut valider sans pièce d’identité")
    void updateBuyerKyc_withoutIdentityDocument_rejected() {
        stubProfile(profile(BuyerType.INDIVIDUAL));
        BuyerKyc kyc = kyc(BuyerKycStatus.DOCUMENTS_PENDING);
        stubDocumentCount(BuyerKycDocumentType.IDENTITY_DOCUMENT, 0L);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateBuyerKyc(broker, buyer.id(), "UNDER_REVIEW", null,
                        false, false, false, false));
        assertEquals(BuyerKycStatus.DOCUMENTS_PENDING, kyc.status);
    }

    @Test
    @DisplayName("la validation exige la vérification manuelle du courriel et du téléphone")
    void updateBuyerKyc_requiresVerifiedContacts() {
        stubProfile(profile(BuyerType.INDIVIDUAL));
        BuyerKyc kyc = kyc(BuyerKycStatus.UNDER_REVIEW);
        stubDocumentCount(BuyerKycDocumentType.IDENTITY_DOCUMENT, 1L);
        stubDocumentCount(BuyerKycDocumentType.SOURCE_OF_FUNDS, 0L);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateBuyerKyc(superUser, buyer.id(), "VALIDATED", null,
                        true, false, false, false));
        assertEquals(BuyerKycStatus.UNDER_REVIEW, kyc.status);
    }

    @Test
    @DisplayName("le justificatif d’origine des fonds est exigé uniquement sur décision du réviseur")
    void updateBuyerKyc_requiresSourceOfFundsWhenFlagged() {
        stubProfile(profile(BuyerType.INDIVIDUAL));
        BuyerKyc kyc = kyc(BuyerKycStatus.UNDER_REVIEW);
        stubDocumentCount(BuyerKycDocumentType.IDENTITY_DOCUMENT, 1L);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateBuyerKyc(broker, buyer.id(), "VALIDATED", null,
                        true, true, true, false));
        assertEquals(BuyerKycStatus.UNDER_REVIEW, kyc.status);
    }

    @Test
    @DisplayName("la validation KYC est permise après vérification des coordonnées")
    void updateBuyerKyc_validatesWithVerifiedContacts() {
        stubProfile(profile(BuyerType.INDIVIDUAL));
        BuyerKyc kyc = kyc(BuyerKycStatus.UNDER_REVIEW);
        stubDocumentCount(BuyerKycDocumentType.IDENTITY_DOCUMENT, 1L);

        service.updateBuyerKyc(broker, buyer.id(), "VALIDATED", null, true, true, false, false);

        assertEquals(BuyerKycStatus.VALIDATED, kyc.status);
        assertEquals(broker.id(), kyc.updatedBy);
        assertNotNull(kyc.updatedAt);
    }

    @Test
    @DisplayName("la revue d’une société ne commence pas sans son dossier légal complet")
    void updateBuyerKyc_companyCannotStartWithoutRequiredDocuments() {
        stubProfile(profile(BuyerType.COMPANY));
        BuyerKyc kyc = kyc(BuyerKycStatus.DOCUMENTS_PENDING);
        stubCompanyDocumentCounts(1L, false);
        stubDocumentCount(BuyerKycDocumentType.COMPANY_STATUTES, 0L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.updateBuyerKyc(broker, buyer.id(), "UNDER_REVIEW", null,
                        false, false, false, false));

        assertEquals("La pièce requise doit être déposée : Statuts de la personne morale.",
                exception.getMessage());
        assertEquals(BuyerKycStatus.DOCUMENTS_PENDING, kyc.status);
    }

    @Test
    @DisplayName("la validation d’une société exige les bénéficiaires effectifs quand le réviseur le demande")
    void updateBuyerKyc_companyRequiresBeneficialOwnersWhenFlagged() {
        stubProfile(profile(BuyerType.COMPANY));
        BuyerKyc kyc = kyc(BuyerKycStatus.UNDER_REVIEW);
        stubCompanyDocumentCounts(1L, false);
        stubDocumentCount(BuyerKycDocumentType.BENEFICIAL_OWNERS, 0L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.updateBuyerKyc(broker, buyer.id(), "VALIDATED", null,
                        true, true, false, true));

        assertEquals("Les informations sur les bénéficiaires effectifs requises doivent être déposées avant la validation.",
                exception.getMessage());
        assertEquals(BuyerKycStatus.UNDER_REVIEW, kyc.status);
    }

    @Test
    @DisplayName("la personne morale est validée avec ses pièces légales sans exigence systématique de bénéficiaires")
    void updateBuyerKyc_companyValidatesWithRequiredDocuments() {
        stubProfile(profile(BuyerType.COMPANY));
        BuyerKyc kyc = kyc(BuyerKycStatus.UNDER_REVIEW);
        stubCompanyDocumentCounts(1L, false);

        service.updateBuyerKyc(broker, buyer.id(), "VALIDATED", null,
                true, true, false, false);

        assertEquals(BuyerKycStatus.VALIDATED, kyc.status);
        assertFalse(kyc.beneficialOwnersRequired);
    }

    @Test
    @DisplayName("seuls les courtiers et administrateurs peuvent consulter la file KYC")
    void buyerKycQueue_buyerDenied() {
        assertThrows(SecurityException.class, () -> service.buyerKycQueue(buyer));
    }

    @Test
    @DisplayName("un acquéreur personne physique peut déposer une pièce chiffrée")
    void storeBuyerKycDocument_storesIdentityForIndividual() throws Exception {
        stubProfile(profile(BuyerType.INDIVIDUAL));
        MockJpa.query(em(), KYC_QUERY, List.of());

        var document = service.storeBuyerKycDocument(buyer, "IDENTITY_DOCUMENT",
                "carte-identite.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertEquals(BuyerKycDocumentType.IDENTITY_DOCUMENT.name(), document.verificationType());
        verify(em()).persist(isA(SecureDocument.class));
        verify(em()).persist(isA(BuyerKyc.class));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    @DisplayName("un acquéreur physique ne peut pas déposer une pièce réservée aux sociétés")
    void storeBuyerKycDocument_individualCannotUploadCompanyDocument() throws Exception {
        stubProfile(profile(BuyerType.INDIVIDUAL));
        MockJpa.query(em(), KYC_QUERY, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.storeBuyerKycDocument(buyer, "COMPANY_STATUTES",
                        "statuts.pdf", "application/pdf", new byte[]{1, 2, 3}));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0L, files.count());
        }
    }

    @Test
    @DisplayName("une personne morale peut déposer ses statuts de façon chiffrée")
    void storeBuyerKycDocument_companyStoresStatutes() throws Exception {
        stubProfile(profile(BuyerType.COMPANY));
        MockJpa.query(em(), KYC_QUERY, List.of());

        var document = service.storeBuyerKycDocument(buyer, "COMPANY_STATUTES",
                "statuts.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertEquals(BuyerKycDocumentType.COMPANY_STATUTES.name(), document.verificationType());
        verify(em()).persist(isA(SecureDocument.class));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count());
        }
    }
}
