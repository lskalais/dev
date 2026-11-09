package fr.alp.confidentia.service;

import fr.alp.confidentia.model.DealRequest;
import fr.alp.confidentia.model.DealAppointment;
import fr.alp.confidentia.model.AppointmentStatus;
import fr.alp.confidentia.model.BuyerFavorite;
import fr.alp.confidentia.model.DataRoomAccessLog;
import fr.alp.confidentia.model.Listing;
import fr.alp.confidentia.model.ListingFinancialDiagnosis;
import fr.alp.confidentia.model.ListingSaleProcess;
import fr.alp.confidentia.model.ListingSaleProcessHistory;
import fr.alp.confidentia.model.SaleProcessStage;
import fr.alp.confidentia.model.ListingTransactionType;
import fr.alp.confidentia.model.BuyerKyc;
import fr.alp.confidentia.model.BuyerKycStatus;
import fr.alp.confidentia.model.Role;
import fr.alp.confidentia.model.SellerDocumentType;
import fr.alp.confidentia.model.SecureDocument;
import fr.alp.confidentia.model.UserAccount;
import fr.alp.confidentia.security.CryptoService;
import fr.alp.confidentia.service.ViewModels.DealView;
import fr.alp.confidentia.service.ViewModels.ListingEditView;
import fr.alp.confidentia.service.ViewModels.ListingSaveResult;
import fr.alp.confidentia.service.ViewModels.ListingSearchFilters;
import fr.alp.confidentia.service.ViewModels.ListingView;
import fr.alp.confidentia.service.ViewModels.PublicListingView;
import fr.alp.confidentia.service.ViewModels.UserView;
import fr.alp.confidentia.testsupport.MockJpa;
import fr.alp.confidentia.testsupport.TestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Tests unitaires de {@link MarketplaceService} pour les annonces, les demandes
 * de mise en
 * relation (NDA) et la confidentialité.
 *
 * <p>
 * Les dépendances (EntityManager, requêtes JPQL, chiffrement) sont des mocks
 * Mockito ;
 * seules les règles métier et de sécurité sont réellement exécutées.
 * </p>
 */
@DisplayName("MarketplaceService - annonces, NDA et confidentialité")
class MarketplaceServiceListingTest {

    private static final String NOT_AUTHORIZED = "Cette action n'est pas autorisée pour votre profil.";
    private static final String MANAGER_ONLY = "Accès réservé au cédant ou au courtier mandaté.";
    private static final String SELLER_ONLY = "Seul le cédant propriétaire peut modifier ce teaser.";
    private static final String UNAVAILABLE = "Donnée chiffrée indisponible : l'ancienne clé de chiffrement n'est plus disponible.";

    private final MarketplaceService service = new MarketplaceService();
    private MockJpa jpa;
    private MockedStatic<CryptoService> crypto;
    @TempDir
    private Path temporaryDirectory;

    private final UserView seller = new UserView(10L, "s@alp.fr", "Cédant", "ALP", "SELLER", false);
    private final UserView buyer = new UserView(20L, "b@alp.fr", "Acheteur", "ALP", "BUYER", false);
    private final UserView broker = new UserView(30L, "c@alp.fr", "Courtier", "Cabinet", "BROKER", false);
    private final UserView anotherBroker = new UserView(31L, "d@alp.fr", "Autre", "Cabinet", "BROKER", false);

