package fr.alp.confidentia.web;

import fr.alp.confidentia.security.CryptoService;
import fr.alp.confidentia.service.MarketplaceService;
import fr.alp.confidentia.service.ViewModels.DocumentDownload;
import fr.alp.confidentia.service.ViewModels.UserView;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link DocumentServlet} : contrôle du jeton CSRF,
 * validation du fichier,
 * et téléchargement sécurisé. Le service est un mock Mockito injecté par
 * réflexion
 * (le champ est annoté {@code @Inject} mais privé).
 */
@DisplayName("DocumentServlet - téléversement et téléchargement")
class DocumentServletTest {

    private static final String CONTEXT = "/confidentia";
    private static final String CSRF = "jeton-csrf-de-test";

    private DocumentServlet servlet;
    private MarketplaceService service;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private HttpSession session;
    private UserView user;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new DocumentServlet();
        service = mock(MarketplaceService.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        user = new UserView(1L, "u@alp.fr", "Utilisateur", "ALP", "SELLER", false);

        // Injection du mock dans le champ @Inject privé.
        Field field = DocumentServlet.class.getDeclaredField("service");
        field.setAccessible(true);
        field.set(servlet, service);

        when(request.getContextPath()).thenReturn(CONTEXT);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("authenticatedUser")).thenReturn(user);
    }

    /**
     * Prépare un Part valide portant le contenu donné.
     * Le double est rédigé à la main : enregistrer des stubs sur un mock neuf pendant qu'un stub
     * d'un autre mock est en cours (ex. {@code thenReturn(validPart(...))}) provoque une
     * {@code UnfinishedStubbingException} avec Mockito.
     */
    private Part validPart(String fileName, String contentType, byte[] content) {
        return new Part() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(content);
            }

            @Override
            public String getContentType() {
                return contentType;
            }

            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getSubmittedFileName() {
                return fileName;
            }

            @Override
            public long getSize() {
                return content.length;
            }

            @Override
            public void write(String path) {
                throw new UnsupportedOperationException("Non utilisé par le servlet");
            }

            @Override
            public void delete() {
                throw new UnsupportedOperationException("Non utilisé par le servlet");
            }

            @Override
            public String getHeader(String name) {
                return null;
            }

            @Override
            public Collection<String> getHeaders(String name) {
                return List.of();
            }

            @Override
            public Collection<String> getHeaderNames() {
                return List.of();
            }
        };
    }

    private void csrfMatches() {
        when(session.getAttribute("uploadCsrfToken")).thenReturn(CSRF);
        when(request.getParameter("csrfToken")).thenReturn(CSRF);
    }

    // =============================================================================================
    // doPost
    // =============================================================================================

    @Nested
    @DisplayName("doPost - téléversement")
    class Televersement {

        @Test
        @DisplayName("authentification absente : 401 et aucun appel au service")
        void doPost_sansUtilisateurEnSession_renvoie401() throws Exception {
            when(session.getAttribute("authenticatedUser")).thenReturn(null);

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentification requise.");
            verify(service, never()).storeDocument(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("changement de mot de passe requis : redirection avant tout traitement")
        void doPost_changementMotDePasseRequis_redirige() throws Exception {
            when(session.getAttribute("authenticatedUser")).thenReturn(
                    new UserView(1L, "u@alp.fr", "U", "ALP", "SELLER", true));

            servlet.doPost(request, response);

            verify(response).sendRedirect(CONTEXT + "/change-password.xhtml");
            verify(service, never()).storeDocument(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("jeton CSRF absent en session : 403")
        void doPost_csrfAbsentEnSession_renvoie403() throws Exception {
            when(session.getAttribute("uploadCsrfToken")).thenReturn(null);
            when(request.getParameter("csrfToken")).thenReturn(CSRF);

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Jeton de sécurité invalide.");
        }

        @Test
        @DisplayName("jeton CSRF non fourni par le client : 403")
        void doPost_csrfNonFourni_renvoie403() throws Exception {
            when(session.getAttribute("uploadCsrfToken")).thenReturn(CSRF);
            when(request.getParameter("csrfToken")).thenReturn(null);

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Jeton de sécurité invalide.");
        }

        @Test
        @DisplayName("jeton CSRF différent : 403 (requête forgée)")
        void doPost_csrfDifferent_renvoie403() throws Exception {
            when(session.getAttribute("uploadCsrfToken")).thenReturn(CSRF);
            when(request.getParameter("csrfToken")).thenReturn("jeton-attaquant");

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Jeton de sécurité invalide.");
            verify(service, never()).storeDocument(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("fichier absent : 400")
        void doPost_fichierAbsent_renvoie400() throws Exception {
            csrfMatches();
            when(request.getPart("file")).thenReturn(null);

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, "Sélectionnez un fichier à téléverser.");
        }

        @Test
        @DisplayName("fichier vide (taille 0) : 400")
        void doPost_fichierVide_renvoie400() throws Exception {
            csrfMatches();
            Part empty = mock(Part.class);
            when(empty.getSize()).thenReturn(0L);
            when(request.getPart("file")).thenReturn(empty);

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, "Sélectionnez un fichier à téléverser.");
            verify(service, never()).storeDocument(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("téléversement valide : le service reçoit le nom, le type et le contenu puis redirection")
        void doPost_televersementValide_appelleLeServiceEtRedirige() throws Exception {
            csrfMatches();
            byte[] content = "%PDF-1.7 contenu".getBytes();
            Part uploaded = validPart("contrat.pdf", "application/pdf", content);
            when(request.getPart("file")).thenReturn(uploaded);
            when(request.getParameter("listingId")).thenReturn("12");
            when(request.getParameter("requestId")).thenReturn(null);

            servlet.doPost(request, response);

            verify(service).storeDocument(eq(user), eq(12L), eq(null), eq("contrat.pdf"),
                    eq("application/pdf"), eq(content));
            verify(response).sendRedirect(CONTEXT + "/app.xhtml?upload=success&faces-redirect=true");
        }

        @Test
        @DisplayName("identifiants vides : transmis comme null au service (page de collecte)")
        void doPost_identifiantsVides_transmetNull() throws Exception {
            csrfMatches();
            Part uploaded = validPart("photo.png", "image/png", new byte[] { 1, 2 });
            when(request.getPart("file")).thenReturn(uploaded);
            when(request.getParameter("listingId")).thenReturn("");
            when(request.getParameter("requestId")).thenReturn("  ");

            servlet.doPost(request, response);

            verify(service).storeDocument(eq(user), eq(null), eq(null), eq("photo.png"),
                    eq("image/png"), any(byte[].class));
        }

        @Test
        @DisplayName("identifiant non numérique : 400 (IllegalArgumentException convertie)")
        void doPost_identifiantNonNumerique_renvoie400() throws Exception {
            csrfMatches();
            Part uploaded = validPart("a.pdf", "application/pdf", new byte[] { 1 });
            when(request.getPart("file")).thenReturn(uploaded);
            when(request.getParameter("listingId")).thenReturn("abc");

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, "Identifiant invalide.");
            verify(service, never()).storeDocument(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("refus du service (format interdit -> IllegalArgumentException) : 400 avec le message")
        void doPost_serviceRefuseLeFormat_renvoie400() throws Exception {
            csrfMatches();
            when(request.getPart("file"))
                    .thenReturn(validPart("virus.exe", "application/octet-stream", new byte[] { 1 }));
            when(service.storeDocument(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Format non autorisé."));

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, "Format non autorisé.");
        }

        @Test
        @DisplayName("refus d'accès du service (SecurityException) : 403")
        void doPost_securityException_renvoie403() throws Exception {
            csrfMatches();
            Part uploaded = validPart("a.pdf", "application/pdf", new byte[] { 1 });
            when(request.getPart("file")).thenReturn(uploaded);
            when(service.storeDocument(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new SecurityException("Accès réservé au cédant ou au courtier mandaté."));

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Accès réservé au cédant ou au courtier mandaté.");
        }

        @Test
        @DisplayName("erreur d'E/S côté stockage (IllegalStateException) : 500")
        void doPost_erreurStockage_renvoie500() throws Exception {
            csrfMatches();
            Part uploaded = validPart("a.pdf", "application/pdf", new byte[] { 1 });
            when(request.getPart("file")).thenReturn(uploaded);
            when(service.storeDocument(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("Impossible d'enregistrer le document sécurisé."));

            servlet.doPost(request, response);

            verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Impossible d'enregistrer le document sécurisé.");
        }

        @Test
        @DisplayName("CSRF valide mais lecture du fichier impossible : IOException propagée")
        void doPost_lectureFichierImpossible_propageIOException() throws Exception {
            csrfMatches();
            Part part = mock(Part.class);
            when(part.getSize()).thenReturn(10L);
            when(part.getInputStream()).thenThrow(new IOException("flux interrompu"));
            when(request.getPart("file")).thenReturn(part);

            assertThrows(IOException.class, () -> servlet.doPost(request, response));
        }
    }

    // =============================================================================================
    // doGet
    // =============================================================================================

    @Nested
    @DisplayName("doGet - téléchargement")
    class Telechargement {

        private ServletOutputStream output;

        @BeforeEach
        void setUpStream() throws IOException {
            output = mock(ServletOutputStream.class);
            when(response.getOutputStream()).thenReturn(output);
        }

        @Test
        @DisplayName("utilisateur non authentifié : 401")
        void doGet_sansAuthentification_renvoie401() throws Exception {
            when(session.getAttribute("authenticatedUser")).thenReturn(null);

            servlet.doGet(request, response);

            verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentification requise.");
            verify(service, never()).readDocument(any(), any());
        }

        @Test
        @DisplayName("identifiant manquant : 400")
        void doGet_identifiantManquant_renvoie400() throws Exception {
            when(request.getParameter("id")).thenReturn(null);

            servlet.doGet(request, response);

            verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, "Identifiant de document manquant.");
        }

        @Test
        @DisplayName("identifiant non numérique : 404 (traduit en 'introuvable')")
        void doGet_identifiantNonNumerique_renvoie404() throws Exception {
            when(request.getParameter("id")).thenReturn("xyz");

            servlet.doGet(request, response);

            verify(response).sendError(HttpServletResponse.SC_NOT_FOUND, "Identifiant invalide.");
        }

        @Test
        @DisplayName("document valide : en-têtes de sécurité et contenu écrit dans la réponse")
        void doGet_documentValide_ecritLeContenu() throws Exception {
            when(request.getParameter("id")).thenReturn("5");
            byte[] bytes = "contenu".getBytes();
            when(service.readDocument(user, 5L))
                    .thenReturn(new DocumentDownload("rapport.pdf", "application/pdf", bytes));

            servlet.doGet(request, response);

            verify(response).setContentType("application/pdf");
            verify(response).setHeader("X-Content-Type-Options", "nosniff");
            verify(response).setHeader("Content-Disposition", "attachment; filename=\"rapport.pdf\"");
            verify(response).setContentLength(bytes.length);
            verify(output).write(bytes);
        }

        @Test
        @DisplayName("nom de fichier contenant guillemets/retours à la ligne : neutralisé dans l'en-tête (anti-injection)")
        void doGet_nomFichierMalveillant_nettoieLEnTete() throws Exception {
            when(request.getParameter("id")).thenReturn("6");
            when(service.readDocument(user, 6L)).thenReturn(new DocumentDownload(
                    "rapport\"\r\nX-Injected: oui.pdf", "application/pdf", new byte[] { 1 }));

            servlet.doGet(request, response);

            verify(response).setHeader("Content-Disposition",
                    "attachment; filename=\"rapportX-Injected: oui.pdf\"");
        }

        @Test
        @DisplayName("document introuvable (IllegalArgumentException) : 404")
        void doGet_documentIntrouvable_renvoie404() throws Exception {
            when(request.getParameter("id")).thenReturn("99");
            when(service.readDocument(user, 99L)).thenThrow(new IllegalArgumentException("Document introuvable."));

            servlet.doGet(request, response);

            verify(response).sendError(HttpServletResponse.SC_NOT_FOUND, "Document introuvable.");
        }

        @Test
        @DisplayName("accès non autorisé (SecurityException) : 403")
        void doGet_accesRefuse_renvoie403() throws Exception {
            when(request.getParameter("id")).thenReturn("7");
            when(service.readDocument(user, 7L))
                    .thenThrow(new SecurityException("Accès réservé aux participants autorisés à ce dossier."));

            servlet.doGet(request, response);

            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Accès réservé aux participants autorisés à ce dossier.");
        }

        @Test
        @DisplayName("chiffré avec une ancienne clé (UnreadableCiphertextException) : 410 Gone")
        void doGet_donneesIllisibles_renvoie410() throws Exception {
            when(request.getParameter("id")).thenReturn("8");
            // On provoque une vraie UnreadableCiphertextException via une clé absente puis
            // des octets invalides.
            System.setProperty("APP_ENCRYPTION_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
            byte[] encrypted = CryptoService.encrypt("x".getBytes());
            System.setProperty("APP_ENCRYPTION_KEY", "HxsaGhwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc=");
            CryptoService.UnreadableCiphertextException failure;
            try {
                CryptoService.decrypt(encrypted);
                throw new AssertionError("Le déchiffrement aurait dû échouer");
            } catch (CryptoService.UnreadableCiphertextException exception) {
                failure = exception;
            } finally {
                System.clearProperty("APP_ENCRYPTION_KEY");
            }
            when(service.readDocument(user, 8L)).thenThrow(failure);

            servlet.doGet(request, response);

            verify(response).sendError(HttpServletResponse.SC_GONE,
                    "Le document a été chiffré avec une ancienne clé et ne peut plus être déchiffré.");
        }

        @Test
        @DisplayName("erreur interne (IllegalStateException) : 500")
        void doGet_erreurInterne_renvoie500() throws Exception {
            when(request.getParameter("id")).thenReturn("9");
            when(service.readDocument(user, 9L))
                    .thenThrow(new IllegalStateException("Le document sécurisé est indisponible."));

            servlet.doGet(request, response);

            verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Le document sécurisé est indisponible.");
        }

        @Test
        @DisplayName("contenu binaire : les octets sont écrits sans modification")
        void doGet_contenuBinaire_ecritLesOctetsAvecFidelite() throws Exception {
            byte[] bytes = { 0x00, (byte) 0xFF, 0x10, 0x7F };
            when(request.getParameter("id")).thenReturn("10");
            when(service.readDocument(user, 10L))
                    .thenReturn(new DocumentDownload("image.png", "image/png", bytes));

            servlet.doGet(request, response);

            org.mockito.ArgumentCaptor<byte[]> captor = org.mockito.ArgumentCaptor.captor();
            verify(output).write(captor.capture());
            assertArrayEquals(bytes, captor.getValue(),
                    "Le contenu binaire doit être écrit sans modification");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Méthode utilitaire optionalLong (via l'API publique)")
    class ParametrageId {

        @Test
        @DisplayName("id hors plage de Long : 404")
        void doGet_identifiantTropGrand_renvoie404() throws Exception {
            when(request.getParameter("id")).thenReturn("999999999999999999999999");

            servlet.doGet(request, response);

            verify(response).sendError(HttpServletResponse.SC_NOT_FOUND, "Identifiant invalide.");
            Mockito.verifyNoInteractions(service);
        }

        @Test
        @DisplayName("id négatif : transmis au service, qui répond « introuvable »")
        void doGet_identifiantNegatif_transmisAuService() throws Exception {
            when(request.getParameter("id")).thenReturn("-3");
            when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
            when(service.readDocument(user, -3L))
                    .thenThrow(new IllegalArgumentException("Document introuvable."));

            servlet.doGet(request, response);

            // Aucun document ne peut avoir un id négatif : le service répond « introuvable ».
            verify(service).readDocument(user, -3L);
            verify(response).sendError(HttpServletResponse.SC_NOT_FOUND, "Document introuvable.");
        }
    }
}
