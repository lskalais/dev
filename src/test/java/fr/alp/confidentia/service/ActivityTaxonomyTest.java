package fr.alp.confidentia.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ActivityTaxonomy - secteurs et domaines")
class ActivityTaxonomyTest {
    @Test
    @DisplayName("propose les trois catégories de secteurs")
    void sectors_containsThreeCategories() {
        assertEquals(List.of("PRIMARY", "SECONDARY", "TERTIARY"),
                ActivityTaxonomy.sectors().stream().map(ActivityTaxonomy.Option::value).toList());
    }

    @Test
    @DisplayName("filtre les domaines selon la catégorie choisie")
    void domainsFor_returnsOnlyMatchingDomains() {
        assertTrue(ActivityTaxonomy.domainsFor("TERTIARY").stream()
                .anyMatch(option -> option.value().equals("commerce")));
        assertFalse(ActivityTaxonomy.domainsFor("PRIMARY").stream()
                .anyMatch(option -> option.value().equals("commerce")));
    }

    @Test
    @DisplayName("accepte plusieurs domaines de la catégorie")
    void isValid_acceptsMultipleDomains() {
        assertTrue(ActivityTaxonomy.isValid("TERTIARY", List.of("commerce", "finance")));
    }

    @Test
    @DisplayName("refuse domaines hors catégorie, doublons et sélection vide")
    void isValid_rejectsInvalidSelections() {
        assertFalse(ActivityTaxonomy.isValid("PRIMARY", List.of("finance")));
        assertFalse(ActivityTaxonomy.isValid("TERTIARY", List.of("commerce", "commerce")));
        assertFalse(ActivityTaxonomy.isValid("TERTIARY", List.of()));
        assertFalse(ActivityTaxonomy.isValid("UNKNOWN", List.of("commerce")));
    }
}
