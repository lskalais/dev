package fr.alp.confidentia.web;

import fr.alp.confidentia.model.BuyerType;
import fr.alp.confidentia.model.BuyerKycDocumentType;
import fr.alp.confidentia.model.Role;
import fr.alp.confidentia.model.SellerDocumentType;
import fr.alp.confidentia.model.ListingTransactionType;
import fr.alp.confidentia.model.PartnerExpertCategory;
import fr.alp.confidentia.service.ActivityTaxonomy;
import fr.alp.confidentia.service.MarketCatalog;
import fr.alp.confidentia.service.MarketplaceService;
import fr.alp.confidentia.service.ViewModels.BuyerRegistration;
import fr.alp.confidentia.service.ViewModels.*;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.model.SelectItem;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Named("marketplace")
@SessionScoped
public class MarketplaceBean implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final String SESSION_LANGUAGE_KEY = "alp.language";

    @Inject
    private MarketplaceService service;

    private UserView user;
    private String email;
    private String password;
    private String fullName;
    private String organization;
    private String role = "BUYER";
    private String buyerType = "INDIVIDUAL";
    private String buyerPhone;
    private String buyerNationality;
    private String buyerResidence;
    private String buyerProfession;
    private BigDecimal indicativeFinancialCapacity;
    private String financialCapacityCurrencyCode = "XOF";
    private String buyerSectors;
    private String buyerLegalForm;
    private String buyerCountry;
    private String buyerCountryCode;
    private String buyerRegistrationNumber;
    private String buyerTaxIdentifier;
    private String buyerHeadOffice;
    private String buyerRepresentativeFunction;
    private String temporaryPassword;
    private String newPassword;
    private String confirmedNewPassword;
    private String message;
    private String signatureName;
    private String csrfToken;
    private String verificationFeedback;
    private Integer dataRoomAccessDurationDays = 30;
    private Long articleId;
    private Long publicArticleId;
    private String articleTitle;
    private String articleCategory;
    private String articleSummary;
    private String articleContent;
    private Long partnerExpertId;
    private String partnerExpertName;
    private String partnerExpertCategory = "ACCOUNTANT";
    private String partnerExpertCountry;
    private String partnerExpertLocation;
    private String partnerExpertSummary;
    private String partnerExpertContactEmail;
    private String partnerExpertPhone;
    private String partnerExpertWebsite;
    private boolean partnerExpertActive = true;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private String contactType = "CONTACT";
    private String contactSubject;
    private String contactBody;
    private final Map<Long, String> buyerKycFeedback = new HashMap<>();
    private final Map<Long, Boolean> buyerKycEmailVerified = new HashMap<>();
    private final Map<Long, Boolean> buyerKycPhoneVerified = new HashMap<>();
    private final Map<Long, Boolean> buyerKycSourceOfFundsRequired = new HashMap<>();
    private final Map<Long, Boolean> buyerKycBeneficialOwnersRequired = new HashMap<>();
    private final Map<Long, String> contactMessageStatuses = new HashMap<>();
    private final Map<Long, String> contactMessageNotes = new HashMap<>();
    private Long activeDealId;
    private Long selectedListingId;
    private static final SecureRandom RANDOM = new SecureRandom();

    private String sectors;
    private String preferredRegion;
    private BigDecimal minimumInvestment;
    private BigDecimal maximumInvestment;
    private String investmentCurrencyCode = "EUR";
    private String sectorFilter;
    private String domainFilter;
    private String regionFilter;
    private String countryFilter;
    private String currencyFilter;
    private BigDecimal minimumPriceFilter;
    private BigDecimal maximumPriceFilter;
    private BigDecimal minimumRevenueFilter;
    private BigDecimal maximumRevenueFilter;
    private BigDecimal minimumProfitMarginFilter;
    private Integer foundedAfterFilter;
    private Integer foundedBeforeFilter;
    private Integer minimumEmployeesFilter;
    private Integer maximumEmployeesFilter;
    private ListingTransactionType transactionTypeFilter;
    private boolean favoritesOnly;
    private boolean searchFiltersValid = true;
    private BigDecimal processedRevenue;
    private BigDecimal processedEbitda;
    private BigDecimal valuationMultiple;

    private ListingTransactionType transactionType = ListingTransactionType.FULL_SALE;
    private String proposedAppointmentTime;
    private String brokerEmail;
    private String sectorCategory;
    private List<String> activityDomains = new ArrayList<>();
    private String sector;
    private String secondarySector;
    private Integer foundedYear;
    private Integer approximateEmployeeCount;
    private boolean publicRevenueRangeAllowed;
    private String description;
    private String region;
    private String customerSummary;
    private String advantages;
    private String saleReason;
    private BigDecimal revenue;
    private BigDecimal ebitda;
    private BigDecimal askingPrice;
    private BigDecimal profitMargin;
    private BigDecimal netIncome;
    private BigDecimal totalAssets;
    private BigDecimal totalDebts;
    private BigDecimal fixedAssets;
    private String financialOutlook;
    private String saleConditions;
    private String supportPeriod;
    private String companyName;
    private String exactLocation;
    private String listingCountryCode;
    private String listingCurrencyCode = "EUR";

    public String register() {
        try {
            Role selectedRole = Role.valueOf(role);
            if (selectedRole == Role.BROKER && (organization == null || organization.isBlank())) {
                throw new IllegalArgumentException("Un cabinet ou une organisation est requis pour le profil courtier.");
            }
            if (selectedRole == Role.BUYER) {
                BuyerType selectedBuyerType;
                try {
                    selectedBuyerType = BuyerType.valueOf(buyerType);
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw new IllegalArgumentException("Choisissez le type de compte acquéreur.");
                }
                BuyerRegistration buyerRegistration = new BuyerRegistration(selectedBuyerType,
                        buyerPhone, buyerNationality, buyerResidence, buyerProfession,
                        indicativeFinancialCapacity, buyerSectors, buyerLegalForm,
                        buyerCountryCode == null || buyerCountryCode.isBlank()
                                ? buyerCountry : countryName(buyerCountryCode),
                        buyerRegistrationNumber, buyerTaxIdentifier, buyerHeadOffice,
                        buyerRepresentativeFunction, financialCapacityCurrencyCode, buyerCountryCode);
                user = service.register(email, password, fullName, organization, selectedRole, buyerRegistration);
            } else {
                user = service.register(email, password, fullName, organization, selectedRole);
            }
            clearActiveDeal();
            rotateSession();
            session().setAttribute("authenticatedUser", user);
            clearCredentials();
            clearBuyerRegistration();
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
        runAction(() -> {
                    if ("EUR".equals(investmentCurrencyCode)) {
                        service.saveBuyerCriteria(user, sectors, preferredRegion,
                                minimumInvestment, maximumInvestment);
                    } else {
                        service.saveBuyerCriteria(user, sectors, preferredRegion,
                                minimumInvestment, maximumInvestment, investmentCurrencyCode);
                    }
                },
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
            ListingSaveResult result = service.saveListing(user, selectedListingId, brokerEmail,
                    sectorCategory, activityDomains, sector, secondarySector, foundedYear,
                    description, region, customerSummary, advantages, saleReason,
                    revenue, ebitda, askingPrice, profitMargin, saleConditions, supportPeriod, companyName,
                    exactLocation, approximateEmployeeCount, publicRevenueRangeAllowed, transactionType,
                    netIncome, totalAssets, totalDebts, fixedAssets, financialOutlook,
                    listingCountryCode, listingCurrencyCode);
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
            listingCountryCode = listing.countryCode();
            listingCurrencyCode = listing.currencyCode();
            brokerEmail = listing.brokerEmail();
            setSectorCategory(listing.sectorCategory());
            activityDomains = new ArrayList<>(listing.activityDomains());
            sector = listing.sector();
            secondarySector = listing.secondarySector();
            foundedYear = listing.foundedYear();
            approximateEmployeeCount = listing.approximateEmployeeCount();
            publicRevenueRangeAllowed = listing.publicRevenueRangeAllowed();
            description = listing.description();
            region = listing.region();
            customerSummary = listing.customerSummary();
            advantages = listing.advantages();
            saleReason = listing.saleReason();
            revenue = listing.revenue();
            ebitda = listing.ebitda();
            askingPrice = listing.askingPrice();
            profitMargin = listing.profitMargin();
            netIncome = listing.netIncome();
            totalAssets = listing.totalAssets();
            totalDebts = listing.totalDebts();
            fixedAssets = listing.fixedAssets();
            financialOutlook = listing.financialOutlook();
            saleConditions = listing.saleConditions();
            supportPeriod = listing.supportPeriod();
            companyName = listing.companyName();
            exactLocation = listing.exactLocation();
            transactionType = listing.transactionType();
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

    public void toggleFavorite(Long listingId) {
        runAction(() -> service.toggleFavorite(user, listingId), "Votre liste de favoris a été mise à jour.");
    }

    public void toggleFavoritesOnly() {
        favoritesOnly = !favoritesOnly;
    }

    public void applyListingSearch() {
        try {
            service.validateListingSearchFilters(listingSearchFilters());
            searchFiltersValid = true;
        } catch (IllegalArgumentException exception) {
            searchFiltersValid = false;
            addError(exception.getMessage());
        }
    }

    public void clearListingSearch() {
        sectorFilter = null;
        domainFilter = null;
        regionFilter = null;
        countryFilter = null;
        currencyFilter = null;
        minimumPriceFilter = null;
        maximumPriceFilter = null;
        minimumRevenueFilter = null;
        maximumRevenueFilter = null;
        minimumProfitMarginFilter = null;
        foundedAfterFilter = null;
        foundedBeforeFilter = null;
        minimumEmployeesFilter = null;
        maximumEmployeesFilter = null;
        transactionTypeFilter = null;
        favoritesOnly = false;
        searchFiltersValid = true;
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

    public void advanceListingSaleProcess(Long listingId) {
        runAction(() -> service.advanceListingSaleProcess(user, listingId),
                "L'étape du processus de cession a été mise à jour.");
    }

    public void authorizeDealDocument(Long documentId) {
        runAction(() -> service.authorizeDealDocument(user, documentId, dataRoomAccessDurationDays),
                "La durée d’accès a été définie; la fenêtre démarrera après la validation ALP.");
    }

    public void validateDealDocument(Long documentId) {
        runAction(() -> service.validateDealDocument(user, documentId),
                "Le document a été validé par ALP.");
    }

    public void sendMessage(Long requestId) {
        if (runAction(() -> service.sendMessage(user, requestId, message), "Message chiffré envoyé.")) {
            message = "";
            activeDealId = requestId;
        }
    }

    public void proposeAppointment(Long requestId) {
        try {
            if (proposedAppointmentTime == null || proposedAppointmentTime.isBlank()) {
                throw new IllegalArgumentException("Choisissez une date et une heure.");
            }
            LocalDateTime proposedTime = LocalDateTime.parse(proposedAppointmentTime);
            service.proposeAppointment(user, requestId, proposedTime);
            proposedAppointmentTime = null;
            activeDealId = requestId;
            addInfo("Proposition de rendez-vous envoyée.");
        } catch (IllegalArgumentException | SecurityException exception) {
            addError(exception.getMessage());
        }
    }

    public void respondToAppointment(Long appointmentId, boolean accept) {
        runAction(() -> service.respondToAppointment(user, appointmentId, accept),
                accept ? "Rendez-vous confirmé." : "Proposition refusée.");
    }

    public void markNotificationRead(Long notificationId) {
        runAction(() -> service.markNotificationRead(user, notificationId), "Notification marquée comme lue.");
    }

    public void openDeal(Long requestId) {
        activeDealId = requestId;
        message = "";
    }

    public void acceptListing(Long listingId) {
        runAction(() -> service.acceptListing(user, listingId),
                "Dossier accepté. Vous en êtes le courtier mandaté et le teaser n'est plus modifiable par le cédant.");
    }

    public void suspendListing(Long listingId) {
        runAction(() -> service.setListingPublished(user, listingId, false),
                "L'annonce a été suspendue.");
    }

    public void publishListing(Long listingId) {
        runAction(() -> service.setListingPublished(user, listingId, true),
                "L'annonce est publiée.");
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
        if (user == null) {
            return List.of();
        }
        return service.listingsFor(user, searchFiltersValid ? listingSearchFilters()
                : new ListingSearchFilters(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null), favoritesOnly && isBuyer());
    }

    public List<ListingSaleProcessView> getListingSaleProcesses() {
        return user == null || !(isSeller() || isBroker() || isSuperUser())
                ? List.of() : requestCachedList("marketplaceListingSaleProcessesCache",
                () -> service.listingSaleProcessesFor(user));
    }

    private ListingSearchFilters listingSearchFilters() {
        return new ListingSearchFilters(sectorFilter, domainFilter, regionFilter, minimumPriceFilter,
                maximumPriceFilter, minimumRevenueFilter, maximumRevenueFilter,
                minimumProfitMarginFilter, foundedAfterFilter, foundedBeforeFilter,
                minimumEmployeesFilter, maximumEmployeesFilter, transactionTypeFilter,
                countryFilter, currencyFilter);
    }

    public Set<Long> getFavoriteListingIds() {
        if (!isBuyer()) {
            return Set.of();
        }
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return service.favoriteListingIds(user);
        }
        Map<String, Object> requestMap = context.getExternalContext().getRequestMap();
        @SuppressWarnings("unchecked")
        Set<Long> cached = (Set<Long>) requestMap.get("marketplaceFavoriteIdsCache");
        if (cached == null) {
            cached = service.favoriteListingIds(user);
            requestMap.put("marketplaceFavoriteIdsCache", cached);
        }
        return cached;
    }

    public List<NotificationView> getNotifications() {
        return user == null ? List.of()
                : requestCachedList("marketplaceNotificationsCache", () -> service.notificationsFor(user));
    }

    public List<AppointmentView> appointmentsFor(Long requestId) {
        return user == null ? List.of()
                : requestCachedList("marketplaceAppointmentsCache:" + requestId,
                () -> service.appointmentsFor(user, requestId));
    }

    public List<PublicListingView> getPublicListings() {
        return service.publicListings();
    }

    public String getLanguage() {
        Object language = FacesContext.getCurrentInstance().getExternalContext()
                .getSessionMap().get(SESSION_LANGUAGE_KEY);
        return "en".equals(language) ? "en" : "fr";
    }

    public void setLanguage(String language) {
        if (!"fr".equals(language) && !"en".equals(language)) {
            throw new IllegalArgumentException("Unsupported language: " + language);
        }
        FacesContext context = FacesContext.getCurrentInstance();
        context.getExternalContext().getSessionMap().put(SESSION_LANGUAGE_KEY, language);
        if (context.getViewRoot() != null) {
            context.getViewRoot().setLocale(java.util.Locale.forLanguageTag(language));
        }
    }

    public String applyLanguage() {
        return null;
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
        return user == null || activeDealId == null || isSuperUser()
                ? List.of() : service.messages(user, activeDealId);
    }

    public List<DocumentView> getActiveDealDocuments() {
        return user == null || activeDealId == null ? List.of() : service.dealDocuments(user, activeDealId);
    }

    public List<DocumentAccessLogView> getActiveDealAccessLogs() {
        return user == null || activeDealId == null || !(isSeller() || isVerificationReviewer())
                ? List.of() : service.dataRoomAccessLogs(user, activeDealId);
    }

    public Integer getDataRoomAccessDurationDays() {
        return dataRoomAccessDurationDays;
    }

    public void setDataRoomAccessDurationDays(Integer dataRoomAccessDurationDays) {
        this.dataRoomAccessDurationDays = dataRoomAccessDurationDays;
    }

    public List<DocumentView> documentsFor(Long listingId) {
        return user == null ? List.of() : service.documentsFor(user, listingId);
    }

    public List<DocumentView> getSellerVerificationDocuments() {
        return isSeller() ? service.sellerVerificationDocuments(user) : List.of();
    }

    public SellerVerificationStatusView getSellerVerificationStatus() {
        return isSeller() ? service.sellerVerificationStatus(user) : null;
    }

    public List<SellerVerificationView> getSellerVerificationQueue() {
        return isVerificationReviewer() ? service.sellerVerificationQueue(user) : List.of();
    }

    public List<DocumentView> sellerVerificationDocumentsFor(Long sellerId) {
        return isVerificationReviewer() ? service.sellerVerificationDocuments(user, sellerId) : List.of();
    }

    public BuyerKycView getBuyerKycStatus() {
        if (!isBuyer()) {
            return null;
        }
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return service.buyerKycStatus(user);
        }
        Map<String, Object> requestMap = context.getExternalContext().getRequestMap();
        String cacheKey = "marketplaceBuyerKycCache";
        if (requestMap.containsKey(cacheKey)) {
            return (BuyerKycView) requestMap.get(cacheKey);
        }
        BuyerKycView status = service.buyerKycStatus(user);
        requestMap.put(cacheKey, status);
        return status;
    }

    public List<BuyerKycView> getBuyerKycQueue() {
        if (!isVerificationReviewer()) {
            return List.of();
        }
        List<BuyerKycView> queue = service.buyerKycQueue(user);
        queue.forEach(kyc -> {
            buyerKycEmailVerified.putIfAbsent(kyc.buyerId(), kyc.emailVerified());
            buyerKycPhoneVerified.putIfAbsent(kyc.buyerId(), kyc.phoneVerified());
            buyerKycSourceOfFundsRequired.putIfAbsent(kyc.buyerId(), kyc.sourceOfFundsRequired());
            buyerKycBeneficialOwnersRequired.putIfAbsent(kyc.buyerId(), kyc.beneficialOwnersRequired());
        });
        return queue;
    }

    public Map<Long, String> getBuyerKycFeedback() {
        return buyerKycFeedback;
    }

    public Map<Long, Boolean> getBuyerKycEmailVerified() {
        return buyerKycEmailVerified;
    }

    public Map<Long, Boolean> getBuyerKycPhoneVerified() {
        return buyerKycPhoneVerified;
    }

    public Map<Long, Boolean> getBuyerKycSourceOfFundsRequired() {
        return buyerKycSourceOfFundsRequired;
    }

    public Map<Long, Boolean> getBuyerKycBeneficialOwnersRequired() {
        return buyerKycBeneficialOwnersRequired;
    }

    public List<SelectItem> getBuyerKycDocumentTypeOptions() {
        BuyerKycView kyc = getBuyerKycStatus();
        if (kyc == null) {
            return List.of();
        }
        return java.util.Arrays.stream(BuyerKycDocumentType.values())
                .filter(type -> type.isAvailableFor(kyc.buyerType()))
                .map(type -> new SelectItem(type.name(), type.label()))
                .toList();
    }

    public String buyerKycDocumentTypeLabel(String type) {
        try {
            return BuyerKycDocumentType.valueOf(type).label();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return "Pièce KYC";
        }
    }

    public String startBuyerKycReview(Long buyerId) {
        return updateBuyerKyc(buyerId, "UNDER_REVIEW", "La vérification KYC a commencé.");
    }

    public String validateBuyerKyc(Long buyerId) {
        return updateBuyerKyc(buyerId, "VALIDATED", "Le profil acquéreur a été vérifié.");
    }

    public String requestBuyerKycCorrections(Long buyerId) {
        return updateBuyerKyc(buyerId, "NEEDS_CORRECTION", "La demande de complément KYC a été enregistrée.");
    }

    private String updateBuyerKyc(Long buyerId, String status, String successMessage) {
        try {
            service.updateBuyerKyc(user, buyerId, status, buyerKycFeedback.get(buyerId),
                    Boolean.TRUE.equals(buyerKycEmailVerified.get(buyerId)),
                    Boolean.TRUE.equals(buyerKycPhoneVerified.get(buyerId)),
                    Boolean.TRUE.equals(buyerKycSourceOfFundsRequired.get(buyerId)),
                    Boolean.TRUE.equals(buyerKycBeneficialOwnersRequired.get(buyerId)));
            buyerKycFeedback.remove(buyerId);
            addInfo(successMessage);
        } catch (IllegalArgumentException | SecurityException | IllegalStateException exception) {
            addError(exception.getMessage());
        }
        return null;
    }

    public String verificationStatusLabel(fr.alp.confidentia.model.SellerVerificationStatus status) {
        return status == null ? "Documents en attente" : status.label();
    }

    public String startSellerVerification(Long sellerId) {
        return updateSellerVerification(sellerId, "UNDER_REVIEW", "La vérification du dossier a commencé.");
    }

    public String validateSellerDocuments(Long sellerId) {
        return updateSellerVerification(sellerId, "DOCUMENTS_VALIDATED", "Les pièces justificatives sont validées.");
    }

    public String requestSellerCorrections(Long sellerId) {
        return updateSellerVerification(sellerId, "NEEDS_CORRECTION",
                "La demande de complément a été enregistrée.");
    }

    public String validateSellerAccount(Long sellerId) {
        return updateSellerVerification(sellerId, "ACCOUNT_VALIDATED", "Le compte cédant est validé.");
    }

    private String updateSellerVerification(Long sellerId, String status, String successMessage) {
        try {
            service.updateSellerVerification(user, sellerId, status, verificationFeedback);
            verificationFeedback = "";
            addInfo(successMessage);
        } catch (IllegalArgumentException | SecurityException | IllegalStateException exception) {
            addError(exception.getMessage());
        }
        return null;
    }

    public List<SelectItem> getSellerDocumentTypeOptions() {
        return java.util.Arrays.stream(SellerDocumentType.values())
                .map(type -> new SelectItem(type.name(), type.label()))
                .toList();
    }

    public String sellerDocumentTypeLabel(String type) {
        try {
            return SellerDocumentType.valueOf(type).label();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return "Pièce de vérification";
        }
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

    public boolean isVerificationReviewer() {
        return user != null && ("BROKER".equals(user.role()) || "SUPER".equals(user.role()));
    }

    public List<AdminUserView> getAdminUsers() {
        if (!isSuperUser()) {
            return List.of();
        }
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return service.usersForAdministration(user);
        }
        Map<String, Object> requestMap = context.getExternalContext().getRequestMap();
        String cacheKey = "marketplaceAdminUsersCache";
        if (requestMap.containsKey(cacheKey)) {
            @SuppressWarnings("unchecked")
            List<AdminUserView> cached = (List<AdminUserView>) requestMap.get(cacheKey);
            return cached;
        }
        List<AdminUserView> users = service.usersForAdministration(user);
        requestMap.put(cacheKey, users);
        return users;
    }

    public AdminDashboardView getAdminDashboard() {
        if (!isSuperUser()) {
            return null;
        }
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return service.adminDashboardFor(user);
        }
        Map<String, Object> requestMap = context.getExternalContext().getRequestMap();
        String cacheKey = "marketplaceAdminDashboardCache";
        if (requestMap.containsKey(cacheKey)) {
            return (AdminDashboardView) requestMap.get(cacheKey);
        }
        AdminDashboardView dashboard = service.adminDashboardFor(user);
        requestMap.put(cacheKey, dashboard);
        return dashboard;
    }

    public List<ArticleView> getPublishedArticles() {
        return requestCachedList("marketplacePublishedArticlesCache", service::publishedArticles);
    }

    public ArticleView getPublicArticle() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return service.publishedArticle(publicArticleId);
        }
        Map<String, Object> requestMap = context.getExternalContext().getRequestMap();
        String cacheKey = "marketplacePublicArticleCache";
        if (requestMap.containsKey(cacheKey)) {
            return (ArticleView) requestMap.get(cacheKey);
        }
        ArticleView article = service.publishedArticle(publicArticleId);
        requestMap.put(cacheKey, article);
        return article;
    }

    public List<ArticleView> getArticlesForAdministration() {
        return isSuperUser()
                ? requestCachedList("marketplaceArticlesAdministrationCache",
                () -> service.articlesForAdministration(user))
                : List.of();
    }

    public void startNewArticle() {
        clearArticleForm();
    }

    public void loadArticleForEdit(Long id) {
        if (!isSuperUser()) {
            addError("Cette action est réservée à l’administrateur.");
            return;
        }
        ArticleView article = service.articlesForAdministration(user).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElse(null);
        if (article == null) {
            addError("Article introuvable.");
            return;
        }
        articleId = article.id();
        articleTitle = article.title();
        articleCategory = article.category();
        articleSummary = article.summary();
        articleContent = article.content();
    }

    public void saveArticle() {
        if (runAction(() -> service.saveArticle(user, articleId, articleTitle, articleCategory,
                articleSummary, articleContent), "L’article a été enregistré comme brouillon ou mis à jour.")) {
            clearArticleForm();
        }
    }

    public void setArticlePublished(Long id, boolean published) {
        runAction(() -> service.setArticlePublished(user, id, published),
                published ? "L’article est publié." : "L’article a été retiré de la publication.");
    }

    public boolean isPartnerDirectoryAvailable() {
        return user != null && service.hasPartnerDirectoryAccess(user);
    }

    public List<PartnerExpertView> getPartnerExperts() {
        if (!isPartnerDirectoryAvailable()) {
            return List.of();
        }
        return requestCachedList("marketplacePartnerExpertsCache", () -> service.partnerExpertsFor(user));
    }

    public void loadPartnerExpertForEdit(PartnerExpertView partner) {
        if (!isSuperUser() || partner == null) {
            addError("Cette action est réservée à l’administrateur.");
            return;
        }
        partnerExpertId = partner.id();
        partnerExpertName = partner.name();
        partnerExpertCategory = partner.category();
        partnerExpertCountry = partner.country();
        partnerExpertLocation = partner.location();
        partnerExpertSummary = partner.summary();
        partnerExpertContactEmail = partner.contactEmail();
        partnerExpertPhone = partner.phone();
        partnerExpertWebsite = partner.website();
        partnerExpertActive = partner.active();
    }

    public void savePartnerExpert() {
        if (runAction(() -> service.savePartnerExpert(user, partnerExpertId, partnerExpertName,
                partnerExpertCategory == null ? null : PartnerExpertCategory.valueOf(partnerExpertCategory),
                partnerExpertCountry, partnerExpertLocation,
                partnerExpertSummary, partnerExpertContactEmail, partnerExpertPhone, partnerExpertWebsite,
                partnerExpertActive), "Le partenaire ou expert a été enregistré.")) {
            clearPartnerExpertForm();
        }
    }

    public void setPartnerExpertActive(Long partnerId, boolean active) {
        runAction(() -> service.setPartnerExpertActive(user, partnerId, active),
                active ? "Le partenaire est de nouveau visible." : "Le partenaire a été suspendu.");
    }

    public void clearPartnerExpertForm() {
        partnerExpertId = null;
        partnerExpertName = null;
        partnerExpertCategory = "ACCOUNTANT";
        partnerExpertCountry = null;
        partnerExpertLocation = null;
        partnerExpertSummary = null;
        partnerExpertContactEmail = null;
        partnerExpertPhone = null;
        partnerExpertWebsite = null;
        partnerExpertActive = true;
    }

    public List<ContactMessageView> getContactMessages() {
        if (!isSuperUser()) {
            return List.of();
        }
        List<ContactMessageView> messages = requestCachedList("marketplaceContactMessagesCache",
                () -> service.contactMessagesFor(user));
        messages.forEach(item -> {
            contactMessageStatuses.putIfAbsent(item.id(), item.status());
            contactMessageNotes.putIfAbsent(item.id(), item.adminNote());
        });
        return messages;
    }

    public void submitContactMessage() {
        if (runAction(() -> service.submitContactMessage(contactName, contactEmail, contactPhone, contactType,
                contactSubject, contactBody), "Votre message a été transmis à ALP.")) {
            clearContactForm();
        }
    }

    public void updateContactMessage(Long id) {
        runAction(() -> service.updateContactMessage(user, id, contactMessageStatuses.get(id),
                contactMessageNotes.get(id)), "Le suivi du message a été mis à jour.");
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
    public String getVerificationFeedback() { return verificationFeedback; }
    public void setVerificationFeedback(String verificationFeedback) {
        this.verificationFeedback = verificationFeedback;
    }
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

    @SuppressWarnings("unchecked")
    private <T> List<T> requestCachedList(String key, Supplier<List<T>> loader) {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return loader.get();
        }
        Map<String, Object> requestMap = context.getExternalContext().getRequestMap();
        Object cached = requestMap.get(key);
        if (cached != null) {
            return (List<T>) cached;
        }
        List<T> loaded = loader.get();
        requestMap.put(key, loaded);
        return loaded;
    }

    private void clearCredentials() {
        password = "";
    }

    private void clearBuyerRegistration() {
        buyerPhone = null;
        buyerNationality = null;
        buyerResidence = null;
        buyerProfession = null;
        indicativeFinancialCapacity = null;
        financialCapacityCurrencyCode = "XOF";
        buyerSectors = null;
        buyerLegalForm = null;
        buyerCountry = null;
        buyerCountryCode = null;
        buyerRegistrationNumber = null;
        buyerTaxIdentifier = null;
        buyerHeadOffice = null;
        buyerRepresentativeFunction = null;
    }

    private void clearActiveDeal() {
        activeDealId = null;
        message = "";
    }

    private void clearArticleForm() {
        articleId = null;
        articleTitle = null;
        articleCategory = null;
        articleSummary = null;
        articleContent = null;
    }

    private void clearContactForm() {
        contactName = null;
        contactEmail = null;
        contactPhone = null;
        contactType = "CONTACT";
        contactSubject = null;
        contactBody = null;
    }

    private void clearListingForm() {
        selectedListingId = null;
        brokerEmail = null;
        sectorCategory = null;
        activityDomains = new ArrayList<>();
        sector = null;
        secondarySector = null;
        foundedYear = null;
        approximateEmployeeCount = null;
        publicRevenueRangeAllowed = false;
        description = null;
        region = null;
        customerSummary = null;
        advantages = null;
        saleReason = null;
        revenue = null;
        ebitda = null;
        askingPrice = null;
        profitMargin = null;
        netIncome = null;
        totalAssets = null;
        totalDebts = null;
        fixedAssets = null;
        financialOutlook = null;
        saleConditions = null;
        supportPeriod = null;
        companyName = null;
        exactLocation = null;
        listingCountryCode = null;
        listingCurrencyCode = "EUR";
        transactionType = ListingTransactionType.FULL_SALE;
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
    public String getBuyerType() { return buyerType; }
    public void setBuyerType(String buyerType) { this.buyerType = buyerType; }
    public String getBuyerPhone() { return buyerPhone; }
    public void setBuyerPhone(String buyerPhone) { this.buyerPhone = buyerPhone; }
    public String getBuyerNationality() { return buyerNationality; }
    public void setBuyerNationality(String buyerNationality) { this.buyerNationality = buyerNationality; }
    public String getBuyerResidence() { return buyerResidence; }
    public void setBuyerResidence(String buyerResidence) { this.buyerResidence = buyerResidence; }
    public String getBuyerProfession() { return buyerProfession; }
    public void setBuyerProfession(String buyerProfession) { this.buyerProfession = buyerProfession; }
    public BigDecimal getIndicativeFinancialCapacity() { return indicativeFinancialCapacity; }
    public void setIndicativeFinancialCapacity(BigDecimal indicativeFinancialCapacity) {
        this.indicativeFinancialCapacity = indicativeFinancialCapacity;
    }
    public String getFinancialCapacityCurrencyCode() { return financialCapacityCurrencyCode; }
    public void setFinancialCapacityCurrencyCode(String financialCapacityCurrencyCode) {
        this.financialCapacityCurrencyCode = financialCapacityCurrencyCode;
    }
    public String getBuyerSectors() { return buyerSectors; }
    public void setBuyerSectors(String buyerSectors) { this.buyerSectors = buyerSectors; }
    public String getBuyerLegalForm() { return buyerLegalForm; }
    public void setBuyerLegalForm(String buyerLegalForm) { this.buyerLegalForm = buyerLegalForm; }
    public String getBuyerCountry() { return buyerCountry; }
    public void setBuyerCountry(String buyerCountry) { this.buyerCountry = buyerCountry; }
    public String getBuyerCountryCode() { return buyerCountryCode; }
    public void setBuyerCountryCode(String buyerCountryCode) { this.buyerCountryCode = buyerCountryCode; }
    public String getListingCountryCode() { return listingCountryCode; }
    public void setListingCountryCode(String listingCountryCode) { this.listingCountryCode = listingCountryCode; }
    public String getListingCurrencyCode() { return listingCurrencyCode; }
    public void setListingCurrencyCode(String listingCurrencyCode) {
        this.listingCurrencyCode = listingCurrencyCode;
    }
    public List<SelectItem> getCountryOptions() {
        return MarketCatalog.countryOptions(displayLocale());
    }
    public List<SelectItem> getCurrencyOptions() {
        return MarketCatalog.currencyOptions(displayLocale());
    }
    public String countryName(String countryCode) {
        return MarketCatalog.countryName(countryCode, displayLocale());
    }
    public String formatCurrency(BigDecimal amount, String currencyCode) {
        return MarketCatalog.formatCurrency(amount, currencyCode, displayLocale());
    }
    private java.util.Locale displayLocale() {
        FacesContext context = FacesContext.getCurrentInstance();
        return context == null || context.getViewRoot() == null
                ? java.util.Locale.FRENCH : context.getViewRoot().getLocale();
    }
    public String getBuyerRegistrationNumber() { return buyerRegistrationNumber; }
    public void setBuyerRegistrationNumber(String buyerRegistrationNumber) {
        this.buyerRegistrationNumber = buyerRegistrationNumber;
    }
    public String getBuyerTaxIdentifier() { return buyerTaxIdentifier; }
    public void setBuyerTaxIdentifier(String buyerTaxIdentifier) {
        this.buyerTaxIdentifier = buyerTaxIdentifier;
    }
    public String getBuyerHeadOffice() { return buyerHeadOffice; }
    public void setBuyerHeadOffice(String buyerHeadOffice) { this.buyerHeadOffice = buyerHeadOffice; }
    public String getBuyerRepresentativeFunction() { return buyerRepresentativeFunction; }
    public void setBuyerRepresentativeFunction(String buyerRepresentativeFunction) {
        this.buyerRepresentativeFunction = buyerRepresentativeFunction;
    }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getArticleId() { return articleId; }
    public String getArticleTitle() { return articleTitle; }
    public void setArticleTitle(String articleTitle) { this.articleTitle = articleTitle; }
    public String getArticleCategory() { return articleCategory; }
    public void setArticleCategory(String articleCategory) { this.articleCategory = articleCategory; }
    public String getArticleSummary() { return articleSummary; }
    public void setArticleSummary(String articleSummary) { this.articleSummary = articleSummary; }
    public String getArticleContent() { return articleContent; }
    public void setArticleContent(String articleContent) { this.articleContent = articleContent; }
    public Long getPublicArticleId() { return publicArticleId; }
    public void setPublicArticleId(Long publicArticleId) { this.publicArticleId = publicArticleId; }
    public Long getPartnerExpertId() { return partnerExpertId; }
    public String getPartnerExpertName() { return partnerExpertName; }
    public void setPartnerExpertName(String partnerExpertName) { this.partnerExpertName = partnerExpertName; }
    public String getPartnerExpertCategory() { return partnerExpertCategory; }
    public void setPartnerExpertCategory(String partnerExpertCategory) {
        this.partnerExpertCategory = partnerExpertCategory;
    }
    public String getPartnerExpertCountry() { return partnerExpertCountry; }
    public void setPartnerExpertCountry(String partnerExpertCountry) {
        this.partnerExpertCountry = partnerExpertCountry;
    }
    public String getPartnerExpertLocation() { return partnerExpertLocation; }
    public void setPartnerExpertLocation(String partnerExpertLocation) {
        this.partnerExpertLocation = partnerExpertLocation;
    }
    public String getPartnerExpertSummary() { return partnerExpertSummary; }
    public void setPartnerExpertSummary(String partnerExpertSummary) {
        this.partnerExpertSummary = partnerExpertSummary;
    }
    public String getPartnerExpertContactEmail() { return partnerExpertContactEmail; }
    public void setPartnerExpertContactEmail(String partnerExpertContactEmail) {
        this.partnerExpertContactEmail = partnerExpertContactEmail;
    }
    public String getPartnerExpertPhone() { return partnerExpertPhone; }
    public void setPartnerExpertPhone(String partnerExpertPhone) { this.partnerExpertPhone = partnerExpertPhone; }
    public String getPartnerExpertWebsite() { return partnerExpertWebsite; }
    public void setPartnerExpertWebsite(String partnerExpertWebsite) {
        this.partnerExpertWebsite = partnerExpertWebsite;
    }
    public boolean isPartnerExpertActive() { return partnerExpertActive; }
    public void setPartnerExpertActive(boolean partnerExpertActive) {
        this.partnerExpertActive = partnerExpertActive;
    }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getContactType() { return contactType; }
    public void setContactType(String contactType) { this.contactType = contactType; }
    public String getContactSubject() { return contactSubject; }
    public void setContactSubject(String contactSubject) { this.contactSubject = contactSubject; }
    public String getContactBody() { return contactBody; }
    public void setContactBody(String contactBody) { this.contactBody = contactBody; }
    public Map<Long, String> getContactMessageStatuses() { return contactMessageStatuses; }
    public Map<Long, String> getContactMessageNotes() { return contactMessageNotes; }
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
    public String getInvestmentCurrencyCode() { return investmentCurrencyCode; }
    public void setInvestmentCurrencyCode(String investmentCurrencyCode) {
        this.investmentCurrencyCode = investmentCurrencyCode;
    }
    public String getSectorFilter() { return sectorFilter; }
    public void setSectorFilter(String sectorFilter) { this.sectorFilter = sectorFilter; }
    public String getDomainFilter() { return domainFilter; }
    public void setDomainFilter(String domainFilter) { this.domainFilter = domainFilter; }
    public String getRegionFilter() { return regionFilter; }
    public void setRegionFilter(String regionFilter) { this.regionFilter = regionFilter; }
    public String getCountryFilter() { return countryFilter; }
    public void setCountryFilter(String countryFilter) { this.countryFilter = countryFilter; }
    public String getCurrencyFilter() { return currencyFilter; }
    public void setCurrencyFilter(String currencyFilter) { this.currencyFilter = currencyFilter; }
    public BigDecimal getMinimumPriceFilter() { return minimumPriceFilter; }
    public void setMinimumPriceFilter(BigDecimal minimumPriceFilter) { this.minimumPriceFilter = minimumPriceFilter; }
    public BigDecimal getMaximumPriceFilter() { return maximumPriceFilter; }
    public void setMaximumPriceFilter(BigDecimal maximumPriceFilter) { this.maximumPriceFilter = maximumPriceFilter; }
    public BigDecimal getMinimumRevenueFilter() { return minimumRevenueFilter; }
    public void setMinimumRevenueFilter(BigDecimal minimumRevenueFilter) { this.minimumRevenueFilter = minimumRevenueFilter; }
    public BigDecimal getMaximumRevenueFilter() { return maximumRevenueFilter; }
    public void setMaximumRevenueFilter(BigDecimal maximumRevenueFilter) { this.maximumRevenueFilter = maximumRevenueFilter; }
    public BigDecimal getMinimumProfitMarginFilter() { return minimumProfitMarginFilter; }
    public void setMinimumProfitMarginFilter(BigDecimal minimumProfitMarginFilter) {
        this.minimumProfitMarginFilter = minimumProfitMarginFilter;
    }
    public Integer getFoundedAfterFilter() { return foundedAfterFilter; }
    public void setFoundedAfterFilter(Integer foundedAfterFilter) { this.foundedAfterFilter = foundedAfterFilter; }
    public Integer getFoundedBeforeFilter() { return foundedBeforeFilter; }
    public void setFoundedBeforeFilter(Integer foundedBeforeFilter) { this.foundedBeforeFilter = foundedBeforeFilter; }
    public Integer getMinimumEmployeesFilter() { return minimumEmployeesFilter; }
    public void setMinimumEmployeesFilter(Integer minimumEmployeesFilter) {
        this.minimumEmployeesFilter = minimumEmployeesFilter;
    }
    public Integer getMaximumEmployeesFilter() { return maximumEmployeesFilter; }
    public void setMaximumEmployeesFilter(Integer maximumEmployeesFilter) {
        this.maximumEmployeesFilter = maximumEmployeesFilter;
    }
    public ListingTransactionType getTransactionTypeFilter() { return transactionTypeFilter; }
    public void setTransactionTypeFilter(ListingTransactionType transactionTypeFilter) {
        this.transactionTypeFilter = transactionTypeFilter;
    }
    public boolean isFavoritesOnly() { return favoritesOnly; }
    public ListingTransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(ListingTransactionType transactionType) {
        this.transactionType = transactionType == null ? ListingTransactionType.FULL_SALE : transactionType;
    }
    public String getProposedAppointmentTime() { return proposedAppointmentTime; }
    public void setProposedAppointmentTime(String proposedAppointmentTime) {
        this.proposedAppointmentTime = proposedAppointmentTime;
    }
    public BigDecimal getProcessedRevenue() { return processedRevenue; }
    public void setProcessedRevenue(BigDecimal processedRevenue) { this.processedRevenue = processedRevenue; }
    public BigDecimal getProcessedEbitda() { return processedEbitda; }
    public void setProcessedEbitda(BigDecimal processedEbitda) { this.processedEbitda = processedEbitda; }
    public BigDecimal getValuationMultiple() { return valuationMultiple; }
    public void setValuationMultiple(BigDecimal valuationMultiple) { this.valuationMultiple = valuationMultiple; }
    public String getBrokerEmail() { return brokerEmail; }
    public void setBrokerEmail(String brokerEmail) { this.brokerEmail = brokerEmail; }
    public List<SelectItem> getSectorCategoryOptions() {
        return ActivityTaxonomy.sectors().stream()
                .map(option -> new SelectItem(option.value(), option.label()))
                .toList();
    }
    public List<SelectItem> getActivityDomainOptions() {
        return ActivityTaxonomy.domainsFor(sectorCategory).stream()
                .map(option -> new SelectItem(option.value(), option.label()))
                .toList();
    }
    public List<SelectItem> getTransactionTypeOptions() {
        return List.of(
                new SelectItem(ListingTransactionType.FULL_SALE, "Cession totale"),
                new SelectItem(ListingTransactionType.PARTIAL_SALE, "Cession partielle"),
                new SelectItem(ListingTransactionType.ASSET_SALE, "Cession d'actifs"));
    }
    public List<SelectItem> getPartnerExpertCategoryOptions() {
        return java.util.Arrays.stream(PartnerExpertCategory.values())
                .map(category -> new SelectItem(category.name(), category.label()))
                .toList();
    }
    public String getSectorCategory() { return sectorCategory; }
    public void setSectorCategory(String sectorCategory) {
        if (!java.util.Objects.equals(this.sectorCategory, sectorCategory)) {
            activityDomains = new ArrayList<>();
        }
        this.sectorCategory = sectorCategory;
    }
    public List<String> getActivityDomains() { return activityDomains; }
    public void setActivityDomains(List<String> activityDomains) {
        this.activityDomains = activityDomains == null ? new ArrayList<>() : new ArrayList<>(activityDomains);
    }
    public Long getSelectedListingId() { return selectedListingId; }
    public String getSector() { return sector; }
    public void setSector(String sector) { this.sector = sector; }
    public String getSecondarySector() { return secondarySector; }
    public void setSecondarySector(String secondarySector) { this.secondarySector = secondarySector; }
    public Integer getFoundedYear() { return foundedYear; }
    public void setFoundedYear(Integer foundedYear) { this.foundedYear = foundedYear; }
    public Integer getApproximateEmployeeCount() { return approximateEmployeeCount; }
    public void setApproximateEmployeeCount(Integer approximateEmployeeCount) {
        this.approximateEmployeeCount = approximateEmployeeCount;
    }
    public boolean isPublicRevenueRangeAllowed() { return publicRevenueRangeAllowed; }
    public void setPublicRevenueRangeAllowed(boolean publicRevenueRangeAllowed) {
        this.publicRevenueRangeAllowed = publicRevenueRangeAllowed;
    }
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
    public BigDecimal getNetIncome() { return netIncome; }
    public void setNetIncome(BigDecimal netIncome) { this.netIncome = netIncome; }
    public BigDecimal getTotalAssets() { return totalAssets; }
    public void setTotalAssets(BigDecimal totalAssets) { this.totalAssets = totalAssets; }
    public BigDecimal getTotalDebts() { return totalDebts; }
    public void setTotalDebts(BigDecimal totalDebts) { this.totalDebts = totalDebts; }
    public BigDecimal getFixedAssets() { return fixedAssets; }
    public void setFixedAssets(BigDecimal fixedAssets) { this.fixedAssets = fixedAssets; }
    public String getFinancialOutlook() { return financialOutlook; }
    public void setFinancialOutlook(String financialOutlook) { this.financialOutlook = financialOutlook; }
    public String getSaleConditions() { return saleConditions; }
    public void setSaleConditions(String saleConditions) { this.saleConditions = saleConditions; }
    public String getSupportPeriod() { return supportPeriod; }
    public void setSupportPeriod(String supportPeriod) { this.supportPeriod = supportPeriod; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getExactLocation() { return exactLocation; }
    public void setExactLocation(String exactLocation) { this.exactLocation = exactLocation; }
}
