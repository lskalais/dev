package fr.alp.confidentia.service;

import fr.alp.confidentia.service.ViewModels.ListingSearchFilters;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        // code_052 : le franc CFA est libellé « FCFA » mais le code ISO reste affiché.
        assertTrue(formatted.endsWith(" FCFA (XOF)"), formatted);
        assertTrue(formatted.startsWith("1"), formatted);
    }

    @Test
    void mandatesXofForUemoaCountriesOnly() {
        assertEquals("XOF", MarketCatalog.mandatedCurrencyFor("CI"));
        assertEquals("XOF", MarketCatalog.mandatedCurrencyFor("SN"));
        assertNull(MarketCatalog.mandatedCurrencyFor("XAF"));
        assertNull(MarketCatalog.mandatedCurrencyFor("FR"));
        assertNull(MarketCatalog.mandatedCurrencyFor(null));
    }

    @Test
    void labelsXofAsFcfa() {
        // La JVM peut nommer la devise « Franc CFA » (XOF) ou « Franc CFA (BCEAO) » (XOF) : seul le
        // code ISO fait foi, le libellé local doit rester « FCFA (XOF) ».
        assertTrue(MarketCatalog.isCurrencyCode("XOF"));
        assertEquals("FCFA (XOF)", MarketCatalog.displayCode("XOF"));
        assertEquals("EUR", MarketCatalog.displayCode("EUR"));
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
