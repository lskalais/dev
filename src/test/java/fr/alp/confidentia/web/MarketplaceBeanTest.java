package fr.alp.confidentia.web;

import fr.alp.confidentia.service.MarketplaceService;
import fr.alp.confidentia.service.ViewModels.ListingEditView;
import fr.alp.confidentia.service.ViewModels.ListingSaveResult;
import fr.alp.confidentia.service.ViewModels.UserView;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du backing bean JSF {@link MarketplaceBean}.
 * <p>
 * {@code FacesContext.getCurrentInstance()} est une méthode statique : elle est
 * simulée avec
 * {@code Mockito.mockStatic}. Le service est un mock Mockito injecté par
 * réflexion.
 * Cela permet de vérifier les messages ajoutés (info/erreur), les redirections
 * de navigation
 * et les appels métier, sans conteneur JSF.
 */
@DisplayName("MarketplaceBean - backing bean JSF")
class MarketplaceBeanTest {

    private MarketplaceBean bean;
    private MarketplaceService service;
    private FacesContext facesContext;
    private ExternalContext externalContext;
    private HttpServletRequest request;
    private HttpSession session;
    private MockedStatic<FacesContext> staticFaces;

    @BeforeEach
    void setUp() throws Exception {
        bean = new MarketplaceBean();
        service = mock(MarketplaceService.class);
        facesContext = mock(FacesContext.class);
        externalContext = mock(ExternalContext.class);
        request = mock(HttpServletRequest.class);
        session = mock(HttpSession.class);

        Field field = MarketplaceBean.class.getDeclaredField("service");
        field.setAccessible(true);
        field.set(bean, service);

        when(facesContext.getExternalContext()).thenReturn(externalContext);
        when(externalContext.getRequest()).thenReturn(request);
        when(externalContext.getRequestMap()).thenReturn(new java.util.HashMap<String, Object>());
        when(request.getSession()).thenReturn(session);
        when(request.getContextPath()).thenReturn("/confidentia");

        staticFaces = Mockito.mockStatic(FacesContext.class);
        staticFaces.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
    }

    @AfterEach
    void tearDown() {
        staticFaces.close();
    }

    /**
     * Injecte l'utilisateur connecté dans le bean (simule l'état de session
     * reconstruit).
     */
    private void connecter(UserView user) throws Exception {
        Field field = MarketplaceBean.class.getDeclaredField("user");
        field.setAccessible(true);
        field.set(bean, user);
    }

    /** Récupère le dernier message ajouté au FacesContext. */
    private FacesMessage lastMessage() {
        ArgumentCaptor<FacesMessage> captor = ArgumentCaptor.forClass(FacesMessage.class);
        verify(facesContext, atLeastOnce()).addMessage(eq(null), captor.capture());
        return captor.getValue();
    }

    private void assertError(String expected) {
        FacesMessage message = lastMessage();
        assertEquals(FacesMessage.SEVERITY_ERROR, message.getSeverity());
        assertEquals(expected, message.getSummary());
    }

    private void assertInfo(String expected) {
        FacesMessage message = lastMessage();
        assertEquals(FacesMessage.SEVERITY_INFO, message.getSeverity());
        assertEquals(expected, message.getSummary());
    }

    // =============================================================================================
    // Authentification
    // =============================================================================================

    @Nested
    @DisplayName("register / login / logout")
    class Authentification {

        @Test
        @DisplayName("register : succès -> redirection vers app.xhtml et session renouvelée")
        void register_succes_redirigeVersAppEtRenouvelleLaSession() throws Exception {
            UserView created = new UserView(1L, "a@alp.fr", "Alice", "ALP", "BUYER", false);
            bean.setEmail("a@alp.fr");
            bean.setPassword("MotDePasse!2026");
            bean.setFullName("Alice");
            bean.setOrganization("ALP");
            bean.setRole("BUYER");
            when(service.register("a@alp.fr", "MotDePasse!2026", "Alice", "ALP",
                    fr.alp.confidentia.model.Role.BUYER)).thenReturn(created);

            String outcome = bean.register();

            assertEquals("/app.xhtml?faces-redirect=true", outcome);
            assertEquals(created, bean.getUser());
            verify(request).changeSessionId(); // anti fixation de session
            verify(session).setAttribute("authenticatedUser", created);
            assertEquals("", bean.getPassword(), "Le mot de passe ne doit pas rester en mémoire");
        }

