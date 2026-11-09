package fr.alp.confidentia.service;

import fr.alp.confidentia.model.Listing;
import fr.alp.confidentia.model.BuyerKyc;
import fr.alp.confidentia.model.BuyerKycStatus;
import fr.alp.confidentia.model.BuyerProfile;
import fr.alp.confidentia.model.BuyerType;
import fr.alp.confidentia.model.SecureDocument;
import fr.alp.confidentia.model.SellerVerification;
import fr.alp.confidentia.model.SellerVerificationStatus;
import fr.alp.confidentia.model.UserAccount;
import fr.alp.confidentia.service.ViewModels.BuyerRegistration;
import fr.alp.confidentia.service.ViewModels.UserView;
import fr.alp.confidentia.testsupport.MockJpa;
import fr.alp.confidentia.testsupport.TestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests unitaires de {@link MarketplaceService} portant sur les règles
 * transverses : administration des comptes, critères acheteur et validations.
 *
 * <p>
 * Le service est testé sans base de données : {@link MockJpa} remplace
 * {@code Jpa.transaction} par une exécution directe du travail métier sur un
 * {@link EntityManager} Mockito.
 * </p>
 */
@DisplayName("MarketplaceService - comptes et validations")
class MarketplaceServiceTest {

    private static final String COUNTER_QUERY = "select count(u) from UserAccount u where u.email = :email";

    private final MarketplaceService service = new MarketplaceService();
    private MockJpa jpa;

    private final UserView superUser = TestSupport.superUser(1L);
    private final UserView seller = TestSupport.seller(10L);
    private final UserView buyer = TestSupport.buyer(20L);
    private final UserView broker = TestSupport.broker(30L);

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

    private UserAccount account(Long id, String passwordHash, boolean passwordChangeRequired) {
        UserAccount account = MockJpa.newEntity(UserAccount.class);
        account.id = id;
        account.email = "u" + id + "@alp.fr";
        account.fullName = "Utilisateur " + id;
        account.passwordHash = passwordHash;
        account.role = fr.alp.confidentia.model.Role.BUYER;
        account.passwordChangeRequired = passwordChangeRequired;
        return account;
    }

    // =============================================================================================
    // isSuperUser / requireRole
    // =============================================================================================

    @Nested
    @DisplayName("isSuperUser")
    class SuperUserCheck {

        @Test
        @DisplayName("rôle SUPER -> true")
        void isSuperUser_roleSuper_retourneVrai() {
            assertTrue(service.isSuperUser(superUser));
        }

        @Test
        @DisplayName("autre rôle -> false")
        void isSuperUser_autreRole_retourneFaux() {
            assertEquals(false, service.isSuperUser(seller));
        }

        @Test
        @DisplayName("utilisateur null -> false")
        void isSuperUser_null_retourneFaux() {
            assertEquals(false, service.isSuperUser(null));
        }
    }

