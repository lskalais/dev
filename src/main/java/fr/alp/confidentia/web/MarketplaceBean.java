package fr.alp.confidentia.web;

import fr.alp.confidentia.model.Role;
import fr.alp.confidentia.service.MarketplaceService;
import fr.alp.confidentia.service.ViewModels.*;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Named("marketplace")
@SessionScoped
public class MarketplaceBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Inject
    private MarketplaceService service;

    private UserView user;
    private String email;
    private String password;
    private String fullName;
    private String organization;
    private String role = "BUYER";
    private String temporaryPassword;
    private String newPassword;
    private String confirmedNewPassword;
    private String message;
    private String signatureName;
    private String csrfToken;
    private Long activeDealId;
    private Long selectedListingId;
    private static final SecureRandom RANDOM = new SecureRandom();

    private String sectors;
    private String preferredRegion;
    private BigDecimal minimumInvestment;
    private BigDecimal maximumInvestment;
    private String sectorFilter;
    private String regionFilter;
    private BigDecimal maximumPriceFilter;
    private BigDecimal processedRevenue;
    private BigDecimal processedEbitda;
    private BigDecimal valuationMultiple;

    private String brokerEmail;
    private String sector;
    private String secondarySector;
    private Integer foundedYear;
    private String description;
    private String region;
    private String customerSummary;
    private String advantages;
    private String saleReason;
    private BigDecimal revenue;
    private BigDecimal ebitda;
    private BigDecimal askingPrice;
    private BigDecimal profitMargin;
    private String saleConditions;
    private String supportPeriod;
    private String companyName;
    private String exactLocation;

    public String register() {
        try {
            Role selectedRole = Role.valueOf(role);
            if (selectedRole == Role.BROKER && (organization == null || organization.isBlank())) {
                throw new IllegalArgumentException("Un cabinet ou une organisation est requis pour le profil courtier.");
            }
            user = service.register(email, password, fullName, organization, selectedRole);
            clearActiveDeal();
            rotateSession();
            session().setAttribute("authenticatedUser", user);
            clearCredentials();
            return "/app.xhtml?faces-redirect=true";
        } catch (IllegalArgumentException exception) {
            addError(exception.getMessage());
            return null;
        }
    }

    public String login() {
        try {
            user = service.authenticate(email, password);
            clearActiveDeal();
            rotateSession();
            session().setAttribute("authenticatedUser", user);
            clearCredentials();
            if (user.passwordChangeRequired()) {
                return "/change-password.xhtml?faces-redirect=true";
            }
            return "/app.xhtml?faces-redirect=true";
        } catch (IllegalArgumentException exception) {
            addError(exception.getMessage());
            return null;
        }
    }

    public String logout() {
        HttpSession current = session();
        if (current != null) {
            current.invalidate();
        }
        clearActiveDeal();
        user = null;
        return "/login.xhtml?faces-redirect=true";
    }

    public void saveCriteria() {
        runAction(() -> service.saveBuyerCriteria(user, sectors, preferredRegion, minimumInvestment, maximumInvestment),
                "Critères de recherche enregistrés.");
    }

    public void processFinancials(Long listingId) {
        if (runAction(() -> service.processFinancials(user, listingId,
                        processedRevenue, processedEbitda, valuationMultiple),
                "Indicateurs financiers enregistrés; le prix estimatif est calculé à partir de l'EBITDA et du multiple fournis.")) {
            processedRevenue = null;
            processedEbitda = null;
            valuationMultiple = null;
        }
    }

    public void saveListing() {
        try {
            ListingSaveResult result = service.saveListing(user, selectedListingId, brokerEmail, sector,
                    secondarySector, foundedYear, description, region, customerSummary, advantages, saleReason,
                    revenue, ebitda, askingPrice, profitMargin, saleConditions, supportPeriod, companyName,
                    exactLocation);
            selectedListingId = result.id();
            addInfo(result.brokerAssigned()
                    ? "Teaser publié anonymement. Vous pouvez le modifier jusqu'à ce que le courtier accepte le dossier."
                    : "Teaser publié anonymement sans courtier attribué. Vous pouvez le modifier jusqu'à sa prise en charge par un courtier.");
        } catch (IllegalArgumentException | SecurityException exception) {
            addError(exception.getMessage());
        } catch (IllegalStateException exception) {
            addError(exception.getMessage());
        }
    }

    public void loadListingForEdit(Long listingId) {
        try {
            ListingEditView listing = service.listingForEditing(user, listingId);
            selectedListingId = listing.id();
            brokerEmail = listing.brokerEmail();
            sector = listing.sector();
            secondarySector = listing.secondarySector();
            foundedYear = listing.foundedYear();
            description = listing.description();
            region = listing.region();
            customerSummary = listing.customerSummary();
            advantages = listing.advantages();
            saleReason = listing.saleReason();
            revenue = listing.revenue();
            ebitda = listing.ebitda();
            askingPrice = listing.askingPrice();
            profitMargin = listing.profitMargin();
            saleConditions = listing.saleConditions();
            supportPeriod = listing.supportPeriod();
            companyName = listing.companyName();
            exactLocation = listing.exactLocation();
            addInfo("Teaser chargé dans le formulaire de modification.");
        } catch (IllegalArgumentException | SecurityException exception) {
            addError(exception.getMessage());
        }
    }

    public void startNewListing() {
        clearListingForm();
    }

    public void requestIntroduction(Long listingId) {
        runAction(() -> service.requestIntroduction(user, listingId), "Demande envoyée. L'identité reste masquée.");
    }

    public void acceptIntroduction(Long requestId) {
        runAction(() -> service.decideIntroduction(user, requestId, true),
                "Demande acceptée. Les deux parties peuvent signer l'accord de confidentialité.");
    }

    public void declineIntroduction(Long requestId) {
        runAction(() -> service.decideIntroduction(user, requestId, false), "Demande refusée.");
    }

    public void signNda(Long requestId) {
        if (runAction(() -> service.signNda(user, requestId, signatureName),
                "Signature enregistrée. Les informations identifiantes ne sont visibles qu'après signature des deux parties.")) {
            signatureName = "";
        }
    }

    public void sendMessage(Long requestId) {
        if (runAction(() -> service.sendMessage(user, requestId, message), "Message chiffré envoyé.")) {
            message = "";
            activeDealId = requestId;
        }
    }

    public void openDeal(Long requestId) {
        activeDealId = requestId;
        message = "";
    }

    public void acceptListing(Long listingId) {
        runAction(() -> service.acceptListing(user, listingId),
                "Dossier accepté. Vous en êtes le courtier mandaté et le teaser n'est plus modifiable par le cédant.");
    }

    public void createManagedUser() {
        try {
            Role selectedRole = Role.valueOf(role);
            service.createManagedUser(user, email, password, fullName, organization, selectedRole);
            addInfo("Le compte a été créé. L'utilisateur devra changer son mot de passe temporaire à sa première connexion.");
            email = "";
            password = "";
            fullName = "";
            organization = "";
            role = "BUYER";
        } catch (IllegalArgumentException | SecurityException exception) {
            addError(exception.getMessage());
        }
    }

    public String assignTemporaryPassword(Long userId) {
        if (!runAction(() -> service.assignTemporaryPassword(user, userId, temporaryPassword),
                "Le mot de passe temporaire a été attribué; son remplacement sera obligatoire à la prochaine connexion.")) {
            return null;
        }
        temporaryPassword = "";
        if (user != null && user.id().equals(userId)) {
            user = new UserView(user.id(), user.email(), user.name(), user.organization(),
                    user.role(), true);
            clearActiveDeal();
            session().setAttribute("authenticatedUser", user);
            return "/change-password.xhtml?faces-redirect=true";
        }
        return null;
    }

    public String changeTemporaryPassword() {
        if (newPassword == null || !newPassword.equals(confirmedNewPassword)) {
            addError("Les deux nouveaux mots de passe ne correspondent pas.");
            return null;
        }
        try {
            user = service.changeTemporaryPassword(user, newPassword);
            clearActiveDeal();
            session().setAttribute("authenticatedUser", user);
            newPassword = "";
            confirmedNewPassword = "";
            addInfo("Votre mot de passe a été remplacé.");
            return "/app.xhtml?faces-redirect=true";
        } catch (IllegalArgumentException | SecurityException exception) {
            addError(exception.getMessage());
            return null;
        }
    }

    public void refresh() {
        if (user == null) {
            user = (UserView) session().getAttribute("authenticatedUser");
        }
        if (user != null && "BUYER".equals(user.role())) {
            service.recordMarketplaceViews(user);
        }
    }

    public List<ListingView> getListings() {
        return user == null ? List.of() : service.listingsFor(user, sectorFilter, regionFilter, maximumPriceFilter);
    }

    public List<ListingView> getPublicListings() {
        return service.publicListings();
    }

    public String getCsrfToken() {
        if (csrfToken == null) {
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            session().setAttribute("uploadCsrfToken", csrfToken);
        }
        return csrfToken;
    }

    public List<DealView> getDeals() {
        if (user == null) {
            return List.of();
        }
        // The bean is @SessionScoped, but this method backs a <ui:repeat>. JSF re-evaluates
        // ui:repeat's value expression during every phase of a single request (restore view,
        // decode, validate, update model, render). Without caching, each call issued a brand
        // new DB query, so the list seen during decode/update-model could differ (even subtly)
        // from the one seen when the view was rendered, desynchronizing ui:repeat's row index
        // from the actual submitted form fields and silently dropping input updates (e.g. the
        // NDA signature name). Caching per HTTP request (via the request map, which is reset
        // for every request regardless of the bean's session scope) keeps the data stable for
        // the whole lifecycle of a single request.
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return service.dealsFor(user);
        }
        Map<String, Object> requestMap = context.getExternalContext().getRequestMap();
        @SuppressWarnings("unchecked")
        List<DealView> cached = (List<DealView>) requestMap.get("marketplaceDealsCache");
        if (cached == null) {
            cached = service.dealsFor(user);
            requestMap.put("marketplaceDealsCache", cached);
        }
        return cached;
    }

    public List<MessageView> getActiveMessages() {
        return user == null || activeDealId == null ? List.of() : service.messages(user, activeDealId);
    }

    public List<DocumentView> getActiveDealDocuments() {
        return user == null || activeDealId == null ? List.of() : service.dealDocuments(user, activeDealId);
    }

    public List<DocumentView> documentsFor(Long listingId) {
        return user == null ? List.of() : service.documentsFor(user, listingId);
    }

    public String getRoleLabel() {
        if (user == null) {
            return "";
        }
        return switch (user.role()) {
            case "SELLER" -> "Cédant";
            case "BUYER" -> "Acquéreur / investisseur";
            case "BROKER" -> "Courtier";
            case "SUPER" -> "Super utilisateur";
            default -> user.role();
        };
    }

    public boolean isSuperUser() {
        return service.isSuperUser(user);
    }

    public List<AdminUserView> getAdminUsers() {
        return isSuperUser() ? service.usersForAdministration(user) : List.of();
    }

    public boolean isBuyer() {
        return user != null && "BUYER".equals(user.role());
    }

    public boolean isSeller() {
        return user != null && "SELLER".equals(user.role());
    }

    public boolean isBroker() {
        return user != null && "BROKER".equals(user.role());
    }

    public String getTemporaryPassword() { return temporaryPassword; }
    public void setTemporaryPassword(String temporaryPassword) { this.temporaryPassword = temporaryPassword; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    public String getConfirmedNewPassword() { return confirmedNewPassword; }
    public void setConfirmedNewPassword(String confirmedNewPassword) {
        this.confirmedNewPassword = confirmedNewPassword;
    }

    private boolean runAction(Runnable action, String successMessage) {
        try {
            action.run();
            addInfo(successMessage);
            return true;
        } catch (IllegalArgumentException | SecurityException exception) {
            addError(exception.getMessage());
        } catch (IllegalStateException exception) {
            addError(exception.getMessage());
        }
        return false;
    }

    private void clearCredentials() {
        password = "";
    }

    private void clearActiveDeal() {
        activeDealId = null;
        message = "";
    }

    private void clearListingForm() {
        selectedListingId = null;
        brokerEmail = null;
        sector = null;
        secondarySector = null;
        foundedYear = null;
        description = null;
        region = null;
        customerSummary = null;
        advantages = null;
        saleReason = null;
        revenue = null;
        ebitda = null;
        askingPrice = null;
        profitMargin = null;
        saleConditions = null;
        supportPeriod = null;
        companyName = null;
        exactLocation = null;
    }

    private HttpSession session() {
        return ((HttpServletRequest) FacesContext.getCurrentInstance()
                .getExternalContext().getRequest()).getSession();
    }

    private void rotateSession() {
        ((HttpServletRequest) FacesContext.getCurrentInstance()
                .getExternalContext().getRequest()).changeSessionId();
    }

    private void addInfo(String text) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, text, null));
    }

    private void addError(String text) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, text, null));
    }

    public UserView getUser() { return user; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSignatureName() { return signatureName; }
    public void setSignatureName(String signatureName) {
        this.signatureName = signatureName;
    }
    public Long getActiveDealId() { return activeDealId; }
    public void setActiveDealId(Long activeDealId) { this.activeDealId = activeDealId; }
    public String getSectors() { return sectors; }
    public void setSectors(String sectors) { this.sectors = sectors; }
    public String getPreferredRegion() { return preferredRegion; }
    public void setPreferredRegion(String preferredRegion) { this.preferredRegion = preferredRegion; }
    public BigDecimal getMinimumInvestment() { return minimumInvestment; }
    public void setMinimumInvestment(BigDecimal minimumInvestment) { this.minimumInvestment = minimumInvestment; }
    public BigDecimal getMaximumInvestment() { return maximumInvestment; }
    public void setMaximumInvestment(BigDecimal maximumInvestment) { this.maximumInvestment = maximumInvestment; }
    public String getSectorFilter() { return sectorFilter; }
    public void setSectorFilter(String sectorFilter) { this.sectorFilter = sectorFilter; }
    public String getRegionFilter() { return regionFilter; }
    public void setRegionFilter(String regionFilter) { this.regionFilter = regionFilter; }
    public BigDecimal getMaximumPriceFilter() { return maximumPriceFilter; }
    public void setMaximumPriceFilter(BigDecimal maximumPriceFilter) { this.maximumPriceFilter = maximumPriceFilter; }
    public BigDecimal getProcessedRevenue() { return processedRevenue; }
    public void setProcessedRevenue(BigDecimal processedRevenue) { this.processedRevenue = processedRevenue; }
    public BigDecimal getProcessedEbitda() { return processedEbitda; }
    public void setProcessedEbitda(BigDecimal processedEbitda) { this.processedEbitda = processedEbitda; }
    public BigDecimal getValuationMultiple() { return valuationMultiple; }
    public void setValuationMultiple(BigDecimal valuationMultiple) { this.valuationMultiple = valuationMultiple; }
    public String getBrokerEmail() { return brokerEmail; }
    public void setBrokerEmail(String brokerEmail) { this.brokerEmail = brokerEmail; }
    public Long getSelectedListingId() { return selectedListingId; }
    public String getSector() { return sector; }
    public void setSector(String sector) { this.sector = sector; }
    public String getSecondarySector() { return secondarySector; }
    public void setSecondarySector(String secondarySector) { this.secondarySector = secondarySector; }
    public Integer getFoundedYear() { return foundedYear; }
    public void setFoundedYear(Integer foundedYear) { this.foundedYear = foundedYear; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getCustomerSummary() { return customerSummary; }
    public void setCustomerSummary(String customerSummary) { this.customerSummary = customerSummary; }
    public String getAdvantages() { return advantages; }
    public void setAdvantages(String advantages) { this.advantages = advantages; }
    public String getSaleReason() { return saleReason; }
    public void setSaleReason(String saleReason) { this.saleReason = saleReason; }
    public BigDecimal getRevenue() { return revenue; }
    public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
    public BigDecimal getEbitda() { return ebitda; }
    public void setEbitda(BigDecimal ebitda) { this.ebitda = ebitda; }
    public BigDecimal getAskingPrice() { return askingPrice; }
    public void setAskingPrice(BigDecimal askingPrice) { this.askingPrice = askingPrice; }
    public BigDecimal getProfitMargin() { return profitMargin; }
    public void setProfitMargin(BigDecimal profitMargin) { this.profitMargin = profitMargin; }
    public String getSaleConditions() { return saleConditions; }
    public void setSaleConditions(String saleConditions) { this.saleConditions = saleConditions; }
    public String getSupportPeriod() { return supportPeriod; }
    public void setSupportPeriod(String supportPeriod) { this.supportPeriod = supportPeriod; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getExactLocation() { return exactLocation; }
    public void setExactLocation(String exactLocation) { this.exactLocation = exactLocation; }
}
