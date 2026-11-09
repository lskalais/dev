package fr.alp.confidentia.web;

import fr.alp.confidentia.security.CryptoService;
import fr.alp.confidentia.service.MarketplaceService;
import fr.alp.confidentia.service.ViewModels.DocumentDownload;
import fr.alp.confidentia.service.ViewModels.UserView;
import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.security.MessageDigest;

@WebServlet("/documents")
@MultipartConfig(maxFileSize = 20L * 1024 * 1024, maxRequestSize = 21L * 1024 * 1024)
public class DocumentServlet extends HttpServlet {
    @Inject
    private MarketplaceService service;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        UserView user = authenticatedUser(request, response);
        if (user == null) {
            return;
        }
        try {
            String expectedToken = (String) request.getSession(false).getAttribute("uploadCsrfToken");
            String submittedToken = request.getParameter("csrfToken");
            if (expectedToken == null || submittedToken == null
                    || !MessageDigest.isEqual(expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    submittedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Jeton de sécurité invalide.");
                return;
            }
            Part file = request.getPart("file");
            if (file == null || file.getSize() == 0) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Sélectionnez un fichier à téléverser.");
                return;
            }
            Long listingId = optionalLong(request.getParameter("listingId"));
            Long requestId = optionalLong(request.getParameter("requestId"));
            String verificationType = request.getParameter("verificationType");
            String buyerKycType = request.getParameter("buyerKycType");
            if (verificationType != null && !verificationType.isBlank()
                    && buyerKycType != null && !buyerKycType.isBlank()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "Choisissez un seul type de document de vérification.");
                return;
            }
            if (verificationType != null && !verificationType.isBlank()) {
                if (listingId != null || requestId != null) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "Un document de vérification ne peut être associé à un dossier de cession.");
                    return;
                }
                service.storeSellerVerificationDocument(user, verificationType,
                        file.getSubmittedFileName(), file.getContentType(), file.getInputStream().readAllBytes());
            } else if (buyerKycType != null && !buyerKycType.isBlank()) {
                if (listingId != null || requestId != null) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "Un document KYC ne peut être associé à un dossier de cession.");
                    return;
                }
                service.storeBuyerKycDocument(user, buyerKycType,
                        file.getSubmittedFileName(), file.getContentType(), file.getInputStream().readAllBytes());
            } else {
                service.storeDocument(user, listingId, requestId,
                        file.getSubmittedFileName(), file.getContentType(), file.getInputStream().readAllBytes());
            }
            response.sendRedirect(request.getContextPath() + "/app.xhtml?upload=success&faces-redirect=true");
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, exception.getMessage());
        } catch (SecurityException exception) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, exception.getMessage());
        } catch (IllegalStateException exception) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        UserView user = authenticatedUser(request, response);
        if (user == null) {
            return;
        }
        try {
            Long id = optionalLong(request.getParameter("id"));
            if (id == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Identifiant de document manquant.");
                return;
            }
            DocumentDownload document = service.readDocument(user, id);
            String safeFilename = document.name().replace("\"", "").replace("\r", "").replace("\n", "");
            response.setContentType(document.contentType());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + safeFilename + "\"");
            response.setContentLength(document.bytes().length);
            response.getOutputStream().write(document.bytes());
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, exception.getMessage());
        } catch (SecurityException exception) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, exception.getMessage());
        } catch (CryptoService.UnreadableCiphertextException exception) {
            response.sendError(HttpServletResponse.SC_GONE,
                    "Le document a été chiffré avec une ancienne clé et ne peut plus être déchiffré.");
        } catch (IllegalStateException exception) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    private static UserView authenticatedUser(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        Object sessionUser = session == null ? null : session.getAttribute("authenticatedUser");
        if (sessionUser instanceof UserView user) {
            if (user.passwordChangeRequired()) {
                response.sendRedirect(request.getContextPath() + "/change-password.xhtml");
                return null;
            }
            return user;
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentification requise.");
        return null;
    }

    private static Long optionalLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Identifiant invalide.");
        }
    }
}
