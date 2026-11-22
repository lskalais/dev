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
    /** UEMOA : le franc CFA (XOF) est la seule monnaie légale de ces huit pays. */
    private static final Set<String> XOF_COUNTRIES = Set.of("BJ", "BF", "CI", "GW", "ML", "NE", "SN", "TG");

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

    /**
     * Devise imposée par le pays du dossier lorsque celui-ci utilise une monnaie unique légale.
     * Cas couvert : les huit pays de la zone UEMOA (Côte d'Ivoire, Sénégal, Bénin…) libellent en
     * franc CFA (XOF), quelle que soit la devise choisie auparavant.
     *
     * @return le code ISO 4217 imposé, ou {@code null} si la devise reste libre.
     */
    public static String mandatedCurrencyFor(String countryCode) {
        return countryCode != null && XOF_COUNTRIES.contains(countryCode) ? "XOF" : null;
    }

    /** Libellé de la devise tel qu'attendu par les professionnels locaux (« FCFA » plutôt que « XOF »). */
    public static String currencyLabel(String code, Locale displayLocale) {
        if ("XOF".equals(code)) {
            return "FCFA (XOF)";
        }
        return isCurrencyCode(code) ? Currency.getInstance(code).getDisplayName(displayLocale) : code;
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
        // code_052 : les dossiers de la zone FCFA sont libellés « FCFA » (avec le code ISO pour lever
        // toute ambiguïté), le franc CFA n'ayant pas de sous-unité affichée.
        return formatter.format(amount) + " " + displayCode(code);
    }

    /** Code de devise tel qu'affiché à côté d'un montant. */
    public static String displayCode(String code) {
        return "XOF".equals(code) ? "FCFA (XOF)" : code;
    }
}
