package fr.alp.confidentia.web;

import fr.alp.confidentia.model.Role;
import fr.alp.confidentia.service.ViewModels.UserView;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

public class AuthenticationFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getServletPath();
        if (path.startsWith("/jakarta.faces.resource/")
                || path.endsWith("/home.xhtml") || path.endsWith("/acheter.xhtml")
                || path.endsWith("/vendre.xhtml") || path.endsWith("/login.xhtml")
                || path.endsWith("/register.xhtml") || path.endsWith("/faq.xhtml")
                || path.endsWith("/legal.xhtml") || path.endsWith("/contact.xhtml")
                || path.endsWith("/article.xhtml")) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = httpRequest.getSession(false);
        if (session == null || session.getAttribute("authenticatedUser") == null) {
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/login.xhtml");
            return;
        }
        Object authenticatedUser = session.getAttribute("authenticatedUser");
        if (!(authenticatedUser instanceof UserView authenticatedView)) {
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session d'authentification invalide.");
            return;
        }
        if (path.endsWith("/admin-users.xhtml") && !Role.SUPER.name().equals(authenticatedView.role())) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Accès réservé aux super utilisateurs.");
            return;
        }
        if (path.endsWith("/content-admin.xhtml")
                && !Role.SUPER.name().equals(authenticatedView.role())) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Accès réservé aux super utilisateurs.");
            return;
        }
        if (path.endsWith("/seller-verification.xhtml")
                && !(Role.SUPER.name().equals(authenticatedView.role())
                || Role.BROKER.name().equals(authenticatedView.role()))) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Accès réservé aux courtiers et aux administrateurs.");
            return;
        }
        if (path.endsWith("/buyer-kyc.xhtml")
                && !(Role.SUPER.name().equals(authenticatedView.role())
                || Role.BROKER.name().equals(authenticatedView.role()))) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Accès réservé aux courtiers et aux administrateurs.");
            return;
        }
        if (authenticatedView.passwordChangeRequired() && !path.endsWith("/change-password.xhtml")) {
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/change-password.xhtml");
            return;
        }
        if (!authenticatedView.passwordChangeRequired() && path.endsWith("/change-password.xhtml")) {
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/app.xhtml");
            return;
        }
        chain.doFilter(request, response);
    }
}
