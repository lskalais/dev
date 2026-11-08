package fr.alp.confidentia.service;

import fr.alp.confidentia.model.Listing;
import fr.alp.confidentia.model.UserAccount;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
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
    }

    // =============================================================================================
    // Administration
    // =============================================================================================

    @Nested
    @DisplayName("Administration")
    class Administration {

        private static final String LIST_QUERY = "select u from UserAccount u order by u.createdAt desc, u.id desc";

        @Test
        @DisplayName("usersForAdministration : non super utilisateur -> SecurityException")
        void usersForAdministration_nonSuper_refuse() {
            assertThrows(SecurityException.class, () -> service.usersForAdministration(seller));
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
            assertNull(views.get(0).companyName());
            assertNull(views.get(0).exactLocation());
        }
    }
}
