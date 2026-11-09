package fr.alp.confidentia.service;

import fr.alp.confidentia.service.ViewModels.ListingSearchFilters;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketCatalogTest {
    @Test
    void acceptsOnlyIsoCountryAndCurrencyCodes() {
        assertTrue(MarketCatalog.isCountryCode("CI"));
        assertFalse(MarketCatalog.isCountryCode("CIV"));
        assertTrue(MarketCatalog.isCurrencyCode("XOF"));
        assertFalse(MarketCatalog.isCurrencyCode("FCFA"));
    }

    @Test
    void formatsMoneyWithAnUnambiguousCurrencyCode() {
        String formatted = MarketCatalog.formatCurrency(
                new BigDecimal("1250.5"), "XOF", Locale.FRENCH);

        assertTrue(formatted.endsWith(" XOF"));
    }

    @Test
    void rejectsFinancialSearchWithoutCurrency() {
        MarketplaceService service = new MarketplaceService();
        ListingSearchFilters filters = new ListingSearchFilters(
                null, null, null, BigDecimal.ONE, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.validateListingSearchFilters(filters));
    }

    @Test
    void rejectsUnknownCurrencyInFinancialSearch() {
        MarketplaceService service = new MarketplaceService();
        ListingSearchFilters filters = new ListingSearchFilters(
                null, null, null, BigDecimal.ONE, null, null, null,
                null, null, null, null, null, null, null, "FCFA");

        assertThrows(IllegalArgumentException.class,
                () -> service.validateListingSearchFilters(filters));
    }
}
