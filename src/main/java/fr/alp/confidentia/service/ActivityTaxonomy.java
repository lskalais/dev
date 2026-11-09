package fr.alp.confidentia.service;

import java.util.List;
import java.util.Map;

public final class ActivityTaxonomy {
    public record Option(String value, String label) {
    }

    private static final List<Option> SECTORS = List.of(
            new Option("PRIMARY", "Primaire"),
            new Option("SECONDARY", "Secondaire"),
            new Option("TERTIARY", "Tertiaire")
    );

    private static final Map<String, List<Option>> DOMAINS = Map.of(
            "PRIMARY", List.of(
                    new Option("agriculture", "Agriculture et cultures"),
                    new Option("livestock", "Élevage"),
                    new Option("forestry", "Sylviculture et exploitation forestière"),
                    new Option("fishing", "Pêche et aquaculture"),
                    new Option("mining", "Mines et carrières")
            ),
            "SECONDARY", List.of(
                    new Option("agro_processing", "Agroalimentaire et transformation"),
                    new Option("manufacturing", "Industrie manufacturière"),
                    new Option("construction", "Bâtiment et travaux publics"),
                    new Option("energy", "Énergie et utilités"),
                    new Option("water", "Eau et assainissement")
            ),
            "TERTIARY", List.of(
                    new Option("commerce", "Commerce et distribution"),
                    new Option("transport", "Transport et logistique"),
                    new Option("finance", "Banque, assurance et finance"),
                    new Option("telecommunications", "Télécommunications et numérique"),
                    new Option("tourism", "Tourisme, hôtellerie et restauration"),
                    new Option("health", "Santé et services sociaux"),
                    new Option("education", "Éducation et formation"),
                    new Option("professional_services", "Services professionnels et conseil"),
                    new Option("real_estate", "Immobilier"),
                    new Option("other_services", "Autres services")
            )
    );

    private ActivityTaxonomy() {
    }

    public static List<Option> sectors() {
        return SECTORS;
    }

    public static List<Option> domainsFor(String sectorCategory) {
        return sectorCategory == null ? List.of() : DOMAINS.getOrDefault(sectorCategory, List.of());
    }

    public static boolean isValid(String sectorCategory, List<String> domainCodes) {
        List<Option> availableDomains = domainsFor(sectorCategory);
        return !availableDomains.isEmpty()
                && domainCodes != null
                && !domainCodes.isEmpty()
                && domainCodes.stream().distinct().count() == domainCodes.size()
                && domainCodes.stream().allMatch(code ->
                availableDomains.stream().anyMatch(option -> option.value().equals(code)));
    }

    public static String sectorLabel(String sectorCategory) {
        return SECTORS.stream()
                .filter(option -> option.value().equals(sectorCategory))
                .map(Option::label)
                .findFirst()
                .orElse("");
    }

    public static List<String> decodeDomains(String storedDomains) {
        if (storedDomains == null || storedDomains.isBlank()) {
            return List.of();
        }
        return List.of(storedDomains.split(",")).stream()
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList();
    }

    public static String domainLabels(String sectorCategory, String storedDomains) {
        List<String> selectedCodes = decodeDomains(storedDomains);
        return domainsFor(sectorCategory).stream()
                .filter(option -> selectedCodes.contains(option.value()))
                .map(Option::label)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
