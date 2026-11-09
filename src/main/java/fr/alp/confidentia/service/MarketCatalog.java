package fr.alp.confidentia.service;

import jakarta.faces.model.SelectItem;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MarketCatalog {
    private static final Set<String> COUNTRY_CODES =
            Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);
    private static final Set<Currency> CURRENCIES = Currency.getAvailableCurrencies();

    private MarketCatalog() {
    }

    public static boolean isCountryCode(String code) {
        return code != null && COUNTRY_CODES.contains(code);
    }

    public static boolean isCurrencyCode(String code) {
        if (code == null) {
            return false;
        }
        try {
            return CURRENCIES.contains(Currency.getInstance(code));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static List<SelectItem> countryOptions(Locale displayLocale) {
        return COUNTRY_CODES.stream()
                .sorted()
                .map(code -> new SelectItem(code,
                        new Locale.Builder().setRegion(code).build().getDisplayCountry(displayLocale)
                                + " (" + code + ")"))
                .toList();
    }

    public static List<SelectItem> currencyOptions(Locale displayLocale) {
        return CURRENCIES.stream()
                .filter(currency -> currency.getDefaultFractionDigits() >= 0)
                .sorted(java.util.Comparator.comparing(Currency::getCurrencyCode))
                .map(currency -> new SelectItem(currency.getCurrencyCode(),
                        currency.getCurrencyCode() + " — " + currency.getDisplayName(displayLocale)))
                .toList();
    }

    public static String countryName(String code, Locale displayLocale) {
        return isCountryCode(code)
                ? new Locale.Builder().setRegion(code).build().getDisplayCountry(displayLocale)
                : "Zone non précisée";
    }

    public static String formatCurrency(java.math.BigDecimal amount, String code, Locale displayLocale) {
        if (amount == null) {
            return "";
        }
        if (!isCurrencyCode(code)) {
            throw new IllegalArgumentException("Code de devise ISO 4217 invalide.");
        }
        Currency currency = Currency.getInstance(code);
        java.text.NumberFormat formatter = java.text.NumberFormat.getNumberInstance(displayLocale);
        formatter.setMinimumFractionDigits(currency.getDefaultFractionDigits());
        formatter.setMaximumFractionDigits(currency.getDefaultFractionDigits());
        return formatter.format(amount) + " " + code;
    }
}
