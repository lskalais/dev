package fr.alp.confidentia.service;

import fr.alp.confidentia.model.BuyerType;
import fr.alp.confidentia.model.BuyerKycStatus;
import fr.alp.confidentia.model.SellerVerificationStatus;
import fr.alp.confidentia.model.ListingTransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ViewModels {
    private ViewModels() {
    }

    public record UserView(Long id, String email, String name, String organization, String role,
                           boolean passwordChangeRequired)
            implements java.io.Serializable {
    }

    public record BuyerRegistration(BuyerType type, String phone, String nationality, String residence,
                                   String profession, BigDecimal indicativeFinancialCapacity, String sectors,
                                   String legalForm, String country, String registrationNumber,
                                   String taxIdentifier, String headOffice, String representativeFunction,
                                   String financialCapacityCurrencyCode, String countryCode) {
        public BuyerRegistration(BuyerType type, String phone, String nationality, String residence,
                                 String profession, BigDecimal indicativeFinancialCapacity, String sectors,
                                 String legalForm, String country, String registrationNumber,
                                 String taxIdentifier, String headOffice, String representativeFunction) {
            this(type, phone, nationality, residence, profession, indicativeFinancialCapacity, sectors,
                    legalForm, country, registrationNumber, taxIdentifier, headOffice,
                    representativeFunction, "XOF", null);
        }

        public BuyerRegistration(BuyerType type, String phone, String nationality, String residence,
                                 String profession, BigDecimal indicativeFinancialCapacity, String sectors,
                                 String legalForm, String country, String registrationNumber,
                                 String taxIdentifier, String headOffice, String representativeFunction,
                                 String financialCapacityCurrencyCode) {
            this(type, phone, nationality, residence, profession, indicativeFinancialCapacity, sectors,
                    legalForm, country, registrationNumber, taxIdentifier, headOffice,
                    representativeFunction, financialCapacityCurrencyCode, null);
        }
    }

    public record AdminUserView(Long id, String email, String name, String organization, String role,
                                Instant createdAt, boolean passwordChangeRequired, BuyerType buyerType,
                                BuyerKycStatus buyerKycStatus, Instant buyerKycUpdatedAt)
            implements java.io.Serializable {
    }

    public record AdminDashboardView(long totalUsers, long buyerKycToReview, long sellerVerificationsToReview,
                                     long publishedListings, long suspendedListings, long activeDealRequests,
                                     long newContactMessages) implements java.io.Serializable {
    }

    public record SellerVerificationStatusView(SellerVerificationStatus status, String feedback,
                                               Instant updatedAt) {
    }

    public record SellerVerificationView(Long sellerId, String name, String email, String organization,
                                         SellerVerificationStatus status, String feedback, Instant updatedAt,
                                         java.util.List<DocumentView> documents) {
    }

    public record BuyerKycView(Long buyerId, String name, String email, String organization, BuyerType buyerType,
                               String phone, String nationality, String residence, String profession,
                               BigDecimal indicativeFinancialCapacity, String financialCapacityCurrencyCode,
                               String legalForm, String country, String countryCode,
                               String registrationNumber, String taxIdentifier, String headOffice,
                               String representativeFunction,
                               BuyerKycStatus status, String feedback, boolean emailVerified,
                               boolean phoneVerified, boolean sourceOfFundsRequired,
                               boolean beneficialOwnersRequired, Instant updatedAt,
                               java.util.List<DocumentView> documents) {
    }

    public record ListingView(Long id, String sectorCategory, String activityDomains,
                              String sector, String secondarySector, Integer foundedYear,
                              String region, String description,
                              BigDecimal revenue, BigDecimal ebitda, BigDecimal askingPrice,
                              long views, int matchPercent, String companyName, String exactLocation,
                              String status, ListingTransactionType transactionType,
                              BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalDebts,
                              BigDecimal fixedAssets, String financialOutlook,
                              boolean professionalValuationValidated,
                              String countryCode, String currencyCode) {
        public ListingView(Long id, String sectorCategory, String activityDomains,
                           String sector, String secondarySector, Integer foundedYear,
                           String region, String description, BigDecimal revenue, BigDecimal ebitda,
                           BigDecimal askingPrice, long views, int matchPercent, String companyName,
                           String exactLocation, String status, ListingTransactionType transactionType) {
            this(id, sectorCategory, activityDomains, sector, secondarySector, foundedYear, region,
                    description, revenue, ebitda, askingPrice, views, matchPercent, companyName,
                    exactLocation, status, transactionType, null, null, null, null, null, false,
                    null, "EUR");
        }

        public ListingView(Long id, String sectorCategory, String activityDomains,
                           String sector, String secondarySector, Integer foundedYear,
                           String region, String description, BigDecimal revenue, BigDecimal ebitda,
                           BigDecimal askingPrice, long views, int matchPercent, String companyName,
                           String exactLocation, String status) {
            this(id, sectorCategory, activityDomains, sector, secondarySector, foundedYear, region,
                    description, revenue, ebitda, askingPrice, views, matchPercent, companyName,
                    exactLocation, status, ListingTransactionType.FULL_SALE);
        }
    }

    public record ListingEditView(Long id, String brokerEmail, String sectorCategory,
                                  java.util.List<String> activityDomains,
                                  String sector, String secondarySector,
                                  Integer foundedYear, Integer approximateEmployeeCount,
                                  boolean publicRevenueRangeAllowed,
                                  String description, String region, String customerSummary,
                                  String advantages, String saleReason, BigDecimal revenue, BigDecimal ebitda,
                                  BigDecimal askingPrice, BigDecimal profitMargin, String saleConditions,
                                  String supportPeriod, String companyName, String exactLocation,
                                  ListingTransactionType transactionType,
                                  BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalDebts,
                                  BigDecimal fixedAssets, String financialOutlook,
                                  String countryCode, String currencyCode) {
        public ListingEditView(Long id, String brokerEmail, String sectorCategory,
                               java.util.List<String> activityDomains,
                               String sector, String secondarySector,
                               Integer foundedYear, Integer approximateEmployeeCount,
                               boolean publicRevenueRangeAllowed,
                               String description, String region, String customerSummary,
                               String advantages, String saleReason, BigDecimal revenue, BigDecimal ebitda,
                               BigDecimal askingPrice, BigDecimal profitMargin, String saleConditions,
                               String supportPeriod, String companyName, String exactLocation,
                               ListingTransactionType transactionType) {
            this(id, brokerEmail, sectorCategory, activityDomains, sector, secondarySector, foundedYear,
                    approximateEmployeeCount, publicRevenueRangeAllowed, description, region,
                    customerSummary, advantages, saleReason, revenue, ebitda, askingPrice, profitMargin,
                    saleConditions, supportPeriod, companyName, exactLocation, transactionType,
                    null, null, null, null, null, null, "EUR");
        }

        public ListingEditView(Long id, String brokerEmail, String sectorCategory,
                               java.util.List<String> activityDomains, String sector, String secondarySector,
                               Integer foundedYear, Integer approximateEmployeeCount,
                               boolean publicRevenueRangeAllowed, String description, String region,
                               String customerSummary, String advantages, String saleReason,
                               BigDecimal revenue, BigDecimal ebitda, BigDecimal askingPrice,
                               BigDecimal profitMargin, String saleConditions, String supportPeriod,
                               String companyName, String exactLocation) {
            this(id, brokerEmail, sectorCategory, activityDomains, sector, secondarySector, foundedYear,
                    approximateEmployeeCount, publicRevenueRangeAllowed, description, region,
                    customerSummary, advantages, saleReason, revenue, ebitda, askingPrice, profitMargin,
                    saleConditions, supportPeriod, companyName, exactLocation, ListingTransactionType.FULL_SALE);
        }
    }

    public record ListingSearchFilters(String sector, String domain, String region,
                                       BigDecimal minimumPrice, BigDecimal maximumPrice,
                                       BigDecimal minimumRevenue, BigDecimal maximumRevenue,
                                       BigDecimal minimumProfitMargin, Integer foundedAfter,
                                       Integer foundedBefore, Integer minimumEmployees,
                                       Integer maximumEmployees, ListingTransactionType transactionType,
                                       String countryCode, String currencyCode) {
        public ListingSearchFilters(String sector, String domain, String region,
                                    BigDecimal minimumPrice, BigDecimal maximumPrice,
                                    BigDecimal minimumRevenue, BigDecimal maximumRevenue,
                                    BigDecimal minimumProfitMargin, Integer foundedAfter,
                                    Integer foundedBefore, Integer minimumEmployees,
                                    Integer maximumEmployees, ListingTransactionType transactionType) {
            this(sector, domain, region, minimumPrice, maximumPrice, minimumRevenue, maximumRevenue,
                    minimumProfitMargin, foundedAfter, foundedBefore, minimumEmployees, maximumEmployees,
                    transactionType, null, "EUR");
        }
    }

    public record NotificationView(Long id, String title, String body, Instant createdAt, boolean read) {
    }

    public record AppointmentView(Long id, Long requestId, Long proposedBy, Instant appointmentAt,
                                  String status, Instant createdAt) {
    }

    public record PublicListingView(Long id, String sectorCategory, String activityDomains,
                                    String sector, String region, String experienceRange,
                                    String employeeRange, String revenueRange,
                                    String countryCode, String currencyCode) {
        public PublicListingView(Long id, String sectorCategory, String activityDomains,
                                 String sector, String region, String experienceRange,
                                 String employeeRange, String revenueRange) {
            this(id, sectorCategory, activityDomains, sector, region, experienceRange,
                    employeeRange, revenueRange, null, "EUR");
        }
    }

    public record ListingSaveResult(Long id, boolean brokerAssigned) {
    }

    public record ListingSaleProcessView(Long listingId, String sector, String stage,
                                         String stageLabel, Instant updatedAt,
                                         List<SaleProcessHistoryView> history, boolean canAdvance) {
    }

    public record SaleProcessHistoryView(String stageLabel, Instant changedAt) {
    }

    public record ArticleView(Long id, String title, String category, String summary, String content,
                              String status, Instant createdAt, Instant updatedAt, Instant publishedAt) {
    }

    public record PartnerExpertView(Long id, String name, String category, String categoryLabel,
                                    String country, String location, String summary, String contactEmail,
                                    String phone, String website, boolean active) {
    }

    public record ContactMessageView(Long id, String senderName, String senderEmail, String senderPhone,
                                     String type, String subject, String messageText, String status,
                                     String adminNote, Instant createdAt, Instant updatedAt) {
    }

    public record DealView(Long id, Long listingId, String sector, String buyerName, String sellerName,
                           String status, boolean ndaSigned, String companyName, String exactLocation,
                           boolean sensitiveDetailsAvailable, boolean buyerSigned, boolean sellerSigned,
                           String ndaVersion, boolean encryptedIdentityUnavailable,
                           BigDecimal revenue, BigDecimal ebitda, BigDecimal askingPrice,
                           String currencyCode) {
    }

    public record MessageView(String sender, String body, Instant sentAt) {
    }

    public record DocumentView(Long id, Long listingId, Long requestId, String name,
                               String contentType, long sizeBytes, Instant uploadedAt,
                               boolean encryptedNameUnavailable, String verificationType,
                               boolean sellerAuthorized, boolean alpValidated,
                               Integer accessDurationDays, Instant accessExpiresAt, boolean accessActive) {
        public DocumentView(Long id, Long listingId, Long requestId, String name,
                            String contentType, long sizeBytes, Instant uploadedAt,
                            boolean encryptedNameUnavailable) {
            this(id, listingId, requestId, name, contentType, sizeBytes, uploadedAt,
                    encryptedNameUnavailable, null, false, false, null, null, false);
        }

        public DocumentView(Long id, Long listingId, Long requestId, String name,
                            String contentType, long sizeBytes, Instant uploadedAt,
                            boolean encryptedNameUnavailable, String verificationType) {
            this(id, listingId, requestId, name, contentType, sizeBytes, uploadedAt,
                    encryptedNameUnavailable, verificationType, false, false, null, null, false);
        }

        public DocumentView(Long id, Long listingId, Long requestId, String name,
                            String contentType, long sizeBytes, Instant uploadedAt,
                            boolean encryptedNameUnavailable, String verificationType,
                            boolean sellerAuthorized, boolean alpValidated) {
            this(id, listingId, requestId, name, contentType, sizeBytes, uploadedAt,
                    encryptedNameUnavailable, verificationType, sellerAuthorized, alpValidated,
                    null, null, false);
        }
    }

    public record DocumentAccessLogView(String documentName, String accessedByEmail, Instant accessedAt) {
    }

    public record DocumentDownload(String name, String contentType, byte[] bytes) {
    }
}