    // =============================================================================================
    // register / createUser
    // =============================================================================================

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("rôle SUPER -> refusé")
        void register_roleSuper_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.register("alice@alp.fr", TestSupport.VALID_PASSWORD, "Alice", "ALP",
                            fr.alp.confidentia.model.Role.SUPER));

            assertTrue(exception.getMessage().contains("SUPER"));
        }

        @Test
        @DisplayName("mot de passe trop court -> refusé")
        void register_motDePasseCourt_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.register("alice@alp.fr", "court", "Alice", "ALP",
                            fr.alp.confidentia.model.Role.SELLER));
        }

        @Test
        @DisplayName("nom blanc -> refusé")
        void register_nomBlanc_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.register("alice@alp.fr", TestSupport.VALID_PASSWORD, "   ", "ALP",
                            fr.alp.confidentia.model.Role.SELLER));

            assertTrue(exception.getMessage().contains("nom"));
        }

        @Test
        @DisplayName("e-mail invalide -> refusé")
        void register_emailInvalide_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.register("pas-un-email", TestSupport.VALID_PASSWORD, "Alice", "ALP",
                            fr.alp.confidentia.model.Role.SELLER));

            assertEquals("Saisissez une adresse courriel valide.", exception.getMessage());
        }

        @Test
        @DisplayName("e-mail déjà utilisé -> refusé")
        void register_emailDejaUtilise_refuse() {
            MockJpa.query(em(), COUNTER_QUERY, List.of(1L));

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.register("alice@alp.fr", TestSupport.VALID_PASSWORD, "Alice", "ALP",
                            fr.alp.confidentia.model.Role.SELLER));

            assertEquals("Cette adresse courriel est déjà utilisée.", exception.getMessage());
            verify(em(), never()).persist(any());
        }

        @Test
        @DisplayName("cas nominal cédant -> compte persisté, e-mail normalisé")
        void register_casNominal_persisteLeCompte() {
            MockJpa.query(em(), COUNTER_QUERY, List.of(0L));
            org.mockito.ArgumentCaptor<UserAccount> captor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);

            UserView view = service.register("  Alice@ALP.fr ", TestSupport.VALID_PASSWORD, "  Alice  ", "  ALP  ",
                    fr.alp.confidentia.model.Role.SELLER);

            verify(em()).persist(captor.capture());
            UserAccount saved = captor.getValue();
            assertEquals("alice@alp.fr", saved.email);
            assertEquals("Alice", saved.fullName);
            assertEquals("ALP", saved.organization);
            assertEquals(fr.alp.confidentia.model.Role.SELLER, saved.role);
            assertEquals(false, saved.passwordChangeRequired);
            assertTrue(saved.passwordHash.startsWith("$2"), "Le mot de passe doit être haché en BCrypt");
            assertNotNull(saved.passwordHash);
            assertEquals("SELLER", view.role());
            assertNotNull(view.id(), "L'identifiant généré par persist() doit être exposé");
        }

        @Test
        @DisplayName("inscription acheteur -> profil acheteur créé")
        void register_acheteur_creeLeProfil() {
            MockJpa.query(em(), COUNTER_QUERY, List.of(0L));

            service.register("bob@alp.fr", TestSupport.VALID_PASSWORD, "Bob", null,
                    fr.alp.confidentia.model.Role.BUYER);

            verify(em()).persist(any(fr.alp.confidentia.model.UserAccount.class));
            verify(em()).persist(any(fr.alp.confidentia.model.BuyerProfile.class));
        }

        @Test
        @DisplayName("inscription personne physique -> coordonnées et critères enregistrés")
        void register_acheteurPersonnePhysique_persisteSonProfil() {
            MockJpa.query(em(), COUNTER_QUERY, List.of(0L));
            BuyerRegistration registration = new BuyerRegistration(BuyerType.INDIVIDUAL,
                    "+2250102030405", "Ivoirienne", "Côte d’Ivoire", "Entrepreneure",
                    new BigDecimal("250000000"), "Agriculture, industrie", null, null,
                    null, null, null, null);
            org.mockito.ArgumentCaptor<fr.alp.confidentia.model.BuyerProfile> captor =
                    org.mockito.ArgumentCaptor.forClass(fr.alp.confidentia.model.BuyerProfile.class);

            service.register("alice@alp.fr", TestSupport.VALID_PASSWORD, "Alice", null,
                    fr.alp.confidentia.model.Role.BUYER, registration);

            verify(em()).persist(captor.capture());
            fr.alp.confidentia.model.BuyerProfile profile = captor.getValue();
            assertEquals(BuyerType.INDIVIDUAL, profile.buyerType);
            assertEquals("+2250102030405", profile.phone);
            assertEquals("Ivoirienne", profile.nationality);
            assertEquals("Côte d’Ivoire", profile.residence);
            assertEquals("Entrepreneure", profile.profession);
            assertEquals(new BigDecimal("250000000"), profile.indicativeFinancialCapacity);
            assertEquals("Agriculture, industrie", profile.sectors);
        }

        @Test
        @DisplayName("inscription personne morale -> représentant et immatriculation enregistrés")
        void register_acheteurPersonneMorale_persisteSonProfil() {
            MockJpa.query(em(), COUNTER_QUERY, List.of(0L));
            BuyerRegistration registration = new BuyerRegistration(BuyerType.COMPANY,
                    "+225 0102030405", null, null, null, null, "Industrie", "SARL", "Côte d’Ivoire",
                    "CI-ABJ-2026-B-12345", "1234567A", "Abidjan, Plateau", "Directrice générale");
            org.mockito.ArgumentCaptor<fr.alp.confidentia.model.BuyerProfile> captor =
                    org.mockito.ArgumentCaptor.forClass(fr.alp.confidentia.model.BuyerProfile.class);
            org.mockito.ArgumentCaptor<UserAccount> accountCaptor =
                    org.mockito.ArgumentCaptor.forClass(UserAccount.class);

            service.register("contact@entreprise.ci", TestSupport.VALID_PASSWORD, "Awa Koné",
                    "Entreprise Exemple SA", fr.alp.confidentia.model.Role.BUYER, registration);

            verify(em()).persist(accountCaptor.capture());
            verify(em()).persist(captor.capture());
            assertEquals("Entreprise Exemple SA", accountCaptor.getValue().organization);
            fr.alp.confidentia.model.BuyerProfile profile = captor.getValue();
            assertEquals(BuyerType.COMPANY, profile.buyerType);
            assertEquals("+225 0102030405", profile.phone);
            assertEquals("SARL", profile.legalForm);
            assertEquals("Côte d’Ivoire", profile.country);
            assertEquals("CI-ABJ-2026-B-12345", profile.registrationNumber);
            assertEquals("1234567A", profile.taxIdentifier);
            assertEquals("Abidjan, Plateau", profile.headOffice);
            assertEquals("Directrice générale", profile.representativeFunction);
        }

        @Test
        @DisplayName("inscription personne physique sans téléphone -> refusée avant création du compte")
        void register_acheteurPersonnePhysique_sansTelephoneRefuse() {
            BuyerRegistration registration = new BuyerRegistration(BuyerType.INDIVIDUAL,
                    " ", "Ivoirienne", "Côte d’Ivoire", "Entrepreneure", null,
                    null, null, null, null, null, null, null);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.register("alice@alp.fr", TestSupport.VALID_PASSWORD, "Alice", null,
                            fr.alp.confidentia.model.Role.BUYER, registration));

            assertEquals("Le téléphone est obligatoire.", exception.getMessage());
            verify(em(), never()).persist(any());
        }

        @Test
        @DisplayName("inscription personne morale sans dénomination -> refusée")
        void register_acheteurPersonneMorale_sansDenominationRefuse() {
            BuyerRegistration registration = new BuyerRegistration(BuyerType.COMPANY,
                    null, null, null, null, null, null, "SARL", "Côte d’Ivoire",
                    "CI-123", "123", "Abidjan", "Gérante");

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.register("contact@entreprise.ci", TestSupport.VALID_PASSWORD,
                            "Awa Koné", " ", fr.alp.confidentia.model.Role.BUYER, registration));

            assertEquals("La dénomination sociale est obligatoire.", exception.getMessage());
            verify(em(), never()).persist(any());
        }
    }

    @Nested
    @DisplayName("Vérification des comptes cédants")
    class SellerVerificationWorkflow {

        private static final String STATUS_QUERY =
                "select v from SellerVerification v where v.userId = :id";
        private static final String DOCUMENT_COUNT_QUERY =
                "select count(d) from SecureDocument d where d.documentPurpose = 'SELLER_VERIFICATION' "
                        + "and d.uploadedBy = :id";
        private static final String DOCUMENTS_QUERY =
                "select d from SecureDocument d where d.documentPurpose = 'SELLER_VERIFICATION' "
                        + "and d.uploadedBy = :id order by d.uploadedAt desc";
        private static final String SELLERS_QUERY =
                "select u from UserAccount u where u.role = :role order by u.createdAt desc, u.id desc";
        private static final String VERIFICATIONS_QUERY = "select v from SellerVerification v";
        private static final String ALL_VERIFICATION_DOCUMENTS_QUERY =
                "select d from SecureDocument d where d.documentPurpose = 'SELLER_VERIFICATION' "
                        + "order by d.uploadedAt desc";

        private SellerVerification verification(SellerVerificationStatus status) {
            SellerVerification verification = MockJpa.newEntity(SellerVerification.class);
            verification.userId = seller.id();
            verification.status = status;
            verification.updatedAt = java.time.Instant.parse("2026-11-01T10:00:00Z");
            MockJpa.query(em(), STATUS_QUERY, List.of(verification));
            UserAccount account = account(seller.id(), "hash", false);
            account.role = fr.alp.confidentia.model.Role.SELLER;
            doReturn(account).when(em()).find(UserAccount.class, seller.id());
            MockJpa.query(em(), DOCUMENT_COUNT_QUERY, List.of(1L));
            return verification;
        }

        @Test
        @DisplayName("inscription cédant -> statut initial documents en attente")
        void register_cedant_creeLeSuiviDocumentsEnAttente() {
            MockJpa.query(em(), COUNTER_QUERY, List.of(0L));
            org.mockito.ArgumentCaptor<SellerVerification> captor =
                    org.mockito.ArgumentCaptor.forClass(SellerVerification.class);

            service.register("vendeur@alp.fr", TestSupport.VALID_PASSWORD, "Vendeur", null,
                    fr.alp.confidentia.model.Role.SELLER);

            verify(em()).persist(captor.capture());
            assertNotNull(captor.getValue().userId);
            assertEquals(SellerVerificationStatus.DOCUMENTS_PENDING, captor.getValue().status);
            assertNotNull(captor.getValue().updatedAt);
        }

        @Test
        @DisplayName("un acquéreur ne peut pas modifier le workflow")
        void updateSellerVerification_nonReviewer_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.updateSellerVerification(buyer, seller.id(), "UNDER_REVIEW", null));
        }

        @Test
        @DisplayName("courtier : accès aux pièces et demande de correction autorisés")
        void broker_consulteLesPiecesEtDemandeUneCorrection() {
            SellerVerification verification = verification(SellerVerificationStatus.UNDER_REVIEW);
            SecureDocument document = verificationDocument();
            MockJpa.query(em(), DOCUMENTS_QUERY, List.of(document));

            var documents = service.sellerVerificationDocuments(broker, seller.id());
            service.updateSellerVerification(broker, seller.id(), "NEEDS_CORRECTION",
                    "Merci de compléter le mandat.");

            assertEquals(1, documents.size());
            assertEquals("RCCM", documents.get(0).verificationType());
            assertEquals(SellerVerificationStatus.NEEDS_CORRECTION, verification.status);
            assertEquals("Merci de compléter le mandat.", verification.feedback);
            assertEquals(broker.id(), verification.updatedBy);
        }

        @Test
        @DisplayName("administrateur : accès aux pièces justificatives autorisé")
        void superUser_consulteLesPiecesDuCedant() {
            MockJpa.query(em(), DOCUMENTS_QUERY, List.of(verificationDocument()));

            var documents = service.sellerVerificationDocuments(superUser, seller.id());

            assertEquals(1, documents.size());
            assertEquals("RCCM", documents.get(0).verificationType());
        }

        @Test
        @DisplayName("file de revue : les pièces du cédant sont incluses dans le dossier")
        void sellerVerificationQueue_inclutLesPiecesDuCedant() {
            UserAccount sellerAccount = account(seller.id(), "hash", false);
            sellerAccount.role = fr.alp.confidentia.model.Role.SELLER;
            MockJpa.query(em(), SELLERS_QUERY, List.of(sellerAccount));
            MockJpa.query(em(), VERIFICATIONS_QUERY, List.of());
            MockJpa.query(em(), ALL_VERIFICATION_DOCUMENTS_QUERY, List.of(verificationDocument()));

            var queue = service.sellerVerificationQueue(broker);

            assertEquals(1, queue.size());
            assertEquals(seller.id(), queue.get(0).sellerId());
            assertEquals(1, queue.get(0).documents().size());
            assertEquals("RCCM", queue.get(0).documents().get(0).verificationType());
        }

        @Test
        @DisplayName("acquéreur : consultation des pièces justificatives interdite")
        void sellerVerificationDocuments_nonReviewer_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.sellerVerificationDocuments(buyer, seller.id()));
        }

        private SecureDocument verificationDocument() {
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.uploadedBy = seller.id();
            document.documentPurpose = "SELLER_VERIFICATION";
            document.verificationType = "RCCM";
            document.originalName = fr.alp.confidentia.security.CryptoService.encrypt("rccm.pdf");
            document.contentType = "application/pdf";
            document.sizeBytes = 10;
            document.uploadedAt = java.time.Instant.parse("2026-11-01T10:00:00Z");
            return document;
        }

        @Test
        @DisplayName("démarrer une vérification sans pièce -> refusé")
        void updateSellerVerification_sansPieceRefuse() {
            verification(SellerVerificationStatus.DOCUMENTS_PENDING);
            MockJpa.query(em(), DOCUMENT_COUNT_QUERY, List.of(0L));

            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> service.updateSellerVerification(superUser, seller.id(), "UNDER_REVIEW", null))
                    .getMessage().contains("au moins une pièce"));
        }

        @Test
        @DisplayName("workflow autorisé : attente, vérification, pièces validées, compte validé")
        void updateSellerVerification_transitionsAutorisees() {
            SellerVerification verification = verification(SellerVerificationStatus.DOCUMENTS_PENDING);

            service.updateSellerVerification(superUser, seller.id(), "UNDER_REVIEW", null);
            assertEquals(SellerVerificationStatus.UNDER_REVIEW, verification.status);
            assertEquals(superUser.id(), verification.updatedBy);

            service.updateSellerVerification(superUser, seller.id(), "DOCUMENTS_VALIDATED", null);
            assertEquals(SellerVerificationStatus.DOCUMENTS_VALIDATED, verification.status);

            service.updateSellerVerification(superUser, seller.id(), "ACCOUNT_VALIDATED", null);
            assertEquals(SellerVerificationStatus.ACCOUNT_VALIDATED, verification.status);
            assertNotNull(verification.updatedAt);
        }

        @Test
        @DisplayName("demande de complément sans motif -> refusée")
        void updateSellerVerification_demandeCorrectionSansMotifRefusee() {
            SellerVerification verification = verification(SellerVerificationStatus.UNDER_REVIEW);

            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> service.updateSellerVerification(superUser, seller.id(), "NEEDS_CORRECTION", " "))
                    .getMessage().contains("Précisez"));
            assertEquals(SellerVerificationStatus.UNDER_REVIEW, verification.status);
        }

        @Test
        @DisplayName("étape incohérente -> refusée")
        void updateSellerVerification_transitionIncoherenteRefusee() {
            SellerVerification verification = verification(SellerVerificationStatus.DOCUMENTS_PENDING);

            assertThrows(IllegalArgumentException.class,
                    () -> service.updateSellerVerification(superUser, seller.id(), "ACCOUNT_VALIDATED", null));
            assertEquals(SellerVerificationStatus.DOCUMENTS_PENDING, verification.status);
        }
    }

    // =============================================================================================
    // Administration
    // =============================================================================================

    @Nested
    @DisplayName("Administration")
    class Administration {

        private static final String LIST_QUERY = "select u from UserAccount u order by u.createdAt desc, u.id desc";
        private static final String BUYER_PROFILES_QUERY =
                "select p from BuyerProfile p where p.userId in :ids";
        private static final String BUYER_KYC_QUERY =
                "select k from BuyerKyc k where k.userId in :ids";

        @Test
        @DisplayName("usersForAdministration : non super utilisateur -> SecurityException")
        void usersForAdministration_nonSuper_refuse() {
            assertThrows(SecurityException.class, () -> service.usersForAdministration(seller));
        }

        @Test
        @DisplayName("adminDashboardFor : non super utilisateur -> SecurityException")
        void adminDashboardFor_nonSuper_refuse() {
            assertThrows(SecurityException.class, () -> service.adminDashboardFor(seller));
            verify(em(), never()).createQuery(anyString());
        }

        @Test
        @DisplayName("adminDashboardFor : super utilisateur -> indicateurs agrégés")
        void adminDashboardFor_superRetourneLesIndicateurs() {
            jakarta.persistence.Query countQuery = mock(jakarta.persistence.Query.class);
            doReturn(1L).when(countQuery).getSingleResult();
            doReturn(countQuery).when(countQuery).setParameter(anyString(), any());
            doReturn(countQuery).when(em()).createQuery(anyString());

            var dashboard = service.adminDashboardFor(superUser);

            assertEquals(1L, dashboard.totalUsers());
            assertEquals(1L, dashboard.buyerKycToReview());
            assertEquals(1L, dashboard.sellerVerificationsToReview());
            assertEquals(1L, dashboard.publishedListings());
            assertEquals(1L, dashboard.suspendedListings());
            assertEquals(1L, dashboard.activeDealRequests());
            assertEquals(1L, dashboard.newContactMessages());
        }

        @Test
        @DisplayName("usersForAdministration : super utilisateur -> comptes mappés")
        void usersForAdministration_super_retourneLesComptes() {
            MockJpa.query(em(), LIST_QUERY, List.of(account(5L, "hash", true)));

            var users = service.usersForAdministration(superUser);

            assertEquals(1, users.size());
            assertEquals(5L, users.get(0).id());
            assertTrue(users.get(0).passwordChangeRequired());
        }

        @Test
        @DisplayName("usersForAdministration : expose le statut KYC sans les documents sensibles")
        void usersForAdministration_super_includesBuyerKycStatus() {
            UserAccount companyAccount = account(5L, "hash", false);
            companyAccount.organization = "Entreprise Exemple";
            UserAccount individualAccount = account(6L, "hash", false);
            MockJpa.query(em(), LIST_QUERY, List.of(companyAccount, individualAccount));

            BuyerProfile companyProfile = MockJpa.newEntity(BuyerProfile.class);
            companyProfile.userId = 5L;
            companyProfile.buyerType = BuyerType.COMPANY;
            BuyerProfile individualProfile = MockJpa.newEntity(BuyerProfile.class);
            individualProfile.userId = 6L;
            individualProfile.buyerType = BuyerType.INDIVIDUAL;
            MockJpa.query(em(), BUYER_PROFILES_QUERY, List.of(companyProfile, individualProfile));

            BuyerKyc companyKyc = MockJpa.newEntity(BuyerKyc.class);
            companyKyc.userId = 5L;
            companyKyc.status = BuyerKycStatus.NEEDS_CORRECTION;
            companyKyc.updatedAt = Instant.parse("2026-11-08T10:00:00Z");
            MockJpa.query(em(), BUYER_KYC_QUERY, List.of(companyKyc));

            var users = service.usersForAdministration(superUser);

            assertEquals(2, users.size());
            assertEquals(BuyerType.COMPANY, users.get(0).buyerType());
            assertEquals(BuyerKycStatus.NEEDS_CORRECTION, users.get(0).buyerKycStatus());
            assertEquals(companyKyc.updatedAt, users.get(0).buyerKycUpdatedAt());
            assertEquals(BuyerType.INDIVIDUAL, users.get(1).buyerType());
            assertEquals(BuyerKycStatus.DOCUMENTS_PENDING, users.get(1).buyerKycStatus());
            assertNull(users.get(1).buyerKycUpdatedAt());
        }

        @Test
        @DisplayName("createManagedUser : rôle manquant -> refusé")
        void createManagedUser_roleManquant_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.createManagedUser(superUser, "bob@alp.fr", TestSupport.VALID_PASSWORD,
                            "Bob", "ALP", null));

            assertTrue(exception.getMessage().contains("rôle"));
        }

        @Test
        @DisplayName("createManagedUser : courtier sans organisation -> refusé")
        void createManagedUser_courtierSansOrganisation_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.createManagedUser(superUser, "bob@alp.fr", TestSupport.VALID_PASSWORD,
                            "Bob", "  ", fr.alp.confidentia.model.Role.BROKER));

            assertTrue(exception.getMessage().contains("organisation"));
        }

        @Test
        @DisplayName("createManagedUser : non super utilisateur -> SecurityException")
        void createManagedUser_nonSuper_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.createManagedUser(seller, "bob@alp.fr", TestSupport.VALID_PASSWORD,
                            "Bob", "ALP", fr.alp.confidentia.model.Role.SELLER));
        }

        @Test
        @DisplayName("createManagedUser : compte créé avec changement de mot de passe exigé")
        void createManagedUser_casNominal_exigeLeChangementDeMotDePasse() {
            MockJpa.query(em(), COUNTER_QUERY, List.of(0L));
            org.mockito.ArgumentCaptor<UserAccount> captor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);

            service.createManagedUser(superUser, "bob@alp.fr", TestSupport.VALID_PASSWORD, "Bob", "Cabinet",
                    fr.alp.confidentia.model.Role.BROKER);

            verify(em()).persist(captor.capture());
            assertTrue(captor.getValue().passwordChangeRequired);
        }

        @Test
        @DisplayName("assignTemporaryPassword : utilisateur introuvable -> refusé")
        void assignTemporaryPassword_utilisateurInconnu_refuse() {
            doReturn(null).when(em()).find(UserAccount.class, 99L);

            assertEquals("Utilisateur introuvable.",
                    assertThrows(IllegalArgumentException.class,
                            () -> service.assignTemporaryPassword(superUser, 99L, TestSupport.VALID_PASSWORD))
                            .getMessage());
        }

        @Test
        @DisplayName("assignTemporaryPassword : mot de passe temporaire enregistré")
        void assignTemporaryPassword_casNominal_enregistreLeHash() {
            UserAccount target = account(5L, "ancien", false);
            doReturn(target).when(em()).find(UserAccount.class, 5L);

            service.assignTemporaryPassword(superUser, 5L, TestSupport.VALID_PASSWORD);

            assertTrue(target.passwordChangeRequired);
            assertTrue(target.passwordHash.startsWith("$2"));
            assertEquals(false, "ancien".equals(target.passwordHash));
        }

        @Test
        @DisplayName("changeTemporaryPassword : aucun changement requis -> SecurityException")
        void changeTemporaryPassword_nonRequis_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.changeTemporaryPassword(buyer, TestSupport.VALID_PASSWORD));
        }

        @Test
        @DisplayName("changeTemporaryPassword : même mot de passe -> refusé")
        void changeTemporaryPassword_memeMotDePasse_refuse() {
            String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode(TestSupport.VALID_PASSWORD);
            UserAccount stored = account(20L, hash, true);
            doReturn(stored).when(em()).find(UserAccount.class, 20L);
            UserView temporary = new UserView(20L, "bob@alp.fr", "Bob", "ALP", "BUYER", true);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.changeTemporaryPassword(temporary, TestSupport.VALID_PASSWORD));

            assertTrue(exception.getMessage().contains("différent"));
        }

        @Test
        @DisplayName("changeTemporaryPassword : nouveau mot de passe -> exigence levée")
        void changeTemporaryPassword_nouveauMotDePasse_leveLExigence() {
            String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode(TestSupport.VALID_PASSWORD);
            UserAccount stored = account(20L, hash, true);
            doReturn(stored).when(em()).find(UserAccount.class, 20L);
            UserView temporary = new UserView(20L, "bob@alp.fr", "Bob", "ALP", "BUYER", true);

            UserView updated = service.changeTemporaryPassword(temporary, "NouveauMotDePasse!2026");

            assertEquals(false, stored.passwordChangeRequired);
            assertEquals(false, updated.passwordChangeRequired());
        }
    }

    // =============================================================================================
    // authenticate
    // =============================================================================================

    @Nested
    @DisplayName("authenticate")
    class Authenticate {

        private static final String AUTH_QUERY = "select u from UserAccount u where u.email = :email";

        @Test
        @DisplayName("utilisateur inconnu -> refusé")
        void authenticate_inconnu_refuse() {
            MockJpa.query(em(), AUTH_QUERY, List.of());

            assertEquals("Adresse courriel ou mot de passe invalide.",
                    assertThrows(IllegalArgumentException.class,
                            () -> service.authenticate("bob@alp.fr", TestSupport.VALID_PASSWORD))
                            .getMessage());
        }

        @Test
        @DisplayName("mot de passe erroné -> refusé")
        void authenticate_motDePasseErrone_refuse() {
            String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode(TestSupport.VALID_PASSWORD);
            MockJpa.query(em(), AUTH_QUERY, List.of(account(20L, hash, false)));

            assertThrows(IllegalArgumentException.class,
                    () -> service.authenticate("bob@alp.fr", "MauvaisMotDePasse!2026"));
        }

        @Test
        @DisplayName("identifiants corrects -> vue utilisateur")
        void authenticate_identifiantsCorrects_retourneLaVue() {
            String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode(TestSupport.VALID_PASSWORD);
            MockJpa.query(em(), AUTH_QUERY, List.of(account(20L, hash, false)));

            UserView view = service.authenticate("  Bob@ALP.fr ", TestSupport.VALID_PASSWORD);

            assertEquals(20L, view.id());
            assertEquals("u20@alp.fr", view.email());
            assertEquals("BUYER", view.role());
        }
    }

    // =============================================================================================
    // saveBuyerCriteria
    // =============================================================================================

    @Nested
    @DisplayName("saveBuyerCriteria")
    class BuyerCriteria {

        private static final String PROFILE_QUERY = "select p from BuyerProfile p where p.userId = :userId";

        @Test
        @DisplayName("rôle non acheteur -> SecurityException")
        void saveBuyerCriteria_nonAcheteur_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.saveBuyerCriteria(seller, "Agro", "Abidjan", null, null));
        }

        @Test
        @DisplayName("minimum supérieur au maximum -> refusé")
        void saveBuyerCriteria_budgetIncoherent_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.saveBuyerCriteria(buyer, null, null,
                            new BigDecimal("900"), new BigDecimal("100")));

            assertTrue(exception.getMessage().contains("minimum"));
        }

        @Test
        @DisplayName("budget négatif -> refusé")
        void saveBuyerCriteria_budgetNegatif_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.saveBuyerCriteria(buyer, null, null, new BigDecimal("-1"), null));

            assertTrue(exception.getMessage().contains("positif"));
        }

        @Test
        @DisplayName("secteurs trop longs -> refusés")
        void saveBuyerCriteria_secteursTropLongs_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.saveBuyerCriteria(buyer, "s".repeat(501), null, null, null));

            assertTrue(exception.getMessage().contains("Secteurs recherchés"));
        }

        @Test
        @DisplayName("critères valides -> profil mis à jour et champs vides normalisés à null")
        void saveBuyerCriteria_casNominal_metAJourLeProfil() {
            fr.alp.confidentia.model.BuyerProfile profile = MockJpa
                    .newEntity(fr.alp.confidentia.model.BuyerProfile.class);
            MockJpa.query(em(), PROFILE_QUERY, List.of(profile));

            service.saveBuyerCriteria(buyer, "  Agro, Tech  ", "  ", new BigDecimal("100"), new BigDecimal("900"));

            assertEquals("Agro, Tech", profile.sectors);
            assertNull(profile.preferredRegion);
            assertEquals(new BigDecimal("100"), profile.minInvestment);
            assertEquals(new BigDecimal("900"), profile.maxInvestment);
        }
    }

    // =============================================================================================
    // Public / filtres simples
    // =============================================================================================

    @Nested
    @DisplayName("Consultations publiques")
    class PublicWeb {

        @Test
        @DisplayName("publicListings : s'appuie sur les requêtes de Jpa.transaction")
        void publicListings_litLesAnnoncesPubliees() {
            // La requête n'est pas déclarée : le repli de MockJpa renvoie une liste vide.
            assertTrue(service.publicListings().isEmpty());
        }

        @Test
        @DisplayName("recordMarketplaceViews : non acheteur -> SecurityException")
        void recordMarketplaceViews_nonAcheteur_refuse() {
            assertThrows(SecurityException.class, () -> service.recordMarketplaceViews(seller));
        }

        @Test
        @DisplayName("processFinancials : non courtier -> SecurityException")
        void processFinancials_nonCourtier_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.processFinancials(seller, 5L, null, null, BigDecimal.ONE));
        }

        @Test
        @DisplayName("acceptListing : non courtier -> SecurityException")
        void acceptListing_nonCourtier_refuse() {
            assertThrows(SecurityException.class, () -> service.acceptListing(seller, 5L));
        }

        @Test
        @DisplayName("publicListings : identité masquée pour les visiteurs")
        void publicListings_masqueLIdentite() {
            Listing listing = MockJpa.newEntity(Listing.class);
            listing.id = 5L;
            listing.status = "PUBLISHED";
            MockJpa.query(em(),
                    "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                    List.of(listing));

            var views = service.publicListings();

            assertEquals(1, views.size());
            List<String> publicFields = java.util.Arrays.stream(views.get(0).getClass().getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertFalse(publicFields.contains("companyName"));
            assertFalse(publicFields.contains("exactLocation"));
            assertFalse(publicFields.contains("askingPrice"));
        }

        @Test
        @DisplayName("listingsFor (administrateur) : voit toutes les annonces")
        void listingsFor_administrateurVoitToutesLesAnnonces() {
            Listing listing = MockJpa.newEntity(Listing.class);
            listing.id = 5L;
            listing.status = "SUSPENDED";
            MockJpa.query(em(), "select l from Listing l order by l.createdAt desc", List.of(listing));

            assertEquals(1, service.listingsFor(superUser).size());
        }

        @Test
        @DisplayName("publication : l'administrateur suspend puis republie une annonce")
        void setListingPublished_administrateurSuspendEtPublie() {
            Listing listing = MockJpa.newEntity(Listing.class);
            listing.id = 5L;
            listing.status = "BROKER_ACCEPTED";
            listing.brokerId = 10L;
            doReturn(listing).when(em()).find(Listing.class, 5L);

            service.setListingPublished(superUser, 5L, false);
            assertEquals("SUSPENDED", listing.status);

            service.setListingPublished(superUser, 5L, true);
            assertEquals("BROKER_ACCEPTED", listing.status);
        }

        @Test
        @DisplayName("publication : un courtier non mandaté ne peut pas suspendre")
        void setListingPublished_courtierNonMandateRefuse() {
            Listing listing = MockJpa.newEntity(Listing.class);
            listing.id = 5L;
            listing.status = "PUBLISHED";
            listing.brokerId = 10L;
            doReturn(listing).when(em()).find(Listing.class, 5L);

            assertThrows(SecurityException.class,
                    () -> service.setListingPublished(broker, 5L, false));
            assertEquals("PUBLISHED", listing.status);
        }
    }
}
