package fr.alp.confidentia.service;

import java.math.BigDecimal;
import java.time.Instant;

public final class ViewModels {
    private ViewModels() {
    }

    public record UserView(Long id, String email, String name, String organization, String role,
                           boolean passwordChangeRequired)
            implements java.io.Serializable {
    }

    public record AdminUserView(Long id, String email, String name, String organization, String role,
                                Instant createdAt, boolean passwordChangeRequired)
            implements java.io.Serializable {
    }

    public record ListingView(Long id, String sector, String region, String description,
                              BigDecimal revenue, BigDecimal ebitda, BigDecimal askingPrice,
                              long views, int matchPercent, String companyName, String exactLocation,
                              String status) {
    }

    public record ListingEditView(Long id, String brokerEmail, String sector, String secondarySector,
                                  Integer foundedYear, String description, String region, String customerSummary,
                                  String advantages, String saleReason, BigDecimal revenue, BigDecimal ebitda,
                                  BigDecimal askingPrice, BigDecimal profitMargin, String saleConditions,
                                  String supportPeriod, String companyName, String exactLocation) {
    }

    public record ListingSaveResult(Long id, boolean brokerAssigned) {
    }

    public record DealView(Long id, Long listingId, String sector, String buyerName, String sellerName,
                           String status, boolean ndaSigned, String companyName, String exactLocation,
                           boolean buyerSigned, boolean sellerSigned, String ndaVersion,
                           boolean encryptedIdentityUnavailable) {
    }

    public record MessageView(String sender, String body, Instant sentAt) {
    }

    public record DocumentView(Long id, Long listingId, Long requestId, String name,
                               String contentType, long sizeBytes, Instant uploadedAt,
                               boolean encryptedNameUnavailable) {
    }

    public record DocumentDownload(String name, String contentType, byte[] bytes) {
    }
}