    @BeforeEach
    void setUp() {
        TestSupport.configureEncryptionKey();
        crypto = mockStatic(CryptoService.class);
        crypto.when(() -> CryptoService.encrypt(anyString()))
                .thenAnswer(invocation -> "enc:" + invocation.getArgument(0, String.class));
        crypto.when(() -> CryptoService.encrypt(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenAnswer(invocation -> {
                    byte[] plain = invocation.getArgument(0, byte[].class);
                    byte[] result = new byte[plain.length];
                    for (int index = 0; index < plain.length; index++) {
                        result[index] = (byte) (plain[index] ^ 0x5A);
                    }
                    return result;
                });
        crypto.when(() -> CryptoService.decrypt(anyString()))
                .thenAnswer(invocation -> decrypt(invocation.getArgument(0, String.class)));
        crypto.when(() -> CryptoService.decrypt(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenAnswer(invocation -> {
                    byte[] cipher = invocation.getArgument(0, byte[].class);
                    byte[] result = new byte[cipher.length];
                    for (int index = 0; index < cipher.length; index++) {
                        result[index] = (byte) (cipher[index] ^ 0x5A);
                    }
                    return result;
                });
        jpa = new MockJpa();
    }

    @AfterEach
    void tearDown() {
        crypto.close();
        jpa.close();
        TestSupport.clearEncryptionKey();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private EntityManager em() {
        return jpa.em;
    }

    private static String decrypt(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("old:")) {
            throw MockJpa.unreadableCiphertext();
        }
        return value.startsWith("enc:") ? value.substring(4) : value;
    }

    private UserAccount account(Long id, Role role) {
        UserAccount account = MockJpa.newEntity(UserAccount.class);
        account.id = id;
        account.role = role;
        account.email = "u" + id + "@alp.fr";
        account.fullName = "Utilisateur " + id;
        account.createdAt = Instant.now();
        return account;
    }

    private Listing listing(Long id, Long sellerId, Long brokerId, String status) {
        Listing listing = MockJpa.newEntity(Listing.class);
        listing.id = id;
        listing.sellerId = sellerId;
        listing.brokerId = brokerId;
        listing.status = status;
        listing.sector = "Agroalimentaire";
        listing.secondarySector = "Distribution";
        listing.region = "Abidjan";
        listing.description = "Boulangerie rentable";
        listing.revenue = new BigDecimal("1000000");
        listing.ebitda = new BigDecimal("100000");
        listing.askingPrice = new BigDecimal("500000");
        listing.createdAt = Instant.now();
        listing.companyNameEncrypted = "enc:Société Secrète";
        listing.exactLocationEncrypted = "enc:Rue 12, Cocody";
        return listing;
    }

    private ListingFinancialDiagnosis diagnosis(Long id, Long listingId) {
        ListingFinancialDiagnosis diagnosis = MockJpa.newEntity(ListingFinancialDiagnosis.class);
        diagnosis.id = id;
        diagnosis.listingId = listingId;
        diagnosis.netIncome = new BigDecimal("80000");
        diagnosis.totalAssets = new BigDecimal("900000");
        diagnosis.totalDebts = new BigDecimal("250000");
        diagnosis.fixedAssets = new BigDecimal("300000");
        diagnosis.financialOutlook = "Croissance régionale";
        return diagnosis;
    }

    private DealRequest deal(Long id, Long listingId, Long buyerId, boolean buyerSigned, boolean sellerSigned) {
        DealRequest request = MockJpa.newEntity(DealRequest.class);
        request.id = id;
        request.listingId = listingId;
        request.buyerId = buyerId;
        request.createdAt = Instant.now();
        request.buyerSignedAt = buyerSigned ? Instant.now() : null;
        request.sellerSignedAt = sellerSigned ? Instant.now() : null;
        // Statut initial explicite : une demande fraîche est PENDING (le statut NDA_* dépend ensuite
        // de la décision du gestionnaire et des signatures).
        request.status = "PENDING";
        return request;
    }

    /**
     * Prépare les stubs JPQL correspondant à la lecture d'une annonce et d'une
     * demande.
     */
    private void prepareDealAccess(Listing listing, DealRequest request) {
        doReturn(request).when(em()).find(DealRequest.class, request.id);
        doReturn(listing).when(em()).find(Listing.class, listing.id);
        stubValidatedBuyerKyc();
    }

    private void stubValidatedBuyerKyc() {
        BuyerKyc kyc = MockJpa.newEntity(BuyerKyc.class);
        kyc.userId = 20L;
        kyc.status = BuyerKycStatus.VALIDATED;
        MockJpa.query(em(), "select k from BuyerKyc k where k.userId = :id", List.of(kyc));
    }

    // =============================================================================================
    // saveListing
    // =============================================================================================

    @Nested
    @DisplayName("saveListing")
    class SaveListing {

        private static final String SECTOR_QUERY = "select u from UserAccount u where u.email = :email and u.role = :role";

        private ListingSaveResult call(String sector, String region, String description,
                Integer foundedYear, BigDecimal revenue) {
            return service.saveListing(seller, null, null, "PRIMARY", List.of("agriculture"),
                    sector, null, foundedYear, description, region, null, null, null, revenue,
                    null, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("saveListing : cas nominal -> annonce PUBLISHED, champs nettoyés, sans courtier")
        void saveListing_toutValide_publieLAnnonce() {
            MockJpa.query(em(), SECTOR_QUERY, List.of());
            org.mockito.ArgumentCaptor<Listing> captor = org.mockito.ArgumentCaptor.forClass(Listing.class);

            ListingSaveResult result = service.saveListing(seller, null, null, "SECONDARY",
                    List.of("agro_processing", "manufacturing"), "  Agro  ", "  Distribution  ",
                    2010, "Description", " Abidjan ", "PME", "Marque", "Retraite",
                    new BigDecimal("1000000"), new BigDecimal("100000"), new BigDecimal("500000"),
                    new BigDecimal("10"), "Conditions", "12 mois", "Société Secrète", "Rue 12");

            assertFalse(result.brokerAssigned());
            verify(em()).persist(captor.capture());
            Listing saved = captor.getValue();
            assertEquals(10L, saved.sellerId);
            assertEquals("SECONDARY", saved.sectorCategory);
            assertEquals("agro_processing,manufacturing", saved.activityDomains);
            assertEquals("Agro", saved.sector);
            assertEquals("Distribution", saved.secondarySector);
            assertEquals("Abidjan", saved.region);
            assertEquals("PUBLISHED", saved.status);
            assertEquals(0, saved.views);
            assertEquals("enc:Société Secrète", saved.companyNameEncrypted, "L'identité doit être chiffrée");
            assertEquals("enc:Rue 12", saved.exactLocationEncrypted);
        }

        @Test
        @DisplayName("saveListing : conserve les indicateurs du diagnostic financier")
        void saveListing_persistsFinancialDiagnosis() {
            MockJpa.query(em(), SECTOR_QUERY, List.of());
            service.saveListing(seller, null, null, "PRIMARY", List.of("agriculture"),
                    "Agro", null, 2010, "Description", "Abidjan", null, null, null,
                    new BigDecimal("1000000"), new BigDecimal("100000"), new BigDecimal("500000"),
                    new BigDecimal("10"), null, null, null, null, null, false,
                    fr.alp.confidentia.model.ListingTransactionType.FULL_SALE,
                    new BigDecimal("80000"), new BigDecimal("900000"), new BigDecimal("250000"),
                    new BigDecimal("300000"), "Croissance régionale");

            verify(em(), atLeastOnce()).persist(org.mockito.ArgumentMatchers.argThat(entity ->
                    entity instanceof ListingFinancialDiagnosis diagnosis
                            && new BigDecimal("80000").equals(diagnosis.netIncome)
                            && new BigDecimal("900000").equals(diagnosis.totalAssets)
                            && new BigDecimal("250000").equals(diagnosis.totalDebts)
                            && new BigDecimal("300000").equals(diagnosis.fixedAssets)
                            && "Croissance régionale".equals(diagnosis.financialOutlook)));
        }

        @Test
        @DisplayName("saveListing : secteur et domaines incohérents -> refusés")
        void saveListing_domaineHorsSecteur_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.saveListing(seller, null, null, "PRIMARY", List.of("finance"),
                            "Agro", null, null, "Description", "Abidjan", null, null, null,
                            null, null, null, null, null, null, null, null));

            assertEquals("Sélectionnez un grand secteur et au moins un domaine d'activité correspondant.",
                    exception.getMessage());
        }

        @Test
        @DisplayName("saveListing : secteur null -> refusé")
        void saveListing_secteurNull_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> call(null, "Abidjan", "Description", null, null));

            assertEquals("Le secteur, la description et la région sont obligatoires.", exception.getMessage());
        }

        @Test
        @DisplayName("saveListing : description blanche -> refusée")
        void saveListing_descriptionBlanche_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> call("Agro", "Abidjan", "   ", null, null));
        }

        @Test
        @DisplayName("saveListing : région null -> refusée")
        void saveListing_regionNull_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> call("Agro", null, "Description", null, null));
        }

        @Test
        @DisplayName("saveListing : description de 4001 caractères -> refusée (limite)")
        void saveListing_descriptionTropLongue_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> call("Agro", "Abidjan", "d".repeat(4001), null, null));

            assertEquals("Un des champs dépasse la longueur autorisée.", exception.getMessage());
        }

        @Test
        @DisplayName("saveListing : description de 4000 caractères -> acceptée (limite)")
        void saveListing_descriptionALaLimite_acceptee() {
            MockJpa.query(em(), SECTOR_QUERY, List.of());

            assertNotNull(call("Agro", "Abidjan", "d".repeat(4000), null, null));
        }

        @Test
        @DisplayName("saveListing : année de création 1799 -> refusée")
        void saveListing_anneeTropAncienne_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> call("Agro", "Abidjan", "Description", 1799, null));

            assertEquals("Année de création invalide.", exception.getMessage());
        }

        @Test
        @DisplayName("saveListing : année de création 1800 -> acceptée (borne basse)")
        void saveListing_anneeBasseLimite_acceptee() {
            MockJpa.query(em(), SECTOR_QUERY, List.of());

            assertNotNull(call("Agro", "Abidjan", "Description", 1800, null));
        }

        @Test
        @DisplayName("saveListing : année future au-delà de 5 ans -> refusée")
        void saveListing_anneeFutureLointaine_refusee() {
            int tooLate = Year.now().getValue() + 6;

            assertThrows(IllegalArgumentException.class,
                    () -> call("Agro", "Abidjan", "Description", tooLate, null));
        }

        @Test
        @DisplayName("saveListing : chiffre d'affaires négatif -> refusé")
        void saveListing_caNegatif_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> call("Agro", "Abidjan", "Description", null, new BigDecimal("-1")));

            assertEquals("Chiffre d'affaires doit être positif ou nul.", exception.getMessage());
        }

        @Test
        @DisplayName("saveListing : chiffre d'affaires à 14 chiffres avant la virgule -> accepté (limite)")
        void saveListing_caALaLimiteDePrecision_accepte() {
            // precision = 16, scale = 2 : la partie entière est limitée à 14 chiffres.
            MockJpa.query(em(), SECTOR_QUERY, List.of());

            assertNotNull(call("Agro", "Abidjan", "Description", null, new BigDecimal("12345678901234.67")));
        }

        @Test
        @DisplayName("saveListing : chiffre d'affaires à 15 chiffres avant la virgule -> refusé")
        void saveListing_caQuinzeChiffres_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> call("Agro", "Abidjan", "Description", null, new BigDecimal("123456789012345.67")));
        }

        @Test
        @DisplayName("saveListing : chiffre d'affaires à 16 chiffres entiers -> refusé")
        void saveListing_caTropGrand_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> call("Agro", "Abidjan", "Description", null, new BigDecimal("1234567890123456.78")));
        }



        @Test
        @DisplayName("saveListing : le teaser contient le nom réel (même ponctué/cassé) -> refusé")
        void saveListing_teaserReprendLeNomNormalise_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.saveListing(seller, null, null, "PRIMARY", List.of("agriculture"),
                            "Agro", null, null,
                            "Rachat de la société société secrète et cie", "Abidjan", null, null, null,
                            null, null, null, null, null, null, "Société Secrète", null));

            assertTrue(exception.getMessage().contains("nom réel"));
        }

        @Test
        @DisplayName("saveListing : le teaser contient l'adresse exacte -> refusé")
        void saveListing_teaserContientLAdresse_refuse() {
            // La localisation exacte est chiffrée puis comparée au teaser après normalisation
            // (minuscules + suppression de tout caractère non alphanumérique).
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.saveListing(seller, null, null, "PRIMARY", List.of("agriculture"),
                            "Agro", null, null,
                            "Sur la commune de cocody, quartier calme", "Abidjan", null, null, null,
                            null, null, null, null, null, null, null, "Commune de Cocody"));

            assertTrue(exception.getMessage().contains("adresse exacte"));
        }

        @Test
        @DisplayName("saveListing : nom réel de moins de 3 caractères ignoré (pas de faux positif)")
        void saveListing_nomReelTropCourt_ignore() {
            // Le contrôle n'a de sens qu'à partir de 3 caractères : un nom trop court
            // ne doit pas déclencher de refus, la publication reste possible.
            MockJpa.query(em(), SECTOR_QUERY, List.of());

            ListingSaveResult result = service.saveListing(seller, null, null, "PRIMARY", List.of("agriculture"),
                    "Agro", null, null,
                    "Description", "Abidjan", null, null, null, null, null, null, null,
                    null, null, "AB", null);

            assertNotNull(result);
            assertFalse(result.brokerAssigned());
        }

        @Test
        @DisplayName("saveListing : rôle non cédant -> SecurityException")
        void saveListing_roleNonCedant_refuse() {
            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.saveListing(buyer, null, null, "PRIMARY", List.of("agriculture"),
                            "Agro", null, null, "Description",
                            "Abidjan", null, null, null, null, null, null, null,
                            null, null, null, null));

            assertEquals(NOT_AUTHORIZED, exception.getMessage());
        }

        @Test
        @DisplayName("saveListing : modification d'une annonce acceptée par un courtier -> refusée")
        void saveListing_annonceAcceptee_refusee() {
            doReturn(listing(5L, 10L, 30L, "BROKER_ACCEPTED")).when(em()).find(Listing.class, 5L);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.saveListing(seller, 5L, null, "PRIMARY", List.of("agriculture"),
                            "Agro", null, null, "Description",
                            "Abidjan", null, null, null, null, null, null, null,
                            null, null, null, null));

            assertTrue(exception.getMessage().contains("accepté par un courtier"));
        }

        @Test
        @DisplayName("saveListing : modification d'une annonce d'un autre cédant -> SecurityException")
        void saveListing_annonceDUnAutreCedant_refusee() {
            doReturn(listing(5L, 99L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.saveListing(seller, 5L, null, "PRIMARY", List.of("agriculture"),
                            "Agro", null, null, "Description",
                            "Abidjan", null, null, null, null, null, null, null,
                            null, null, null, null));

            assertEquals(SELLER_ONLY, exception.getMessage());
        }

        @Test
        @DisplayName("saveListing : annonce inexistante -> refusée")
        void saveListing_annonceInexistante_refusee() {
            doReturn(null).when(em()).find(Listing.class, 777L);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.saveListing(seller, 777L, null, "PRIMARY", List.of("agriculture"),
                            "Agro", null, null, "Description",
                            "Abidjan", null, null, null, null, null, null, null,
                            null, null, null, null));

            assertEquals("Annonce introuvable.", exception.getMessage());
        }

        @Test
        @DisplayName("saveListing : courtier e-mail valide -> affectation et brokerAssigned = true")
        void saveListing_avecCourtier_affecteLeCourtier() {
            MockJpa.query(em(), SECTOR_QUERY, List.of(account(30L, Role.BROKER)));

            ListingSaveResult result = service.saveListing(seller, null, "c@alp.fr", "PRIMARY", List.of("agriculture"),
                    "Agro", null, null,
                    "Description", "Abidjan", null, null, null, null, null, null, null,
                    null, null, null, null);

            assertTrue(result.brokerAssigned());
        }

        @Test
        @DisplayName("saveListing : e-mail de courtier inconnu -> annonce publiée sans courtier")
        void saveListing_courtierInconnu_publieSansCourtier() {
            MockJpa.query(em(), SECTOR_QUERY, List.of());

            assertFalse(service.saveListing(seller, null, "inconnu@alp.fr", "PRIMARY", List.of("agriculture"),
                    "Agro", null, null,
                    "Description", "Abidjan", null, null, null, null, null, null, null,
                    null, null, null, null)
                    .brokerAssigned());
        }
    }

    // =============================================================================================
    // listingForEditing / listingsFor / processFinancials
    // =============================================================================================

    @Nested
    @DisplayName("Consultation et modification")
    class Consultation {

        @Test
        @DisplayName("listingForEditing : cédant propriétaire -> champs déchiffrés")
        void listingForEditing_proprietaire_retourneLeFormulaire() {
            Listing stored = listing(5L, 10L, null, "PUBLISHED");
            stored.sectorCategory = "TERTIARY";
            stored.activityDomains = "commerce,finance";
            ListingFinancialDiagnosis diagnosis = diagnosis(1L, 5L);
            MockJpa.query(em(), "select d from ListingFinancialDiagnosis d where d.listingId = :listingId",
                    List.of(diagnosis));
            doReturn(stored).when(em()).find(Listing.class, 5L);

            ListingEditView view = service.listingForEditing(seller, 5L);

            assertEquals("TERTIARY", view.sectorCategory());
            assertEquals(List.of("commerce", "finance"), view.activityDomains());
            assertEquals("Agroalimentaire", view.sector());
            assertEquals("Société Secrète", view.companyName());
            assertEquals("Rue 12, Cocody", view.exactLocation());
            assertEquals(new BigDecimal("80000"), view.netIncome());
            assertEquals(new BigDecimal("900000"), view.totalAssets());
            assertEquals(new BigDecimal("250000"), view.totalDebts());
            assertEquals(new BigDecimal("300000"), view.fixedAssets());
            assertEquals("Croissance régionale", view.financialOutlook());
            assertNull(view.brokerEmail());
        }

        @Test
        @DisplayName("listingForEditing : données chiffrées avec une ancienne clé -> message d'indisponibilité")
        void listingForEditing_ancienneCle_retourneUnMessageDIndisponibilite() {
            Listing stored = listing(5L, 10L, null, "PUBLISHED");
            stored.companyNameEncrypted = "old:xyz";
            stored.exactLocationEncrypted = "old:abc";
            doReturn(stored).when(em()).find(Listing.class, 5L);

            ListingEditView view = service.listingForEditing(seller, 5L);

            assertEquals(UNAVAILABLE, view.companyName());
            assertEquals(UNAVAILABLE, view.exactLocation());
        }

        @Test
        @DisplayName("listingForEditing : autre cédant -> SecurityException")
        void listingForEditing_autreCedant_refuse() {
            doReturn(listing(5L, 99L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);

            assertThrows(SecurityException.class, () -> service.listingForEditing(seller, 5L));
        }

        @Test
        @DisplayName("listingForEditing : annonce acceptée -> modification interdite")
        void listingForEditing_annonceAcceptee_refuse() {
            doReturn(listing(5L, 10L, 30L, "BROKER_ACCEPTED")).when(em()).find(Listing.class, 5L);

            assertThrows(IllegalArgumentException.class, () -> service.listingForEditing(seller, 5L));
        }

        @Test
        @DisplayName("listingForEditing : rôle non cédant -> SecurityException")
        void listingForEditing_roleNonCedant_refuse() {
            assertThrows(SecurityException.class, () -> service.listingForEditing(buyer, 5L));
        }

        @Test
        @DisplayName("listingsFor (acheteur) : les filtres secteur/région/prix s'appliquent")
        void listingsFor_acheteur_appliqueLesFiltres() {
            Listing matching = listing(1L, 10L, null, "PUBLISHED");
            Listing wrongSector = listing(2L, 10L, null, "PUBLISHED");
            wrongSector.sector = "Tech";
            wrongSector.secondarySector = null;
            Listing tooExpensive = listing(3L, 10L, null, "PUBLISHED");
            tooExpensive.askingPrice = new BigDecimal("900000");

            MockJpa.query(em(),
                    "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                    List.of(matching, wrongSector, tooExpensive));
            MockJpa.query(em(), "select p from BuyerProfile p where p.userId = :id", List.of());
            MockJpa.query(em(),
                    "select d from DealRequest d where d.buyerId = :buyerId "
                            + "and d.buyerSignedAt is not null and d.sellerSignedAt is not null",
                    List.of(deal(11L, 1L, buyer.id(), true, true)));

            List<ListingView> views = service.listingsFor(buyer, "agro", "abidjan", new BigDecimal("600000"));

            assertEquals(1, views.size());
            assertEquals(1L, views.get(0).id());
        }

        @Test
        @DisplayName("listingsFor (acheteur) : applique domaine, ancienneté, effectif et transaction")
        void listingsFor_acheteur_appliqueLesFiltresEtendus() {
            Listing matching = listing(1L, 10L, null, "PUBLISHED");
            matching.activityDomains = "agriculture,logistique";
            matching.foundedYear = 2012;
            matching.approximateEmployeeCount = 34;
            matching.transactionType = ListingTransactionType.PARTIAL_SALE;
            Listing wrongDomain = listing(2L, 10L, null, "PUBLISHED");
            wrongDomain.activityDomains = "santé";
            MockJpa.query(em(),
                    "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                    List.of(matching, wrongDomain));
            MockJpa.query(em(), "select p from BuyerProfile p where p.userId = :id", List.of());

            ListingSearchFilters filters = new ListingSearchFilters(null, "logistique", "Abidjan",
                    null, null, null, null, null, 2010, 2015, 10, 50,
                    ListingTransactionType.PARTIAL_SALE);
            List<ListingView> views = service.listingsFor(buyer, filters, false);

            assertEquals(1, views.size());
            assertEquals(1L, views.get(0).id());
            assertEquals(ListingTransactionType.PARTIAL_SALE, views.get(0).transactionType());
        }

        @Test
        @DisplayName("toggleFavorite : ajoute une annonce publiée aux favoris")
        void toggleFavorite_ajouteLeFavori() {
            Listing listing = listing(5L, 10L, null, "PUBLISHED");
            doReturn(listing).when(em()).find(Listing.class, 5L);
            MockJpa.query(em(),
                    "select f from BuyerFavorite f where f.buyerId = :buyerId and f.listingId = :listingId",
                    List.of());

            boolean added = service.toggleFavorite(buyer, 5L);

            assertTrue(added);
            org.mockito.ArgumentCaptor<BuyerFavorite> captor =
                    org.mockito.ArgumentCaptor.forClass(BuyerFavorite.class);
            verify(em()).persist(captor.capture());
            assertEquals(buyer.id(), captor.getValue().buyerId);
            assertEquals(5L, captor.getValue().listingId);
        }

        @Test
        @DisplayName("toggleFavorite : refuse une annonce suspendue")
        void toggleFavorite_refuseUneAnnonceSuspendue() {
            doReturn(listing(5L, 10L, null, "SUSPENDED")).when(em()).find(Listing.class, 5L);

            assertThrows(IllegalArgumentException.class, () -> service.toggleFavorite(buyer, 5L));
        }

        @Test
        @DisplayName("listingsFor (cédant) : uniquement les annonces du cédant")
        void listingsFor_cedant_retourneSesAnnonces() {
            MockJpa.query(em(), "select l from Listing l where l.sellerId = :id order by l.createdAt desc",
                    List.of(listing(1L, 10L, null, "PUBLISHED")));

            List<ListingView> views = service.listingsFor(seller);

            assertEquals(1, views.size());
            assertEquals(0, views.get(0).matchPercent(), "Un cédant n'a pas de profil acheteur");
        }

        @Test
        @DisplayName("listingsFor (acheteur) : tri par pourcentage de correspondance décroissant")
        void listingsFor_acheteur_trieParCorrespondance() {
            Listing matching = listing(1L, 10L, null, "PUBLISHED");
            Listing lessMatching = listing(2L, 10L, null, "PUBLISHED");
            lessMatching.sector = "Tech";
            lessMatching.region = "Dakar";
            MockJpa.query(em(),
                    "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                    List.of(lessMatching, matching));

            fr.alp.confidentia.model.BuyerProfile profile = MockJpa
                    .newEntity(fr.alp.confidentia.model.BuyerProfile.class);
            profile.userId = 20L;
            profile.sectors = "Agroalimentaire, Tech";
            profile.preferredRegion = "Abidjan";
            profile.maxInvestment = new BigDecimal("600000");
            MockJpa.query(em(), "select p from BuyerProfile p where p.userId = :id", List.of(profile));

            List<ListingView> views = service.listingsFor(buyer);

            assertEquals(1L, views.get(0).id(), "La meilleure correspondance doit être en tête");
            assertTrue(views.get(0).matchPercent() >= views.get(1).matchPercent());
        }

        @Test
        @DisplayName("listingsFor (acheteur) : identité masquée sans NDA signé")
        void listingsFor_acheteur_sansNda_masqueLIdentite() {
            MockJpa.query(em(),
                    "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                    List.of(listing(1L, 10L, null, "PUBLISHED")));
            MockJpa.query(em(), "select p from BuyerProfile p where p.userId = :id", List.of());

            ListingView view = service.listingsFor(buyer).get(0);

            assertNull(view.companyName());
            assertNull(view.exactLocation());
            assertNull(view.foundedYear());
            assertNull(view.revenue());
            assertNull(view.ebitda());
            assertNull(view.askingPrice());
            assertNull(view.netIncome());
            assertNull(view.totalAssets());
            assertNull(view.totalDebts());
            assertNull(view.fixedAssets());
            assertNull(view.financialOutlook());
            assertFalse(view.professionalValuationValidated());
            assertTrue(view.description().contains("validation du profil"));
        }

        @Test
        @DisplayName("listingsFor (courtier) : accès aux annonces non attribuées publiées")
        void listingsFor_courtier_retourneSesAnnoncesEtLesLibres() {
            MockJpa.query(em(),
                    "select l from Listing l where l.brokerId = :id or (l.brokerId is null and l.status = 'PUBLISHED') order by l.createdAt desc",
                    List.of(listing(1L, 10L, null, "PUBLISHED")));

            assertEquals(1, service.listingsFor(broker).size());
        }

        @Test
        @DisplayName("publicListings : mappe les annonces sans profil acheteur")
        void publicListings_retourneLesAnnoncesPubliees() {
            Listing published = listing(1L, 10L, null, "PUBLISHED");
            published.sectorCategory = "PRIMARY";
            published.activityDomains = "agriculture";
            published.foundedYear = Year.now().getValue() - 12;
            published.approximateEmployeeCount = 34;
            published.publicRevenueRangeAllowed = true;
            published.revenue = new BigDecimal("1500000");
            MockJpa.query(em(),
                    "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                    List.of(published));

            List<PublicListingView> views = service.publicListings();

            assertEquals(1, views.size());
            assertEquals("Agroalimentaire", views.get(0).sector());
            assertEquals("11 à 20 ans", views.get(0).experienceRange());
            assertEquals("11 à 50", views.get(0).employeeRange());
            assertEquals("500 000 à 1 999 999", views.get(0).revenueRange());
        }

        @Test
        @DisplayName("publicListings : masque la fourchette de CA si le cédant ne l’autorise pas")
        void publicListings_masqueLeChiffreAffairesSansAutorisation() {
            Listing published = listing(1L, 10L, null, "PUBLISHED");
            published.publicRevenueRangeAllowed = false;
            MockJpa.query(em(),
                    "select l from Listing l where l.status in ('PUBLISHED', 'BROKER_ACCEPTED') order by l.createdAt desc",
                    List.of(published));

            assertNull(service.publicListings().get(0).revenueRange());
        }

        @Test
        @DisplayName("processFinancials : calcule la marge et le prix estimatif")
        void processFinancials_multipleValide_calculeLesIndicateurs() {
            Listing stored = listing(5L, 10L, 30L, "PUBLISHED");
            stored.profitMargin = null;
            doReturn(stored).when(em()).find(Listing.class, 5L);
            MockJpa.query(em(), "select d from ListingFinancialDiagnosis d where d.listingId = :listingId",
                    List.of());

            service.processFinancials(broker, 5L, new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("5"));

            assertEquals(new BigDecimal("10.00"), stored.profitMargin);
            assertEquals(new BigDecimal("500.00"), stored.askingPrice);
            verify(em(), atLeastOnce()).persist(org.mockito.ArgumentMatchers.argThat(entity ->
                    entity instanceof ListingFinancialDiagnosis diagnosis
                            && new BigDecimal("5").equals(diagnosis.valuationMultiple)
                            && broker.id().equals(diagnosis.valuationValidatedBy)
                            && diagnosis.valuationValidatedAt != null));
        }

        @Test
        @DisplayName("processFinancials : multiple 0 -> refusé (borne basse exclue)")
        void processFinancials_multipleZero_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.processFinancials(broker, 5L, null, null, BigDecimal.ZERO));
        }

        @Test
        @DisplayName("processFinancials : multiple 30 -> accepté (borne haute incluse)")
        void processFinancials_multipleTrente_accepte() {
            Listing stored = listing(5L, 10L, 30L, "PUBLISHED");
            doReturn(stored).when(em()).find(Listing.class, 5L);

            service.processFinancials(broker, 5L, new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("30"));

            assertEquals(new BigDecimal("3000.00"), stored.askingPrice);
        }

        @Test
        @DisplayName("processFinancials : multiple 30.01 -> refusé")
        void processFinancials_multipleTropGrand_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.processFinancials(broker, 5L, null, null, new BigDecimal("30.01")));
        }

        @Test
        @DisplayName("processFinancials : multiple négatif -> refusé")
        void processFinancials_multipleNegatif_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.processFinancials(broker, 5L, null, null, new BigDecimal("-1")));
        }

        @Test
        @DisplayName("processFinancials : EBITDAO négatif avec multiple -> refusé (valorisation impossible)")
        void processFinancials_ebitdaNegatif_avecMultiple_refuse() {
            Listing stored = listing(5L, 10L, 30L, "PUBLISHED");
            stored.ebitda = new BigDecimal("-100");
            doReturn(stored).when(em()).find(Listing.class, 5L);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.processFinancials(broker, 5L, new BigDecimal("1000"), null, new BigDecimal("5")));

            assertTrue(exception.getMessage().contains("EBITDA négatif"));
        }

        @Test
        @DisplayName("processFinancials : rôle non courtier -> SecurityException")
        void processFinancials_roleNonCourtier_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.processFinancials(seller, 5L, null, null, BigDecimal.ONE));
        }

        @Test
        @DisplayName("processFinancials : courtier non mandaté -> SecurityException")
        void processFinancials_courtierNonMandate_refuse() {
            doReturn(listing(5L, 10L, 30L, "PUBLISHED")).when(em()).find(Listing.class, 5L);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.processFinancials(anotherBroker, 5L, null, null, null));

            assertEquals(MANAGER_ONLY, exception.getMessage());
        }

        @Test
        @DisplayName("acceptListing : courtier sur annonce libre -> BROKER_ACCEPTED")
        void acceptListing_annonceLibre_accepte() {
            Listing stored = listing(5L, 10L, null, "PUBLISHED");
            doReturn(stored).when(em()).find(Listing.class, 5L);

            service.acceptListing(broker, 5L);

            assertEquals(30L, stored.brokerId);
            assertEquals("BROKER_ACCEPTED", stored.status);
        }

        @Test
        @DisplayName("acceptListing : annonce déjà attribuée à un autre courtier -> SecurityException")
        void acceptListing_autreCourtier_refuse() {
            doReturn(listing(5L, 10L, 30L, "PUBLISHED")).when(em()).find(Listing.class, 5L);

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.acceptListing(anotherBroker, 5L));

            assertTrue(exception.getMessage().contains("déjà attribué"));
        }

        @Test
        @DisplayName("acceptListing : annonce déjà acceptée -> refusé")
        void acceptListing_dejaAcceptee_refuse() {
            doReturn(listing(5L, 10L, 30L, "BROKER_ACCEPTED")).when(em()).find(Listing.class, 5L);

            assertThrows(IllegalArgumentException.class, () -> service.acceptListing(broker, 5L));
        }

        @Test
        @DisplayName("recordMarketplaceViews : acheteur -> une mise à jour exécutée")
        void recordMarketplaceViews_acheteur_miseAJour() {
            // Requête bulk non typée : elle passe par createQuery(String), pas createQuery(String, Class).
            MockJpa.updateUntyped(em(),
                    "update Listing l set l.views = l.views + 1 where l.status in ('PUBLISHED', 'BROKER_ACCEPTED')");

            service.recordMarketplaceViews(buyer);

            verify(em()).createQuery(
                    "update Listing l set l.views = l.views + 1 where l.status in ('PUBLISHED', 'BROKER_ACCEPTED')");
        }

        @Test
        @DisplayName("recordMarketplaceViews : non acheteur -> SecurityException")
        void recordMarketplaceViews_nonAcheteur_refuse() {
            assertThrows(SecurityException.class, () -> service.recordMarketplaceViews(seller));
        }
    }

    // =============================================================================================
    // Demandes de mise en relation / NDA
    // =============================================================================================

    @Nested
    @DisplayName("Demandes de mise en relation et NDA")
    class Demandes {

        @Test
        @DisplayName("requestIntroduction : annonce publiée -> demande PENDING persistée")
        void requestIntroduction_annoncePubliee_creeLaDemande() {
            doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);
            MockJpa.query(em(),
                    "select count(d) from DealRequest d where d.listingId = :listingId and d.buyerId = :buyerId",
                    List.of(0L));
            org.mockito.ArgumentCaptor<DealRequest> captor = org.mockito.ArgumentCaptor.forClass(DealRequest.class);

            service.requestIntroduction(buyer, 5L);

            verify(em()).persist(captor.capture());
            assertEquals("PENDING", captor.getValue().status);
            assertEquals(20L, captor.getValue().buyerId);
        }

        @Test
        @DisplayName("requestIntroduction : annonce non disponible -> refusé")
        void requestIntroduction_annonceNonDisponible_refuse() {
            doReturn(listing(5L, 10L, null, "DRAFT")).when(em()).find(Listing.class, 5L);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.requestIntroduction(buyer, 5L));

            assertEquals("Cette annonce n'est pas disponible.", exception.getMessage());
        }

        @Test
        @DisplayName("requestIntroduction : demande déjà envoyée -> refusé")
        void requestIntroduction_demandeExistante_refuse() {
            doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);
            MockJpa.query(em(),
                    "select count(d) from DealRequest d where d.listingId = :listingId and d.buyerId = :buyerId",
                    List.of(1L));

            assertThrows(IllegalArgumentException.class, () -> service.requestIntroduction(buyer, 5L));
        }

        @Test
        @DisplayName("requestIntroduction : rôle non acheteur -> SecurityException")
        void requestIntroduction_nonAcheteur_refuse() {
            assertThrows(SecurityException.class, () -> service.requestIntroduction(seller, 5L));
        }

        @Test
        @DisplayName("decideIntroduction : acceptation -> NDA_PENDING avec version")
        void decideIntroduction_acceptation_passeEnNdaPending() {
            Listing stored = listing(5L, 10L, null, "PUBLISHED");
            DealRequest pending = deal(7L, 5L, 20L, false, false);
            prepareDealAccess(stored, pending);

            service.decideIntroduction(seller, 7L, true);

            assertEquals("NDA_PENDING", pending.status);
            assertEquals("MVP-1.0", pending.ndaVersion);
        }

        @Test
        @DisplayName("decideIntroduction : refus -> DECLINED sans version")
        void decideIntroduction_refus_passeEnDeclined() {
            Listing stored = listing(5L, 10L, null, "PUBLISHED");
            DealRequest pending = deal(7L, 5L, 20L, false, false);
            pending.ndaVersion = "MVP-1.0";
            prepareDealAccess(stored, pending);

            service.decideIntroduction(seller, 7L, false);

            assertEquals("DECLINED", pending.status);
            assertNull(pending.ndaVersion);
        }

        @Test
        @DisplayName("decideIntroduction : demande déjà traitée -> refusé")
        void decideIntroduction_dejaTraitee_refuse() {
            DealRequest treated = deal(7L, 5L, 20L, true, true);
            treated.status = "NDA_SIGNED"; // demande déjà traitée (plus PENDING)
            treatAsPublished(treated);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.decideIntroduction(seller, 7L, true));

            assertEquals("Cette demande a déjà été traitée.", exception.getMessage());
        }

        @Test
        @DisplayName("decideIntroduction : gestionnaire non autorisé -> SecurityException")
        void decideIntroduction_nonGestionnaire_refuse() {
            DealRequest pending = deal(7L, 5L, 20L, false, false);
            treatAsPublished(pending);

            assertThrows(SecurityException.class, () -> service.decideIntroduction(anotherBroker, 7L, true));
        }

        @Test
        @DisplayName("signNda : acheteur -> signature acheteur enregistrée")
        void signNda_acheteur_enregistreLaSignatureAcheteur() {
            DealRequest pending = deal(7L, 5L, 20L, false, true);
            pending.status = "NDA_PENDING";
            treatAsPublished(pending);

            service.signNda(buyer, 7L, "  Acheteur Signataire  ");

            assertEquals("Acheteur Signataire", pending.buyerSignatureName);
            assertNotNull(pending.buyerSignedAt);
            assertEquals("NDA_SIGNED", pending.status);
        }

        @Test
        @DisplayName("signNda : profil acquéreur non validé -> signature refusée")
        void signNda_profilNonValide_refuseLaSignature() {
            DealRequest pending = deal(7L, 5L, 20L, false, true);
            pending.status = "NDA_PENDING";
            treatAsPublished(pending);
            MockJpa.query(em(), "select k from BuyerKyc k where k.userId = :id", List.of());

            assertThrows(SecurityException.class, () -> service.signNda(buyer, 7L, "Acheteur Signataire"));
            assertNull(pending.buyerSignedAt);
        }

        @Test
        @DisplayName("signNda : cédant -> signature cédant enregistrée")
        void signNda_cedant_enregistreLaSignatureCedant() {
            DealRequest pending = deal(7L, 5L, 20L, true, false);
            pending.status = "NDA_PENDING"; // demande acceptée, en attente de la signature du cédant
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), pending);

            service.signNda(seller, 7L, "Cédant Signataire");

            assertEquals("Cédant Signataire", pending.sellerSignatureName);
            assertNotNull(pending.sellerSignedAt);
        }

        @Test
        @DisplayName("signNda : signature blanche -> refusée")
        void signNda_signatureBlanche_refusee() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.signNda(buyer, 7L, "   "));

            assertEquals("Saisissez votre nom complet comme signature.", exception.getMessage());
        }

        @Test
        @DisplayName("signNda : signature null -> refusée")
        void signNda_signatureNull_refusee() {
            assertThrows(IllegalArgumentException.class, () -> service.signNda(buyer, 7L, null));
        }

        @Test
        @DisplayName("signNda : signature de 161 caractères -> refusée")
        void signNda_signatureTropLongue_refusee() {
            assertThrows(IllegalArgumentException.class, () -> service.signNda(buyer, 7L, "n".repeat(161)));
        }

        @Test
        @DisplayName("signNda : demande refusée -> signature interdite")
        void signNda_demandeDeclinee_refusee() {
            DealRequest declined = deal(7L, 5L, 20L, false, false);
            declined.status = "DECLINED";
            treatAsPublished(declined);

            assertThrows(IllegalArgumentException.class, () -> service.signNda(buyer, 7L, "Nom"));
        }

        @Test
        @DisplayName("signNda : tiers non participant -> refusé")
        void signNda_tiers_refuse() {
            treatAsPublished(deal(7L, 5L, 20L, false, true));

            assertThrows(IllegalArgumentException.class, () -> service.signNda(broker, 7L, "Nom"));
        }

        @Test
        @DisplayName("dealsFor (acheteur) : nom du cédant masqué avant signature")
        void dealsFor_acheteurSansNda_masqueLeCedant() {
            DealRequest request = deal(7L, 5L, 20L, false, false);
            request.status = "NDA_PENDING";
            Listing stored = listing(5L, 10L, null, "PUBLISHED");
            MockJpa.query(em(), "select d from DealRequest d where d.buyerId = :id order by d.createdAt desc",
                    List.of(request));
            doReturn(stored).when(em()).find(Listing.class, 5L);
            doReturn(account(20L, Role.BUYER)).when(em()).find(UserAccount.class, 20L);
            doReturn(account(10L, Role.SELLER)).when(em()).find(UserAccount.class, 10L);

            DealView view = service.dealsFor(buyer).get(0);

            assertEquals("Cédant confidentiel", view.sellerName());
            assertFalse(view.ndaSigned());
            assertNull(view.companyName());
        }

        @Test
        @DisplayName("dealsFor : NDA signé mais ancienne clé -> indicateur d'indisponibilité")
        void dealsFor_ancienneCle_signaleLIndisponibilite() {
            DealRequest request = deal(7L, 5L, 20L, true, true);
            Listing stored = listing(5L, 10L, null, "PUBLISHED");
            stored.companyNameEncrypted = "old:x";
            MockJpa.query(em(), "select d from DealRequest d where d.buyerId = :id order by d.createdAt desc",
                    List.of(request));
            doReturn(stored).when(em()).find(Listing.class, 5L);
            doReturn(account(20L, Role.BUYER)).when(em()).find(UserAccount.class, 20L);
            doReturn(account(10L, Role.SELLER)).when(em()).find(UserAccount.class, 10L);
            stubValidatedBuyerKyc();

            DealView view = service.dealsFor(buyer).get(0);

            assertTrue(view.encryptedIdentityUnavailable());
            assertEquals(UNAVAILABLE, view.companyName());
        }

        @Test
        @DisplayName("dealsFor (administrateur) : voit les dossiers pour revue ALP")
        void dealsFor_administrateurVoitLesDossiersPourRevue() {
            DealRequest request = deal(7L, 5L, 20L, true, true);
            MockJpa.query(em(), "select d from DealRequest d order by d.createdAt desc", List.of(request));
            doReturn(listing(5L, 10L, 30L, "PUBLISHED")).when(em()).find(Listing.class, 5L);
            doReturn(account(20L, Role.BUYER)).when(em()).find(UserAccount.class, 20L);
            doReturn(account(10L, Role.SELLER)).when(em()).find(UserAccount.class, 10L);
            UserView reviewer = new UserView(99L, "admin@alp.fr", "Admin", "ALP", "SUPER", false);

            assertEquals(1, service.dealsFor(reviewer).size());
        }

        @Test
        @DisplayName("decideIntroduction : l'administrateur peut traiter une demande")
        void decideIntroduction_administrateurPeutTraiterLaDemande() {
            DealRequest request = deal(7L, 5L, 20L, false, false);
            request.status = "PENDING";
            prepareDealAccess(listing(5L, 10L, 30L, "PUBLISHED"), request);
            UserView reviewer = new UserView(99L, "admin@alp.fr", "Admin", "ALP", "SUPER", false);

            service.decideIntroduction(reviewer, 7L, true);

            assertEquals("NDA_PENDING", request.status);
            assertEquals("MVP-1.0", request.ndaVersion);
        }
    }

    /**
     * Simule l'accès à une demande pour une annonce publiée appartenant au cédant
     * 10.
     */
    private void treatAsPublished(DealRequest request) {
        doReturn(request).when(em()).find(DealRequest.class, request.id);
        doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);
        stubValidatedBuyerKyc();
    }

    // =============================================================================================
    // Messagerie chiffrée
    // =============================================================================================

    @Nested
    @DisplayName("Messagerie")
    class Messagerie {

        @Test
        @DisplayName("sendMessage : NDA signé -> message chiffré persisté")
        void sendMessage_ndaSigne_persisteUnMessageChiffre() {
            DealRequest signed = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), signed);
            org.mockito.ArgumentCaptor<fr.alp.confidentia.model.SecureMessage> captor = org.mockito.ArgumentCaptor
                    .forClass(fr.alp.confidentia.model.SecureMessage.class);

            service.sendMessage(buyer, 7L, "  Bonjour  ");

            verify(em()).persist(captor.capture());
            assertEquals("enc:Bonjour", captor.getValue().encryptedBody);
            assertEquals(20L, captor.getValue().senderId);
        }

        @Test
        @DisplayName("sendMessage : message vide -> refusé")
        void sendMessage_messageVide_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.sendMessage(buyer, 7L, "   "));

            assertEquals("Le message doit contenir entre 1 et 8 000 caractères.", exception.getMessage());
        }

        @Test
        @DisplayName("sendMessage : message null -> refusé")
        void sendMessage_messageNull_refuse() {
            assertThrows(IllegalArgumentException.class, () -> service.sendMessage(buyer, 7L, null));
        }

        @Test
        @DisplayName("sendMessage : message de 8 001 caractères -> refusé")
        void sendMessage_messageTropLong_refuse() {
            assertThrows(IllegalArgumentException.class, () -> service.sendMessage(buyer, 7L, "m".repeat(8001)));
        }

        @Test
        @DisplayName("sendMessage : message de 8 000 caractères -> accepté (limite)")
        void sendMessage_messageALaLimite_accepte() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, true, true));

            service.sendMessage(buyer, 7L, "m".repeat(8000));

            verify(em()).persist(org.mockito.ArgumentMatchers.any(fr.alp.confidentia.model.SecureMessage.class));
        }

        @Test
        @DisplayName("sendMessage : NDA non signé -> SecurityException")
        void sendMessage_ndaNonSigne_refuse() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, true, false));

            SecurityException exception = assertThrows(SecurityException.class,
                    () -> service.sendMessage(buyer, 7L, "Bonjour"));

            assertTrue(exception.getMessage().contains("confidentialité"));
        }

        @Test
        @DisplayName("sendMessage : tiers authentifié -> SecurityException")
        void sendMessage_tiers_refuse() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, true, true));

            assertThrows(SecurityException.class, () -> service.sendMessage(anotherBroker, 7L, "Bonjour"));
        }

        @Test
        @DisplayName("messages : NDA signé -> contenu déchiffré pour affichage")
        void messages_ndaSigne_retourneLesMessagesDechiffres() {
            DealRequest signed = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), signed);
            fr.alp.confidentia.model.SecureMessage message = MockJpa
                    .newEntity(fr.alp.confidentia.model.SecureMessage.class);
            message.id = 2L;
            message.requestId = 7L;
            message.senderId = 20L;
            message.encryptedBody = "enc:Bonjour";
            message.createdAt = Instant.now();
            MockJpa.query(em(), "select m from SecureMessage m where m.requestId = :requestId order by m.createdAt",
                    List.of(message));
            doReturn(account(20L, Role.BUYER)).when(em()).find(UserAccount.class, 20L);

            var views = service.messages(buyer, 7L);

            assertEquals(1, views.size());
            assertEquals("Bonjour", views.get(0).body());
            assertEquals("Utilisateur 20", views.get(0).sender());
        }

        @Test
        @DisplayName("messages : NDA non signé -> SecurityException")
        void messages_ndaNonSigne_refuse() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, false, false));

            assertThrows(SecurityException.class, () -> service.messages(buyer, 7L));
        }

        @Test
        @DisplayName("proposeAppointment : participant avec NDA signé -> proposition persistée")
        void proposeAppointment_participantCreeUneProposition() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, true, true));
            org.mockito.ArgumentCaptor<DealAppointment> captor =
                    org.mockito.ArgumentCaptor.forClass(DealAppointment.class);

            service.proposeAppointment(buyer, 7L, LocalDateTime.now().plusDays(2));

            verify(em()).persist(captor.capture());
            assertEquals(7L, captor.getValue().requestId);
            assertEquals(buyer.id(), captor.getValue().proposedBy);
            assertEquals(AppointmentStatus.PENDING, captor.getValue().status);
        }

        @Test
        @DisplayName("proposeAppointment : tiers refusé")
        void proposeAppointment_tiersRefuse() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, true, true));

            assertThrows(SecurityException.class,
                    () -> service.proposeAppointment(anotherBroker, 7L, LocalDateTime.now().plusDays(2)));
        }

        @Test
        @DisplayName("respondToAppointment : autre participant confirme la proposition")
        void respondToAppointment_autreParticipantConfirme() {
            Listing listing = listing(5L, 10L, null, "PUBLISHED");
            DealRequest request = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing, request);
            DealAppointment appointment = MockJpa.newEntity(DealAppointment.class);
            appointment.id = 9L;
            appointment.requestId = 7L;
            appointment.proposedBy = buyer.id();
            appointment.status = AppointmentStatus.PENDING;
            doReturn(appointment).when(em()).find(DealAppointment.class, 9L);

            service.respondToAppointment(seller, 9L, true);

            assertEquals(AppointmentStatus.CONFIRMED, appointment.status);
        }

        @Test
        @DisplayName("messages : corps illisible (ancienne clé) -> message d'indisponibilité")
        void messages_corpsIllisible_messageDIndisponibilite() {
            DealRequest signed = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), signed);
            fr.alp.confidentia.model.SecureMessage message = MockJpa
                    .newEntity(fr.alp.confidentia.model.SecureMessage.class);
            message.id = 2L;
            message.senderId = 20L;
            message.encryptedBody = "old:abc";
            message.createdAt = Instant.now();
            MockJpa.query(em(), "select m from SecureMessage m where m.requestId = :requestId order by m.createdAt",
                    List.of(message));
            doReturn(account(20L, Role.BUYER)).when(em()).find(UserAccount.class, 20L);

            assertEquals(UNAVAILABLE, service.messages(buyer, 7L).get(0).body());
        }
    }

    // =============================================================================================
    // Documents
    // =============================================================================================

    @Nested
    @DisplayName("Documents sécurisés")
    class Documents {

        @Test
        @DisplayName("storeSellerVerificationDocument : type invalide -> refusé")
        void storeSellerVerificationDocument_typeInvalide_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.storeSellerVerificationDocument(seller, "UNKNOWN",
                            "rccm.pdf", "application/pdf", new byte[] { 1 }));
        }

        @Test
        @DisplayName("storeSellerVerificationDocument : réservé au cédant")
        void storeSellerVerificationDocument_nonCedant_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.storeSellerVerificationDocument(buyer, "RCCM",
                            "rccm.pdf", "application/pdf", new byte[] { 1 }));
        }

        @Test
        @DisplayName("storeSellerVerificationDocument : stocke la pièce chiffrée hors dossier de cession")
        void storeSellerVerificationDocument_valide_persisteLaPiece() {
            String previousStorageDirectory = System.getProperty("PRIVATE_STORAGE_DIR");
            System.setProperty("PRIVATE_STORAGE_DIR", temporaryDirectory.toString());
            try {
                org.mockito.ArgumentCaptor<SecureDocument> captor =
                        org.mockito.ArgumentCaptor.forClass(SecureDocument.class);

                var view = service.storeSellerVerificationDocument(seller, "RCCM",
                        "rccm.pdf", "application/pdf", new byte[] { 1, 2, 3 });

                verify(em()).persist(captor.capture());
                SecureDocument saved = captor.getValue();
                assertNull(saved.listingId);
                assertNull(saved.requestId);
                assertEquals(seller.id(), saved.uploadedBy);
                assertEquals("SELLER_VERIFICATION", saved.documentPurpose);
                assertEquals(SellerDocumentType.RCCM.name(), saved.verificationType);
                assertEquals("enc:rccm.pdf", saved.originalName);
                assertEquals(SellerDocumentType.RCCM.name(), view.verificationType());
            } finally {
                if (previousStorageDirectory == null) {
                    System.clearProperty("PRIVATE_STORAGE_DIR");
                } else {
                    System.setProperty("PRIVATE_STORAGE_DIR", previousStorageDirectory);
                }
            }

        }

        @Test
        @DisplayName("readDocument : un acquéreur ne peut pas lire une pièce de vérification")
        void readDocument_acquereurNePeutPasLirePieceDeVerification() {
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 7L;
            document.documentPurpose = "SELLER_VERIFICATION";
            document.uploadedBy = seller.id();
            doReturn(document).when(em()).find(SecureDocument.class, 7L);

            assertThrows(SecurityException.class, () -> service.readDocument(buyer, 7L));
        }

        @Test
        @DisplayName("sellerVerificationDocuments : ne retourne que les pièces du cédant")
        void sellerVerificationDocuments_retourneLesPiecesDuCedant() {
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.uploadedBy = seller.id();
            document.documentPurpose = "SELLER_VERIFICATION";
            document.verificationType = "RCCM";
            document.originalName = "enc:rccm.pdf";
            document.contentType = "application/pdf";
            document.sizeBytes = 10;
            document.uploadedAt = Instant.now();
            MockJpa.query(em(),
                    "select d from SecureDocument d where d.documentPurpose = 'SELLER_VERIFICATION' "
                            + "and d.uploadedBy = :id order by d.uploadedAt desc",
                    List.of(document));

            var documents = service.sellerVerificationDocuments(seller);

            assertEquals(1, documents.size());
            assertEquals("RCCM", documents.get(0).verificationType());
            assertEquals("rccm.pdf", documents.get(0).name());
        }

        @Test
        @DisplayName("storeDocument : fichier vide -> refusé")
        void storeDocument_fichierVide_refuse() {
            assertEquals("Le fichier doit faire entre 1 octet et 20 Mo.",
                    assertThrows(IllegalArgumentException.class,
                            () -> service.storeDocument(seller, 5L, null, "a.pdf", "application/pdf", new byte[0]))
                            .getMessage());
        }

        @Test
        @DisplayName("storeDocument : fichier null -> refusé")
        void storeDocument_fichierNull_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.storeDocument(seller, 5L, null, "a.pdf", "application/pdf", null));
        }

        @Test
        @DisplayName("storeDocument : fichier > 20 Mo -> refusé")
        void storeDocument_fichierTropGros_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.storeDocument(seller, 5L, null, "a.pdf", "application/pdf",
                            new byte[20 * 1024 * 1024 + 1]));
        }

        @Test
        @DisplayName("storeDocument : nom de fichier null -> refusé")
        void storeDocument_nomNull_refuse() {
            assertEquals("Nom de fichier invalide.",
                    assertThrows(IllegalArgumentException.class,
                            () -> service.storeDocument(seller, 5L, null, null, "application/pdf",
                                    new byte[] { 1, 2 }))
                            .getMessage());
        }

        @Test
        @DisplayName("storeDocument : extension interdite (.exe) -> refusée")
        void storeDocument_extensionInterdite_refusee() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.storeDocument(seller, 5L, null, "virus.exe", "application/octet-stream",
                            new byte[] { 1, 2 }));

            assertTrue(exception.getMessage().contains("Format non autorisé"));
        }

        @Test
        @DisplayName("storeDocument : fichier sans extension -> refusé")
        void storeDocument_sansExtension_refuse() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.storeDocument(seller, 5L, null, "facture", "application/pdf", new byte[] { 1 }));
        }

        @Test
        @DisplayName("storeDocument : sans dossier (listing ni request) -> refusé")
        void storeDocument_sansDossier_refuse() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.storeDocument(seller, null, null, "a.pdf", "application/pdf", new byte[] { 1 }));

            assertEquals("Un dossier de collecte ou un dossier NDA est requis.", exception.getMessage());
        }

        @Test
        @DisplayName("storeDocument : dépôt sur une annonce -> document persisté pour le gestionnaire")
        void storeDocument_surAnnonce_persisteLeDocument() {
            doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);
            org.mockito.ArgumentCaptor<SecureDocument> captor = org.mockito.ArgumentCaptor
                    .forClass(SecureDocument.class);

            var view = service.storeDocument(seller, 5L, null, "viabilité.pdf", "application/pdf",
                    new byte[] { 1, 2, 3 });

            verify(em()).persist(captor.capture());
            SecureDocument saved = captor.getValue();
            assertEquals("enc:viabilité.pdf", saved.originalName);
            assertEquals("application/pdf", saved.contentType);
            assertEquals(3, saved.sizeBytes);
            assertEquals(5L, saved.listingId);
            assertEquals(10L, saved.uploadedBy);
            assertNotNull(saved.storageKey);
            assertEquals("viabilité.pdf", view.name());
        }

        @Test
        @DisplayName("storeDocument : dépôt par un non-gestionnaire de l'annonce -> SecurityException")
        void storeDocument_nonGestionnaire_refuse() {
            doReturn(listing(5L, 10L, 30L, "PUBLISHED")).when(em()).find(Listing.class, 5L);

            assertThrows(SecurityException.class,
                    () -> service.storeDocument(anotherBroker, 5L, null, "a.pdf", "application/pdf", new byte[] { 1 }));
        }

        @Test
        @DisplayName("storeDocument : type MIME incohérent -> déduit de l'extension")
        void storeDocument_typeMimeIncoherent_deduitDeLExtension() {
            doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);

            service.storeDocument(seller, 5L, null, "photo.png", "text/html", new byte[] { 1 });

            org.mockito.ArgumentCaptor<SecureDocument> captor = org.mockito.ArgumentCaptor.forClass(SecureDocument.class);
            verify(em()).persist(captor.capture());
            assertEquals("image/png", captor.getValue().contentType,
                    "Un type MIME non autorisé doit être remplacé par celui déduit de l'extension");
        }

        @Test
        @DisplayName("documentsFor : non gestionnaire -> SecurityException")
        void documentsFor_nonGestionnaire_refuse() {
            doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);

            assertThrows(SecurityException.class, () -> service.documentsFor(anotherBroker, 5L));
        }

        @Test
        @DisplayName("documentsFor : gestionnaire -> liste des documents de collecte")
        void documentsFor_gestionnaire_retourneLaListe() {
            doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 3L;
            document.listingId = 5L;
            document.originalName = "enc:contrat.pdf";
            document.contentType = "application/pdf";
            document.sizeBytes = 10;
            document.uploadedAt = Instant.now();
            MockJpa.query(em(),
                    "select d from SecureDocument d where d.listingId = :id and d.requestId is null "
                            + "and d.documentPurpose is null order by d.uploadedAt desc",
                    List.of(document));

            var views = service.documentsFor(seller, 5L);

            assertEquals(1, views.size());
            assertEquals("contrat.pdf", views.get(0).name());
            assertFalse(views.get(0).encryptedNameUnavailable());
        }

        @Test
        @DisplayName("documentsFor : nom illisible (ancienne clé) -> indicateur + libellé d'indisponibilité")
        void documentsFor_nomIllisible_signaleLIndisponibilite() {
            doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 3L;
            document.originalName = "old:abc";
            document.contentType = "application/pdf";
            MockJpa.query(em(),
                    "select d from SecureDocument d where d.listingId = :id and d.requestId is null "
                            + "and d.documentPurpose is null order by d.uploadedAt desc",
                    List.of(document));

            var view = service.documentsFor(seller, 5L).get(0);

            assertTrue(view.encryptedNameUnavailable());
            assertEquals(UNAVAILABLE, view.name());
        }

        @Test
        @DisplayName("dealDocuments : NDA non signé -> SecurityException")
        void dealDocuments_ndaNonSigne_refuse() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, false, false));

            assertThrows(SecurityException.class, () -> service.dealDocuments(buyer, 7L));
        }

        @Test
        @DisplayName("dealDocuments : NDA signé -> liste des documents de la demande")
        void dealDocuments_ndaSigne_retourneLaListe() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, true, true));
            SecureDocument approved = MockJpa.newEntity(SecureDocument.class);
            approved.id = 1L;
            approved.requestId = 7L;
            approved.originalName = "enc:memorandum.pdf";
            approved.sellerAuthorized = true;
            approved.alpValidated = true;
            SecureDocument awaitingApproval = MockJpa.newEntity(SecureDocument.class);
            awaitingApproval.id = 2L;
            awaitingApproval.requestId = 7L;
            awaitingApproval.originalName = "enc:contrat.pdf";
            MockJpa.query(em(), "select d from SecureDocument d where d.requestId = :id order by d.uploadedAt desc",
                    List.of(approved, awaitingApproval));

            var documents = service.dealDocuments(buyer, 7L);

            assertEquals(1, documents.size());
            assertEquals("memorandum.pdf", documents.get(0).name());
            assertTrue(documents.get(0).sellerAuthorized());
            assertTrue(documents.get(0).alpValidated());
        }

        @Test
        @DisplayName("authorizeDealDocument : cédant propriétaire -> accès autorisé")
        void authorizeDealDocument_cedantAutoriseLePartage() {
            DealRequest signed = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), signed);
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.listingId = 5L;
            document.requestId = 7L;
            doReturn(document).when(em()).find(SecureDocument.class, 8L);

            service.authorizeDealDocument(seller, 8L, 45);

            assertTrue(document.sellerAuthorized);
            assertEquals(10L, document.sellerAuthorizedBy);
            assertNotNull(document.sellerAuthorizedAt);
            assertEquals(45, document.accessDurationDays);
            assertNull(document.accessExpiresAt);
        }

        @Test
        @DisplayName("authorizeDealDocument : durée hors limites -> refusée")
        void authorizeDealDocument_dureeHorsLimites_refusee() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.authorizeDealDocument(seller, 8L, 366));
        }

        @Test
        @DisplayName("validateDealDocument : le cédant doit d’abord autoriser le partage")
        void validateDealDocument_sansAutorisationCedant_refuse() {
            DealRequest signed = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing(5L, 10L, 30L, "PUBLISHED"), signed);
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.listingId = 5L;
            document.requestId = 7L;
            doReturn(document).when(em()).find(SecureDocument.class, 8L);
            UserView reviewer = new UserView(99L, "admin@alp.fr", "Admin", "ALP", "SUPER", false);

            assertThrows(IllegalArgumentException.class,
                    () -> service.validateDealDocument(reviewer, 8L));
            assertFalse(document.alpValidated);
        }

        @Test
        @DisplayName("validateDealDocument : administrateur -> document validé par ALP")
        void validateDealDocument_administrateurValideLePartage() {
            DealRequest signed = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing(5L, 10L, 30L, "PUBLISHED"), signed);
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.requestId = 7L;
            document.sellerAuthorized = true;
            document.accessDurationDays = 30;
            doReturn(document).when(em()).find(SecureDocument.class, 8L);
            MockJpa.query(em(), "select d from SecureDocument d where d.requestId = :id order by d.uploadedAt desc",
                    List.of(document));
            UserView reviewer = new UserView(99L, "admin@alp.fr", "Admin", "ALP", "SUPER", false);

            assertEquals(1, service.dealDocuments(reviewer, 7L).size());
            service.validateDealDocument(reviewer, 8L);

            assertTrue(document.alpValidated);
            assertEquals(99L, document.alpValidatedBy);
            assertNotNull(document.alpValidatedAt);
            assertEquals(30, document.accessDurationDays);
            assertEquals(document.alpValidatedAt.plusSeconds(30L * 24 * 60 * 60), document.accessExpiresAt);
        }

        @Test
        @DisplayName("validateDealDocument : ne prolonge pas une fenêtre encore active")
        void validateDealDocument_fenetreActive_nePeutPasEtreProlongee() {
            prepareDealAccess(listing(5L, 10L, 30L, "PUBLISHED"), deal(7L, 5L, 20L, true, true));
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.requestId = 7L;
            document.sellerAuthorized = true;
            document.alpValidated = true;
            document.accessDurationDays = 30;
            Instant expiresAt = Instant.now().plusSeconds(30L * 24 * 60 * 60);
            document.accessExpiresAt = expiresAt;
            doReturn(document).when(em()).find(SecureDocument.class, 8L);
            UserView reviewer = new UserView(99L, "admin@alp.fr", "Admin", "ALP", "SUPER", false);

            assertThrows(IllegalArgumentException.class, () -> service.validateDealDocument(reviewer, 8L));
            assertEquals(expiresAt, document.accessExpiresAt);
        }

        @Test
        @DisplayName("validateDealDocument : durée expirée -> nouvelle autorisation du cédant requise")
        void validateDealDocument_fenetreExpiree_exigeRenouvellementCedant() {
            prepareDealAccess(listing(5L, 10L, 30L, "PUBLISHED"), deal(7L, 5L, 20L, true, true));
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.requestId = 7L;
            document.sellerAuthorized = true;
            document.alpValidated = true;
            document.accessDurationDays = 30;
            document.accessExpiresAt = Instant.now().minusSeconds(1);
            doReturn(document).when(em()).find(SecureDocument.class, 8L);
            UserView reviewer = new UserView(99L, "admin@alp.fr", "Admin", "ALP", "SUPER", false);

            assertThrows(IllegalArgumentException.class, () -> service.validateDealDocument(reviewer, 8L));
            assertTrue(document.alpValidated);
        }

        @Test
        @DisplayName("readDocument : document non approuvé -> acheteur refusé")
        void readDocument_documentNonApprouve_refuseAcheteur() {
            DealRequest signed = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), signed);
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.listingId = 5L;
            document.requestId = 7L;
            document.originalName = "enc:contrat.pdf";
            doReturn(document).when(em()).find(SecureDocument.class, 8L);

            assertThrows(SecurityException.class, () -> service.readDocument(buyer, 8L));
        }

        @Test
        @DisplayName("readDocument : fenêtre expirée -> acheteur refusé")
        void readDocument_fenetreExpiree_refuseAcheteur() {
            DealRequest signed = deal(7L, 5L, 20L, true, true);
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), signed);
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.listingId = 5L;
            document.requestId = 7L;
            document.originalName = "enc:contrat.pdf";
            document.sellerAuthorized = true;
            document.alpValidated = true;
            document.accessExpiresAt = Instant.now().minusSeconds(1);
            doReturn(document).when(em()).find(SecureDocument.class, 8L);

            assertThrows(SecurityException.class, () -> service.readDocument(buyer, 8L));
        }

        @Test
        @DisplayName("readDocument : accès valide -> téléchargement journalisé")
        void readDocument_accesValide_journaliseLeTelechargement() throws Exception {
            String previousStorageDirectory = System.getProperty("PRIVATE_STORAGE_DIR");
            System.setProperty("PRIVATE_STORAGE_DIR", temporaryDirectory.toString());
            try {
                DealRequest signed = deal(7L, 5L, 20L, true, true);
                prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), signed);
                SecureDocument document = MockJpa.newEntity(SecureDocument.class);
                document.id = 8L;
                document.listingId = 5L;
                document.requestId = 7L;
                document.originalName = "enc:contrat.pdf";
                document.storageKey = "data-room-file";
                document.contentType = "application/pdf";
                document.sellerAuthorized = true;
                document.alpValidated = true;
                document.accessDurationDays = 30;
                document.accessExpiresAt = Instant.now().plusSeconds(30L * 24 * 60 * 60);
                doReturn(document).when(em()).find(SecureDocument.class, 8L);
                Files.write(temporaryDirectory.resolve("data-room-file.bin"),
                        CryptoService.encrypt(new byte[] { 1, 2, 3 }));

                var download = service.readDocument(buyer, 8L);

                assertEquals("contrat.pdf", download.name());
                assertTrue(java.util.Arrays.equals(new byte[] { 1, 2, 3 }, download.bytes()));
                org.mockito.ArgumentCaptor<DataRoomAccessLog> captor =
                        org.mockito.ArgumentCaptor.forClass(DataRoomAccessLog.class);
                verify(em()).persist(captor.capture());
                assertEquals(8L, captor.getValue().documentId);
                assertEquals(7L, captor.getValue().requestId);
                assertEquals(20L, captor.getValue().accessedBy);
                assertEquals("b@alp.fr", captor.getValue().accessedByEmail);
                assertNotNull(captor.getValue().accessedAt);
            } finally {
                if (previousStorageDirectory == null) {
                    System.clearProperty("PRIVATE_STORAGE_DIR");
                } else {
                    System.setProperty("PRIVATE_STORAGE_DIR", previousStorageDirectory);
                }
            }
        }

        @Test
        @DisplayName("dataRoomAccessLogs : cédant propriétaire -> historique du dossier")
        void dataRoomAccessLogs_cedantConsulteHistorique() {
            prepareDealAccess(listing(5L, 10L, 30L, "PUBLISHED"), deal(7L, 5L, 20L, true, true));
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 8L;
            document.originalName = "enc:contrat.pdf";
            doReturn(document).when(em()).find(SecureDocument.class, 8L);
            DataRoomAccessLog access = MockJpa.newEntity(DataRoomAccessLog.class);
            access.documentId = 8L;
            access.requestId = 7L;
            access.accessedByEmail = "b@alp.fr";
            access.accessedAt = Instant.now();
            MockJpa.query(em(),
                    "select a from DataRoomAccessLog a where a.requestId = :requestId "
                            + "order by a.accessedAt desc",
                    List.of(access));

            var history = service.dataRoomAccessLogs(seller, 7L);

            assertEquals(1, history.size());
            assertEquals("contrat.pdf", history.get(0).documentName());
            assertEquals("b@alp.fr", history.get(0).accessedByEmail());
        }

        @Test
        @DisplayName("dataRoomAccessLogs : acquéreur non autorisé à consulter l’historique global")
        void dataRoomAccessLogs_acheteur_refuse() {
            prepareDealAccess(listing(5L, 10L, null, "PUBLISHED"), deal(7L, 5L, 20L, true, true));

            assertThrows(SecurityException.class, () -> service.dataRoomAccessLogs(buyer, 7L));
        }

        @Test
        @DisplayName("readDocument : document inexistant -> refusé")
        void readDocument_inexistant_refuse() {
            doReturn(null).when(em()).find(SecureDocument.class, 4L);

            assertEquals("Document introuvable.",
                    assertThrows(IllegalArgumentException.class, () -> service.readDocument(seller, 4L))
                            .getMessage());
        }

        @Test
        @DisplayName("readDocument : document de collecte par un non-gestionnaire -> SecurityException")
        void readDocument_nonGestionnaire_refuse() {
            SecureDocument document = MockJpa.newEntity(SecureDocument.class);
            document.id = 4L;
            document.listingId = 5L;
            document.originalName = "enc:a.pdf";
            document.storageKey = "uuid";
            doReturn(document).when(em()).find(SecureDocument.class, 4L);
            doReturn(listing(5L, 10L, null, "PUBLISHED")).when(em()).find(Listing.class, 5L);

            assertThrows(SecurityException.class, () -> service.readDocument(buyer, 4L));
        }
    }

    @Nested
    @DisplayName("Suivi du processus de cession")
    class SuiviCession {

        @Test
        @DisplayName("avance d'une étape : courtier mandaté actualise le suivi partagé")
        void avanceEtape_courtierMandate_enregistreLaTransition() {
            Listing listing = listing(5L, 10L, 30L, "PUBLISHED");
            ListingSaleProcess process = new ListingSaleProcess();
            process.listingId = listing.id;
            process.stage = SaleProcessStage.LISTING_PUBLICATION.name();
            process.updatedAt = Instant.now();
            doReturn(listing).when(em()).find(Listing.class, listing.id);
            MockJpa.query(em(), "select p from ListingSaleProcess p where p.listingId = :listingId",
                    List.of(process));

            service.advanceListingSaleProcess(broker, listing.id);

            assertEquals(SaleProcessStage.BUYER_INTEREST.name(), process.stage);
            assertEquals(broker.id(), process.updatedBy);
            verify(em()).persist(org.mockito.ArgumentMatchers.argThat(entity ->
                    entity instanceof ListingSaleProcessHistory history
                            && SaleProcessStage.LISTING_PUBLICATION.name().equals(history.fromStage)
                            && SaleProcessStage.BUYER_INTEREST.name().equals(history.toStage)
                            && broker.id().equals(history.changedBy)));
        }

        @Test
        @DisplayName("avance d'une étape : cédant en consultation seule")
        void avanceEtape_cedant_refuse() {
            assertThrows(SecurityException.class,
                    () -> service.advanceListingSaleProcess(seller, 5L));
        }

        @Test
        @DisplayName("avance d'une étape : processus clôturé non modifiable")
        void avanceEtape_processusCloture_refuse() {
            Listing listing = listing(5L, 10L, 30L, "PUBLISHED");
            ListingSaleProcess process = new ListingSaleProcess();
            process.listingId = listing.id;
            process.stage = SaleProcessStage.COMPLETED.name();
            process.updatedAt = Instant.now();
            doReturn(listing).when(em()).find(Listing.class, listing.id);
            MockJpa.query(em(), "select p from ListingSaleProcess p where p.listingId = :listingId",
                    List.of(process));

            assertThrows(IllegalArgumentException.class,
                    () -> service.advanceListingSaleProcess(broker, listing.id));
            assertEquals(SaleProcessStage.COMPLETED.name(), process.stage);
        }
    }
}