        @Test
        @DisplayName("register : courtier sans organisation -> message d'erreur, pas d'appel au service")
        void register_courtierSansOrganisation_afficheUneErreur() {
            bean.setRole("BROKER");
            bean.setOrganization("  ");

            String outcome = bean.register();

            assertNull(outcome);
            assertError("Un cabinet ou une organisation est requis pour le profil courtier.");
            verify(service, never()).register(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("register : rôle inconnu -> IllegalArgumentException interceptée")
        void register_roleInconnu_afficheUneErreur() {
            bean.setRole("PIRATE");

            assertNull(bean.register());
            assertEquals(FacesMessage.SEVERITY_ERROR, lastMessage().getSeverity());
        }

        @Test
        @DisplayName("register : règle métier violée par le service -> message d'erreur affiché")
        void register_serviceRefuse_afficheLErreurDuService() {
            bean.setRole("BUYER");
            when(service.register(any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Cette adresse courriel est déjà utilisée."));

            assertNull(bean.register());
            assertError("Cette adresse courriel est déjà utilisée.");
        }

        @Test
        @DisplayName("login : succès -> app.xhtml et session marquée authentifiée")
        void login_succes_redirigeVersApp() {
            UserView user = new UserView(2L, "b@alp.fr", "Bob", "ALP", "SELLER", false);
            bean.setEmail("b@alp.fr");
            bean.setPassword("MotDePasse!2026");
            when(service.authenticate("b@alp.fr", "MotDePasse!2026")).thenReturn(user);

            assertEquals("/app.xhtml?faces-redirect=true", bean.login());
            verify(session).setAttribute("authenticatedUser", user);
        }

        @Test
        @DisplayName("login : changement de mot de passe requis -> redirection vers change-password.xhtml")
        void login_motDePasseTemporaire_redirigeVersChangePassword() {
            UserView user = new UserView(2L, "b@alp.fr", "Bob", "ALP", "SELLER", true);
            when(service.authenticate(any(), any())).thenReturn(user);

            assertEquals("/change-password.xhtml?faces-redirect=true", bean.login());
        }

        @Test
        @DisplayName("login : identifiants invalides -> message d'erreur, pas de session")
        void login_identifiantsInvalides_afficheUneErreur() {
            when(service.authenticate(any(), any()))
                    .thenThrow(new IllegalArgumentException("Adresse courriel ou mot de passe invalide."));

            assertNull(bean.login());
            assertError("Adresse courriel ou mot de passe invalide.");
            verify(session, never()).setAttribute(anyString(), any());
        }

        @Test
        @DisplayName("logout : invalide la session et redirige vers login.xhtml")
        void logout_sessionExistante_invalideEtRedirige() {
            assertDoesNotThrow(() -> connecter(new UserView(1L, "a@alp.fr", "A", null, "BUYER", false)));

            assertEquals("/login.xhtml?faces-redirect=true", bean.logout());
            verify(session).invalidate();
            assertNull(bean.getUser());
        }

        @Test
        @DisplayName("logout : sans session, ne lève rien et nettoie l'utilisateur")
        void logout_sansSession_nettoieLUtilisateur() throws Exception {
            when(request.getSession()).thenReturn(null);
            connecter(new UserView(1L, "a@alp.fr", "A", null, "BUYER", false));

            assertEquals("/login.xhtml?faces-redirect=true", bean.logout());
            assertNull(bean.getUser());
        }
    }

    // =============================================================================================
    // Actions métier (runAction)
    // =============================================================================================

    @Nested
    @DisplayName("Actions métier")
    class ActionsMetier {

        @Test
        @DisplayName("saveCriteria : succès -> message de confirmation")
        void saveCriteria_succes_afficheConfirmation() throws Exception {
            UserView buyer = new UserView(1L, "b@alp.fr", "B", null, "BUYER", false);
            connecter(buyer);
            bean.setSectors("Agro, Tech");
            bean.setPreferredRegion("Abidjan");
            bean.setMinimumInvestment(new BigDecimal("1000"));
            bean.setMaximumInvestment(new BigDecimal("5000"));

            bean.saveCriteria();

            verify(service).saveBuyerCriteria(buyer, "Agro, Tech", "Abidjan",
                    new BigDecimal("1000"), new BigDecimal("5000"));
            assertInfo("Critères de recherche enregistrés.");
        }

        @Test
        @DisplayName("saveCriteria : erreur métier -> message d'erreur et pas d'exception")
        void saveCriteria_erreurMetier_afficheUneErreur() throws Exception {
            connecter(new UserView(1L, "b@alp.fr", "B", null, "BUYER", false));
            Mockito.doThrow(new IllegalArgumentException("Le budget minimum ne peut dépasser le budget maximum."))
                    .when(service).saveBuyerCriteria(any(), any(), any(), any(), any());

            bean.saveCriteria();

            assertError("Le budget minimum ne peut dépasser le budget maximum.");
        }

        @Test
        @DisplayName("requestIntroduction : succès -> message de confidentialité")
        void requestIntroduction_succes_afficheConfirmation() throws Exception {
            UserView buyer = new UserView(1L, "b@alp.fr", "B", null, "BUYER", false);
            connecter(buyer);

            bean.requestIntroduction(5L);

            verify(service).requestIntroduction(buyer, 5L);
            assertInfo("Demande envoyée. L'identité reste masquée.");
        }

        @Test
        @DisplayName("acceptIntroduction : appelle decideIntroduction avec accept=true")
        void acceptIntroduction_appelleLeServiceAvecAcceptation() throws Exception {
            UserView seller = new UserView(2L, "s@alp.fr", "S", null, "SELLER", false);
            connecter(seller);

            bean.acceptIntroduction(9L);

            verify(service).decideIntroduction(seller, 9L, true);
        }

        @Test
        @DisplayName("declineIntroduction : appelle decideIntroduction avec accept=false")
        void declineIntroduction_appelleLeServiceAvecRefus() throws Exception {
            UserView seller = new UserView(2L, "s@alp.fr", "S", null, "SELLER", false);
            connecter(seller);

            bean.declineIntroduction(9L);

            verify(service).decideIntroduction(seller, 9L, false);
        }

        @Test
        @DisplayName("signNda : succès -> signature réinitialisée")
        void signNda_succes_reinitialiseLaSignature() throws Exception {
            connecter(new UserView(1L, "b@alp.fr", "B", null, "BUYER", false));
            bean.setSignatureName("Jean Dupont");

            bean.signNda(3L);

            verify(service).signNda(any(), eq(3L), eq("Jean Dupont"));
            assertEquals("", bean.getSignatureName());
        }

        @Test
        @DisplayName("signNda : refus du service -> signature conservée pour correction")
        void signNda_echec_conserveLaSignature() throws Exception {
            connecter(new UserView(1L, "b@alp.fr", "B", null, "BUYER", false));
            bean.setSignatureName("X");
            Mockito.doThrow(new IllegalArgumentException("Saisissez votre nom complet comme signature."))
                    .when(service).signNda(any(), any(), any());

            bean.signNda(3L);

            assertEquals("X", bean.getSignatureName());
            assertError("Saisissez votre nom complet comme signature.");
        }

        @Test
        @DisplayName("sendMessage : succès -> message vidé et dossier actif défini")
        void sendMessage_succes_videLeMessageEtOuvreLeDossier() throws Exception {
            connecter(new UserView(1L, "b@alp.fr", "B", null, "BUYER", false));
            bean.setMessage("Bonjour, je souhaite en savoir plus.");

            bean.sendMessage(4L);

            assertEquals("", bean.getMessage());
            assertEquals(4L, bean.getActiveDealId());
            assertInfo("Message chiffré envoyé.");
        }

        @Test
        @DisplayName("acceptListing : succès -> message courtier")
        void acceptListing_succes_afficheConfirmation() throws Exception {
            UserView broker = new UserView(3L, "c@alp.fr", "C", "Cabinet", "BROKER", false);
            connecter(broker);

            bean.acceptListing(8L);

            verify(service).acceptListing(broker, 8L);
            assertInfo(
                    "Dossier accepté. Vous en êtes le courtier mandaté et le teaser n'est plus modifiable par le cédant.");
        }

        @Test
        @DisplayName("processFinancials : succès -> les champs financiers du formulaire sont vidés")
        void processFinancials_succes_videLeFormulaire() throws Exception {
            connecter(new UserView(3L, "c@alp.fr", "C", null, "BROKER", false));
            bean.setProcessedRevenue(new BigDecimal("1000"));
            bean.setProcessedEbitda(new BigDecimal("200"));
            bean.setValuationMultiple(new BigDecimal("5"));

            bean.processFinancials(7L);

            verify(service).processFinancials(any(), eq(7L), eq(new BigDecimal("1000")),
                    eq(new BigDecimal("200")), eq(new BigDecimal("5")));
            assertNull(bean.getProcessedRevenue());
            assertNull(bean.getProcessedEbitda());
            assertNull(bean.getValuationMultiple());
        }

        @Test
        @DisplayName("processFinancials : échec -> les champs sont conservés pour correction")
        void processFinancials_echec_conserveLesChamps() throws Exception {
            connecter(new UserView(3L, "c@alp.fr", "C", null, "BROKER", false));
            bean.setProcessedRevenue(new BigDecimal("1000"));
            Mockito.doThrow(new IllegalArgumentException("Le multiple de valorisation doit être entre 0 et 30."))
                    .when(service).processFinancials(any(), any(), any(), any(), any());

            bean.processFinancials(7L);

            assertEquals(new BigDecimal("1000"), bean.getProcessedRevenue());
            assertError("Le multiple de valorisation doit être entre 0 et 30.");
        }

        @Test
        @DisplayName("runAction : SecurityException est aussi convertie en message d'erreur")
        void action_securityException_afficheUneErreur() throws Exception {
            connecter(new UserView(1L, "b@alp.fr", "B", null, "BUYER", false));
            Mockito.doThrow(new SecurityException("Cette action n'est pas autorisée pour votre profil."))
                    .when(service).requestIntroduction(any(), any());

            bean.requestIntroduction(1L);

            assertError("Cette action n'est pas autorisée pour votre profil.");
        }

        @Test
        @DisplayName("runAction : IllegalStateException est aussi convertie en message d'erreur")
        void action_illegalStateException_afficheUneErreur() throws Exception {
            connecter(new UserView(1L, "b@alp.fr", "B", null, "BUYER", false));
            Mockito.doThrow(new IllegalStateException("Base indisponible"))
                    .when(service).requestIntroduction(any(), any());

            bean.requestIntroduction(1L);

            assertError("Base indisponible");
        }
    }

    // =============================================================================================
    // Annonces
    // =============================================================================================

    @Nested
    @DisplayName("Annonces")
    class Annonces {

        @Test
        @DisplayName("saveListing : succès avec courtier -> message spécifique et sélection mémorisée")
        void saveListing_succesAvecCourtier_brokerAssigned() throws Exception {
            connecter(new UserView(2L, "s@alp.fr", "S", null, "SELLER", false));
            bean.setBrokerEmail("c@alp.fr");
            bean.setSector("Agro");
            bean.setRegion("Abidjan");
            bean.setDescription("Boulangerie rentable");
            when(service.saveListing(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ListingSaveResult(55L, true));

            bean.saveListing();

            assertEquals(55L, bean.getSelectedListingId());
            assertInfo(
                    "Teaser publié anonymement. Vous pouvez le modifier jusqu'à ce que le courtier accepte le dossier.");
        }

        @Test
        @DisplayName("saveListing : succès sans courtier -> message sans attribution")
        void saveListing_succesSansCourtier_brokerNotAssigned() throws Exception {
            connecter(new UserView(2L, "s@alp.fr", "S", null, "SELLER", false));
            when(service.saveListing(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ListingSaveResult(56L, false));

            bean.saveListing();

            assertEquals(56L, bean.getSelectedListingId());
            assertInfo(
                    "Teaser publié anonymement sans courtier attribué. Vous pouvez le modifier jusqu'à sa prise en charge par un courtier.");
        }

        @Test
        @DisplayName("saveListing : erreur de validation -> message d'erreur, sélection inchangée")
        void saveListing_erreurDeValidation_afficheUneErreur() throws Exception {
            connecter(new UserView(2L, "s@alp.fr", "S", null, "SELLER", false));
            when(service.saveListing(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(
                            new IllegalArgumentException("Le secteur, la description et la région sont obligatoires."));

            bean.saveListing();

            assertNull(bean.getSelectedListingId());
            assertError("Le secteur, la description et la région sont obligatoires.");
        }

        @Test
        @DisplayName("saveListing : erreur de chiffrement (IllegalStateException) -> message d'erreur")
        void saveListing_erreurDeChiffrement_afficheUneErreur() throws Exception {
            connecter(new UserView(2L, "s@alp.fr", "S", null, "SELLER", false));
            when(service.saveListing(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException(
                            "La variable APP_ENCRYPTION_KEY (clé Base64 de 32 octets) est requise."));

            bean.saveListing();

            assertError("La variable APP_ENCRYPTION_KEY (clé Base64 de 32 octets) est requise.");
        }

        @Test
        @DisplayName("loadListingForEdit : copie tous les champs du formulaire")
        void loadListingForEdit_copieLesChamps() throws Exception {
            connecter(new UserView(2L, "s@alp.fr", "S", null, "SELLER", false));
            ListingEditView view = new ListingEditView(10L, "c@alp.fr", "Agro", "Tech", 1990,
                    "Desc", "Abidjan", "PME", "Marque", "Retraite", new BigDecimal("1000"),
                    new BigDecimal("100"), new BigDecimal("900"), new BigDecimal("10"),
                    "Conditions", "12 mois", "Société X", "Rue Y");
            when(service.listingForEditing(any(), eq(10L))).thenReturn(view);

            bean.loadListingForEdit(10L);

            assertEquals(10L, bean.getSelectedListingId());
            assertEquals("c@alp.fr", bean.getBrokerEmail());
            assertEquals("Agro", bean.getSector());
            assertEquals("Tech", bean.getSecondarySector());
            assertEquals(1990, bean.getFoundedYear());
            assertEquals("Desc", bean.getDescription());
            assertEquals("Abidjan", bean.getRegion());
            assertEquals("PME", bean.getCustomerSummary());
            assertEquals("Marque", bean.getAdvantages());
            assertEquals("Retraite", bean.getSaleReason());
            assertEquals(new BigDecimal("1000"), bean.getRevenue());
            assertEquals(new BigDecimal("100"), bean.getEbitda());
            assertEquals(new BigDecimal("900"), bean.getAskingPrice());
            assertEquals(new BigDecimal("10"), bean.getProfitMargin());
            assertEquals("Conditions", bean.getSaleConditions());
            assertEquals("12 mois", bean.getSupportPeriod());
            assertEquals("Société X", bean.getCompanyName());
            assertEquals("Rue Y", bean.getExactLocation());
            assertInfo("Teaser chargé dans le formulaire de modification.");
        }

        @Test
        @DisplayName("loadListingForEdit : dossier accepté -> message d'erreur")
        void loadListingForEdit_dossierAccepte_afficheUneErreur() throws Exception {
            connecter(new UserView(2L, "s@alp.fr", "S", null, "SELLER", false));
            when(service.listingForEditing(any(), any()))
                    .thenThrow(new IllegalArgumentException(
                            "Ce dossier a été accepté par un courtier et ne peut plus être modifié."));

            bean.loadListingForEdit(10L);

            assertError("Ce dossier a été accepté par un courtier et ne peut plus être modifié.");
        }

        @Test
        @DisplayName("startNewListing : vide tous les champs du formulaire d'annonce")
        void startNewListing_videLeFormulaire() throws Exception {
            bean.setSector("Agro");
            bean.setDescription("Desc");
            bean.setRevenue(new BigDecimal("10"));

            bean.startNewListing();

            assertNull(bean.getSector());
            assertNull(bean.getDescription());
            assertNull(bean.getRevenue());
            assertNull(bean.getSelectedListingId());
        }

        @Test
        @DisplayName("getListings : sans utilisateur -> liste vide (pas d'appel au service)")
        void getListings_sansUtilisateur_retourneListeVide() {
            assertTrue(bean.getListings().isEmpty());
            verify(service, never()).listingsFor(any(), any(), any(), any());
        }

        @Test
        @DisplayName("getListings : avec utilisateur -> délègue au service avec les filtres")
        void getListings_avecUtilisateur_delegueAvecFiltres() throws Exception {
            UserView buyer = new UserView(1L, "b@alp.fr", "B", null, "BUYER", false);
            connecter(buyer);
            bean.setSectorFilter("Agro");
            bean.setRegionFilter("Abidjan");
            bean.setMaximumPriceFilter(new BigDecimal("500"));

            List<?> result = bean.getListings();

            assertNotNull(result);
            verify(service).listingsFor(buyer, "Agro", "Abidjan", new BigDecimal("500"));
        }
    }

    // =============================================================================================
    // Administration
    // =============================================================================================

    @Nested
    @DisplayName("Administration")
    class Administration {

        @Test
        @DisplayName("createManagedUser : succès -> formulaire réinitialisé")
        void createManagedUser_succes_reinitialiseLeFormulaire() throws Exception {
            UserView admin = new UserView(1L, "super@alp.fr", "Admin", null, "SUPER", false);
            connecter(admin);
            bean.setEmail("n@alp.fr");
            bean.setPassword("MotDePasse!2026");
            bean.setFullName("Nouveau");
            bean.setOrganization("ALP");
            bean.setRole("SELLER");

            bean.createManagedUser();

            verify(service).createManagedUser(admin, "n@alp.fr", "MotDePasse!2026", "Nouveau", "ALP",
                    fr.alp.confidentia.model.Role.SELLER);
            assertEquals("", bean.getEmail());
            assertEquals("", bean.getFullName());
            assertEquals("BUYER", bean.getRole());
            assertInfo(
                    "Le compte a été créé. L'utilisateur devra changer son mot de passe temporaire à sa première connexion.");
        }

        @Test
        @DisplayName("createManagedUser : refus du service -> message d'erreur, formulaire conservé")
        void createManagedUser_refus_afficheUneErreur() throws Exception {
            connecter(new UserView(1L, "super@alp.fr", "Admin", null, "SUPER", false));
            bean.setEmail("n@alp.fr");
            bean.setRole("BROKER");
            Mockito.doThrow(
                    new IllegalArgumentException("Un cabinet ou une organisation est requis pour le profil courtier."))
                    .when(service).createManagedUser(any(), any(), any(), any(), any(), any());

            bean.createManagedUser();

            assertEquals("n@alp.fr", bean.getEmail());
            assertError("Un cabinet ou une organisation est requis pour le profil courtier.");
        }

        @Test
        @DisplayName("createManagedUser : rôle inconnu -> IllegalArgumentException interceptée")
        void createManagedUser_roleInconnu_afficheUneErreur() throws Exception {
            connecter(new UserView(1L, "super@alp.fr", "Admin", null, "SUPER", false));
            bean.setRole("ROOT");

            bean.createManagedUser();

            assertEquals(FacesMessage.SEVERITY_ERROR, lastMessage().getSeverity());
            verify(service, never()).createManagedUser(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("assignTemporaryPassword : sur son propre compte -> redirection vers change-password")
        void assignTemporaryPassword_surSoiMeme_redirigeVersChangePassword() throws Exception {
            UserView admin = new UserView(1L, "super@alp.fr", "Admin", null, "SUPER", false);
            connecter(admin);
            bean.setTemporaryPassword("Temporaire!2026");

            String outcome = bean.assignTemporaryPassword(1L);

            assertEquals("/change-password.xhtml?faces-redirect=true", outcome);
            assertEquals("", bean.getTemporaryPassword());
            assertTrue(bean.getUser().passwordChangeRequired(),
                    "L'utilisateur courant doit désormais être prié de changer son mot de passe");
            verify(session).setAttribute(eq("authenticatedUser"), any(UserView.class));
        }

        @Test
        @DisplayName("assignTemporaryPassword : sur un autre compte -> aucun changement de vue")
        void assignTemporaryPassword_surUnAutreCompte_aucuneRedirection() throws Exception {
            UserView admin = new UserView(1L, "super@alp.fr", "Admin", null, "SUPER", false);
            connecter(admin);
            bean.setTemporaryPassword("Temporaire!2026");

            assertNull(bean.assignTemporaryPassword(2L));
            assertEquals("", bean.getTemporaryPassword());
        }

        @Test
        @DisplayName("assignTemporaryPassword : échec -> mot de passe conservé, pas de redirection")
        void assignTemporaryPassword_echec_conserveLeMotDePasse() throws Exception {
            connecter(new UserView(1L, "super@alp.fr", "Admin", null, "SUPER", false));
            bean.setTemporaryPassword("court");
            Mockito.doThrow(new IllegalArgumentException(
                    "Le mot de passe doit contenir au moins 12 caractères et au plus 72 octets UTF-8."))
                    .when(service).assignTemporaryPassword(any(), any(), any());

            assertNull(bean.assignTemporaryPassword(2L));
            assertEquals("court", bean.getTemporaryPassword());
        }

        @Test
        @DisplayName("getAdminUsers : non super utilisateur -> liste vide")
        void getAdminUsers_nonSuper_retourneListeVide() throws Exception {
            connecter(new UserView(1L, "s@alp.fr", "S", null, "SELLER", false));
            when(service.isSuperUser(any())).thenReturn(false);

            assertTrue(bean.getAdminUsers().isEmpty());
            verify(service, never()).usersForAdministration(any());
        }

        @Test
        @DisplayName("getAdminUsers : super utilisateur -> délègue au service")
        void getAdminUsers_super_delegueAuService() throws Exception {
            UserView admin = new UserView(1L, "super@alp.fr", "Admin", null, "SUPER", false);
            connecter(admin);
            when(service.isSuperUser(admin)).thenReturn(true);
            when(service.usersForAdministration(admin)).thenReturn(List.of());

            assertTrue(bean.getAdminUsers().isEmpty());
            verify(service).usersForAdministration(admin);
        }

        @Test
        @DisplayName("isSuperUser : délègue au service, null compris")
        void isSuperUser_delegueAuService() {
            when(service.isSuperUser(null)).thenReturn(false);

            assertFalse(bean.isSuperUser());
            verify(service).isSuperUser(null);
        }
    }

    // =============================================================================================
    // Changement de mot de passe
    // =============================================================================================

    @Nested
    @DisplayName("Changement de mot de passe")
    class ChangementDeMotDePasse {

        @Test
        @DisplayName("changeTemporaryPassword : confirmation différente -> erreur, service non appelé")
        void changeTemporaryPassword_confirmationDifferente_afficheUneErreur() {
            bean.setNewPassword("Nouveau!2026A");
            bean.setConfirmedNewPassword("Nouveau!2026B");

            assertNull(bean.changeTemporaryPassword());
            assertError("Les deux nouveaux mots de passe ne correspondent pas.");
            verify(service, never()).changeTemporaryPassword(any(), any());
        }

        @Test
        @DisplayName("changeTemporaryPassword : nouveau mot de passe null -> erreur")
        void changeTemporaryPassword_motDePasseNull_afficheUneErreur() {
            bean.setNewPassword(null);
            bean.setConfirmedNewPassword(null);

            assertNull(bean.changeTemporaryPassword());
            assertError("Les deux nouveaux mots de passe ne correspondent pas.");
        }

        @Test
        @DisplayName("changeTemporaryPassword : succès -> redirection vers app et champs vidés")
        void changeTemporaryPassword_succes_redirigeVersApp() {
            UserView updated = new UserView(1L, "a@alp.fr", "A", null, "BUYER", false);
            when(service.changeTemporaryPassword(any(), eq("Nouveau!2026"))).thenReturn(updated);
            bean.setNewPassword("Nouveau!2026");
            bean.setConfirmedNewPassword("Nouveau!2026");

            assertEquals("/app.xhtml?faces-redirect=true", bean.changeTemporaryPassword());
            assertEquals(updated, bean.getUser());
            assertEquals("", bean.getNewPassword());
            assertEquals("", bean.getConfirmedNewPassword());
            assertInfo("Votre mot de passe a été remplacé.");
        }

        @Test
        @DisplayName("changeTemporaryPassword : refus métier -> message d'erreur")
        void changeTemporaryPassword_refusMetier_afficheUneErreur() {
            when(service.changeTemporaryPassword(any(), any()))
                    .thenThrow(new IllegalArgumentException(
                            "Le nouveau mot de passe doit être différent du mot de passe temporaire."));
            bean.setNewPassword("Identique!2026");
            bean.setConfirmedNewPassword("Identique!2026");

            assertNull(bean.changeTemporaryPassword());
            assertError("Le nouveau mot de passe doit être différent du mot de passe temporaire.");
        }
    }

    // =============================================================================================
    // Divers : refresh, sessions, cache, CSRF, labels
    // =============================================================================================

    @Nested
    @DisplayName("Refresh, session et divers")
    class Divers {

        @Test
        @DisplayName("refresh : reconstruit l'utilisateur depuis la session et enregistre les vues pour un acheteur")
        void refresh_acheteur_rechargeEtEnregistreLesVues() {
            UserView buyer = new UserView(1L, "b@alp.fr", "B", null, "BUYER", false);
            when(session.getAttribute("authenticatedUser")).thenReturn(buyer);

            bean.refresh();

            assertEquals(buyer, bean.getUser());
            verify(service).recordMarketplaceViews(buyer);
        }

        @Test
        @DisplayName("refresh : un cédant n'enregistre pas les vues")
        void refresh_cedant_nEnregistrePasLesVues() {
            when(session.getAttribute("authenticatedUser"))
                    .thenReturn(new UserView(2L, "s@alp.fr", "S", null, "SELLER", false));

            bean.refresh();

            assertNotNull(bean.getUser());
            verify(service, never()).recordMarketplaceViews(any());
        }

        @Test
        @DisplayName("refresh : session vide -> aucun utilisateur, aucun appel")
        void refresh_sessionVide_neFaitRien() {
            when(session.getAttribute("authenticatedUser")).thenReturn(null);

            bean.refresh();

            assertNull(bean.getUser());
            verify(service, never()).recordMarketplaceViews(any());
        }

        @Test
        @DisplayName("getDeals : sans utilisateur -> liste vide")
        void getDeals_sansUtilisateur_retourneListeVide() {
            assertTrue(bean.getDeals().isEmpty());
            verify(service, never()).dealsFor(any());
        }

        @Test
        @DisplayName("getDeals : le résultat est mis en cache dans la requête (un seul appel au service)")
        void getDeals_appelsMultiples_uneSeuleRequete() throws Exception {
            UserView user = new UserView(1L, "b@alp.fr", "B", null, "BUYER", false);
            connecter(user);
            when(service.dealsFor(user)).thenReturn(List.of());

            bean.getDeals();
            bean.getDeals();
            bean.getDeals();

            // Régression : JSF évalue l'expression plusieurs fois par requête ; le cache
            // anti-désynchronisation
            // doit éviter les requêtes multiples.
            verify(service, Mockito.times(1)).dealsFor(user);
        }

        @Test
        @DisplayName("getActiveMessages / getActiveDealDocuments : liste vide sans dossier actif")
        void dossiersSansDossierActif_listesVides() {
            assertDoesNotThrow(() -> connecter(new UserView(1L, "b@alp.fr", "B", null, "BUYER", false)));
            bean.setActiveDealId(null);

            assertTrue(bean.getActiveMessages().isEmpty());
            assertTrue(bean.getActiveDealDocuments().isEmpty());
            verify(service, never()).messages(any(), any());
            verify(service, never()).dealDocuments(any(), any());
        }

        @Test
        @DisplayName("getActiveMessages : avec dossier actif -> délègue au service")
        void getActiveMessages_avecDossierActif_delegueAuService() throws Exception {
            UserView user = new UserView(1L, "b@alp.fr", "B", null, "BUYER", false);
            connecter(user);
            bean.openDeal(4L);
            when(service.messages(user, 4L)).thenReturn(List.of());

            assertTrue(bean.getActiveMessages().isEmpty());
            assertEquals(4L, bean.getActiveDealId());
            verify(service).messages(user, 4L);
            assertEquals("", bean.getMessage(), "openDeal doit vider le brouillon de message");
        }

        @Test
        @DisplayName("getCsrfToken : généré une seule fois, encodé en Base64 URL et stocké en session")
        void getCsrfToken_genereEtStockeEnSession() {
            String first = bean.getCsrfToken();
            String second = bean.getCsrfToken();

            assertNotNull(first);
            assertEquals(first, second, "Le jeton doit rester stable pendant la session");
            assertTrue(first.matches("[A-Za-z0-9_-]+"), "Encodage Base64 URL sans padding attendu");
            verify(session).setAttribute("uploadCsrfToken", first);
        }

        @Test
        @DisplayName("getPublicListings : délègue au service")
        void getPublicListings_delegueAuService() {
            when(service.publicListings()).thenReturn(List.of());

            assertTrue(bean.getPublicListings().isEmpty());
        }

        @Test
        @DisplayName("getRoleLabel : libellés français pour chaque rôle et repli sur le code")
        void getRoleLabel_libelles() throws Exception {
            connecter(new UserView(1L, "a@alp.fr", "A", null, "SELLER", false));
            assertEquals("Cédant", bean.getRoleLabel());

            connecter(new UserView(1L, "a@alp.fr", "A", null, "BUYER", false));
            assertEquals("Acquéreur / investisseur", bean.getRoleLabel());

            connecter(new UserView(1L, "a@alp.fr", "A", null, "BROKER", false));
            assertEquals("Courtier", bean.getRoleLabel());

            connecter(new UserView(1L, "a@alp.fr", "A", null, "SUPER", false));
            assertEquals("Super utilisateur", bean.getRoleLabel());

            connecter(new UserView(1L, "a@alp.fr", "A", null, "INCONNU", false));
            assertEquals("INCONNU", bean.getRoleLabel());
        }

        @Test
        @DisplayName("getRoleLabel : sans utilisateur -> chaîne vide")
        void getRoleLabel_sansUtilisateur_chaîneVide() {
            assertEquals("", bean.getRoleLabel());
        }

        @Test
        @DisplayName("isBuyer / isSeller / isBroker : vrais uniquement pour le bon rôle")
        void isBuyerIsSellerIsBroker_selonLeRole() throws Exception {
            connecter(new UserView(1L, "a@alp.fr", "A", null, "BUYER", false));
            assertTrue(bean.isBuyer());
            assertFalse(bean.isSeller());
            assertFalse(bean.isBroker());

            connecter(new UserView(1L, "a@alp.fr", "A", null, "SELLER", false));
            assertFalse(bean.isBuyer());
            assertTrue(bean.isSeller());

            connecter(new UserView(1L, "a@alp.fr", "A", null, "BROKER", false));
            assertTrue(bean.isBroker());
        }

        @Test
        @DisplayName("isBuyer / isSeller / isBroker : faux sans utilisateur")
        void indicateurs_sansUtilisateur_faux() {
            assertFalse(bean.isBuyer());
            assertFalse(bean.isSeller());
            assertFalse(bean.isBroker());
        }

        @Test
        @DisplayName("openDeal : mémorise le dossier en cours")
        void openDeal_memoriseLeDossier() {
            bean.openDeal(12L);

            assertEquals(12L, bean.getActiveDealId());
        }

        @Test
        @DisplayName("getters/setters : les valeurs sont restituées")
        void accesseurs_valeursRestituees() {
            bean.setCompanyName("Société");
            bean.setExactLocation("Rue");
            bean.setFoundedYear(2001);

            assertEquals("Société", bean.getCompanyName());
            assertEquals("Rue", bean.getExactLocation());
            assertEquals(2001, bean.getFoundedYear());
        }
    }

    // =============================================================================================

    @Test
    @DisplayName("Le bean est sérialisable (bean de session JSF)")
    void bean_serialisable() {
        assertTrue(java.io.Serializable.class.isAssignableFrom(MarketplaceBean.class));
    }

    @Test
    @DisplayName("Le bean est annoté @Named(\"marketplace\") et @SessionScoped")
    void bean_annotationsJSF() {
        assertNotNull(MarketplaceBean.class.getAnnotation(jakarta.inject.Named.class));
        assertEquals("marketplace", MarketplaceBean.class.getAnnotation(jakarta.inject.Named.class).value());
        assertNotNull(MarketplaceBean.class.getAnnotation(
                jakarta.enterprise.context.SessionScoped.class));
    }

    @Test
    @DisplayName("Champs du formulaire : une modification ne réinitialise pas l'utilisateur connecté")
    void champsFormulaire_modification_nAffectePasLUtilisateur() throws Exception {
        UserView user = new UserView(1L, "a@alp.fr", "A", null, "BUYER", false);
        connecter(user);

        bean.setEmail("autre@alp.fr");
        bean.setPassword("xyz");

        assertSame(user, bean.getUser());
    }

}
