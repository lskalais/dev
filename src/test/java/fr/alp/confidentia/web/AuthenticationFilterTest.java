package fr.alp.confidentia.web;

import fr.alp.confidentia.service.ViewModels.UserView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link AuthenticationFilter}.
 * <p>
 * Toutes les dépendances servlet sont des mocks Mockito : aucun conteneur n'est
 * nécessaire.
 * Le contrat vérifié est : quelles requêtes passent, lesquelles sont
 * redirigées, lesquelles sont refusées.
 */
@DisplayName("AuthenticationFilter - contrôle d'accès")
class AuthenticationFilterTest {

    private static final String CONTEXT = "/confidentia";

    private AuthenticationFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        filter = new AuthenticationFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        session = mock(HttpSession.class);
        when(request.getContextPath()).thenReturn(CONTEXT);
        when(request.getSession(false)).thenReturn(session);
    }

    private void path(String value) {
        when(request.getServletPath()).thenReturn(value);
    }

    private void authenticatedAs(Long id, String role, boolean passwordChangeRequired) {
        when(session.getAttribute("authenticatedUser"))
                .thenReturn(new UserView(id, "u@alp.fr", "Utilisateur", "ALP", role, passwordChangeRequired));
    }

    @Nested
    @DisplayName("Ressources publiques")
    class RessourcesPubliques {

        @ParameterizedTest(name = "chemin public : {0}")
        @ValueSource(strings = { "/home.xhtml", "/acheter.xhtml", "/vendre.xhtml", "/login.xhtml",
                "/register.xhtml", "/faq.xhtml", "/legal.xhtml", "/contact.xhtml", "/article.xhtml",
                "/jakarta.faces.resource/theme.css" })
        @DisplayName("les pages publiques et ressources statiques ne sont pas filtrées")
        void doFilter_cheminPublic_laissePasser(String servletPath) throws Exception {
            path(servletPath);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            verify(response, never()).sendRedirect(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("Absence d'authentification")
    class NonAuthentifie {

        @Test
        @DisplayName("session absente : redirection vers /login.xhtml")
        void doFilter_sansSession_redirigeVersLogin() throws Exception {
            path("/app.xhtml");
            when(request.getSession(false)).thenReturn(null);

            filter.doFilter(request, response, chain);

            verify(response).sendRedirect(CONTEXT + "/login.xhtml");
            verify(chain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("session existante sans utilisateur : redirection vers /login.xhtml")
        void doFilter_sessionSansUtilisateur_redirigeVersLogin() throws Exception {
            path("/app.xhtml");
            when(session.getAttribute("authenticatedUser")).thenReturn(null);

            filter.doFilter(request, response, chain);

            verify(response).sendRedirect(CONTEXT + "/login.xhtml");
            verify(chain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("attribut de session d'un mauvais type : 401")
        void doFilter_attributDeMauvaisType_renvoie401() throws Exception {
            path("/app.xhtml");
            // Un attaquant ou un bug applicatif peut placer n'importe quoi dans la session.
            when(session.getAttribute("authenticatedUser")).thenReturn("utilisateur-en-chaine");

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session d'authentification invalide.");
            verify(chain, never()).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Règles métier sur l'utilisateur authentifié")
    class UtilisateurAuthentifie {

        @Test
        @DisplayName("utilisateur standard sur une page protégée : accès autorisé")
        void doFilter_utilisateurStandardSurPageProtegee_laissePasser() throws Exception {
            path("/app.xhtml");
            authenticatedAs(1L, "BUYER", false);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            verify(response, never()).sendError(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("admin-users.xhtml avec un rôle non SUPER : 403")
        void doFilter_pageAdminSansRoleSuper_renvoie403() throws Exception {
            path("/admin-users.xhtml");
            authenticatedAs(1L, "SELLER", false);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Accès réservé aux super utilisateurs.");
            verify(chain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("admin-users.xhtml avec le rôle SUPER : accès autorisé")
        void doFilter_pageAdminAvecRoleSuper_laissePasser() throws Exception {
            path("/admin-users.xhtml");
            authenticatedAs(1L, "SUPER", false);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("content-admin.xhtml avec un rôle non SUPER : 403")
        void doFilter_pageContenuSansRoleSuper_renvoie403() throws Exception {
            path("/content-admin.xhtml");
            authenticatedAs(1L, "BROKER", false);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Accès réservé aux super utilisateurs.");
            verify(chain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("content-admin.xhtml avec le rôle SUPER : accès autorisé")
        void doFilter_pageContenuAvecRoleSuper_laissePasser() throws Exception {
            path("/content-admin.xhtml");
            authenticatedAs(1L, "SUPER", false);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("seller-verification.xhtml avec le rôle BROKER : accès autorisé")
        void doFilter_pageVerificationAvecCourtier_laissePasser() throws Exception {
            path("/seller-verification.xhtml");
            authenticatedAs(1L, "BROKER", false);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("seller-verification.xhtml avec un rôle non autorisé : 403")
        void doFilter_pageVerificationSansRoleAutorise_renvoie403() throws Exception {
            path("/seller-verification.xhtml");
            authenticatedAs(1L, "SELLER", false);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Accès réservé aux courtiers et aux administrateurs.");
            verify(chain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("buyer-kyc.xhtml avec le rôle BROKER : accès autorisé")
        void doFilter_pageBuyerKycAvecCourtier_laissePasser() throws Exception {
            path("/buyer-kyc.xhtml");
            authenticatedAs(1L, "BROKER", false);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("buyer-kyc.xhtml avec un rôle BUYER : 403")
        void doFilter_pageBuyerKycAvecAcquereur_renvoie403() throws Exception {
            path("/buyer-kyc.xhtml");
            authenticatedAs(1L, "BUYER", false);

            filter.doFilter(request, response, chain);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Accès réservé aux courtiers et aux administrateurs.");
            verify(chain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("changement de mot de passe requis : redirection vers change-password.xhtml")
        void doFilter_changementMotDePasseRequis_redirigeVersChangePassword() throws Exception {
            path("/app.xhtml");
            authenticatedAs(1L, "BUYER", true);

            filter.doFilter(request, response, chain);

            verify(response).sendRedirect(CONTEXT + "/change-password.xhtml");
            verify(chain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("changement de mot de passe requis mais page demandée = change-password : accès autorisé")
        void doFilter_changementRequisSurLaBonnePage_laissePasser() throws Exception {
            path("/change-password.xhtml");
            authenticatedAs(1L, "BUYER", true);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("mot de passe à jour mais page change-password : redirection vers app.xhtml")
        void doFilter_changePasswordSansObligation_redirigeVersApp() throws Exception {
            path("/change-password.xhtml");
            authenticatedAs(1L, "BUYER", false);

            filter.doFilter(request, response, chain);

            verify(response).sendRedirect(CONTEXT + "/app.xhtml");
            verify(chain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("un chemin public reste accessible même avec un changement de mot de passe requis")
        void doFilter_cheminPublicAvecChangementRequis_laissePasser() throws Exception {
            path("/home.xhtml");
            authenticatedAs(1L, "BUYER", true);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Cas particuliers")
    class CasParticuliers {

        @Test
        @DisplayName("chemin null : NullPointerException (contrat servlet, ne doit pas arriver)")
        void doFilter_cheminNull_leveNullPointerException() {
            path(null);

            assertThrows(NullPointerException.class, () -> filter.doFilter(request, response, chain));
        }

        @Test
        @DisplayName("objet requête qui n'est pas HttpServletRequest : ClassCastException")
        void doFilter_requeteNonHttp_leveClassCastException() {
            // La signature Servlet est permissive : le cast vers HttpServletRequest échoue
            // tôt.
            assertThrows(ClassCastException.class,
                    () -> filter.doFilter(mock(jakarta.servlet.ServletRequest.class), response, chain));
        }
    }
}
