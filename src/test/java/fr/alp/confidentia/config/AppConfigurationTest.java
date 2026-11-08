package fr.alp.confidentia.config;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link AppConfiguration}.
 * <p>
 * Ordre de résolution attendu : ServletContext -> propriétés système ->
 * variables d'environnement -> fallback.
 * L'état statique (ServletContext) est réinitialisé entre chaque test via
 * {@code initialize(null)}.
 */
@DisplayName("AppConfiguration - résolution des paramètres")
class AppConfigurationTest {

    private static final String PARAM = "ALP_TEST_PARAM";

    @BeforeEach
    void setUp() {
        AppConfiguration.initialize(null);
        System.clearProperty(PARAM);
    }

    @AfterEach
    void tearDown() {
        AppConfiguration.initialize(null);
        System.clearProperty(PARAM);
    }

    @Test
    @DisplayName("get : la valeur du ServletContext est prioritaire")
    void get_parametreDansServletContext_retourneCetteValeur() {
        ServletContext context = mock(ServletContext.class);
        when(context.getInitParameter(PARAM)).thenReturn("contexte");
        System.setProperty(PARAM, "systeme");
        AppConfiguration.initialize(context);

        assertEquals("contexte", AppConfiguration.get(PARAM, "defaut"));
    }

    @Test
    @DisplayName("get : à défaut de contexte, la propriété système est utilisée")
    void get_sansContexte_proprieteSystemeUtilisee() {
        System.setProperty(PARAM, "systeme");

        assertEquals("systeme", AppConfiguration.get(PARAM, "defaut"));
    }

    @Test
    @DisplayName("get : si le contexte renvoie une valeur vide, on retombe sur la propriété système")
    void get_contexteVide_retombeSurProprieteSysteme() {
        ServletContext context = mock(ServletContext.class);
        when(context.getInitParameter(PARAM)).thenReturn("   ");
        AppConfiguration.initialize(context);
        System.setProperty(PARAM, "systeme");

        assertEquals("systeme", AppConfiguration.get(PARAM, "defaut"));
    }

    @Test
    @DisplayName("get : la variable d'environnement est utilisée quand rien d'autre n'est défini")
    void get_variableEnvironnement_utilisee() {
        // PATH est présente dans tous les environnements Windows/Unix et jamais
        // blanche.
        String path = System.getenv("PATH");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isBlank(),
                "PATH doit exister pour ce test d'intégration d'environnement");

        String value = AppConfiguration.get("PATH", "defaut");

        assertEquals(path, value, "La variable d'environnement doit être retournée telle quelle");
    }

    @Test
    @DisplayName("get : le fallback est retourné si le paramètre est inconnu")
    void get_parametreInconnu_retourneLeFallback() {
        assertEquals("defaut", AppConfiguration.get(PARAM, "defaut"));
    }

    @Test
    @DisplayName("get : le fallback null est retourné si le paramètre est inconnu")
    void get_parametreInconnuEtFallbackNull_retourneNull() {
        assertNull(AppConfiguration.get(PARAM, null));
    }

    @Test
    @DisplayName("get : une valeur effective null fait retomber sur le fallback")
    void get_valeurVide_retourneFallback() {
        System.setProperty(PARAM, "");

        assertEquals("defaut", AppConfiguration.get(PARAM, "defaut"));
    }

    @Test
    @DisplayName("get : une valeur contenant des espaces internes est conservée telle quelle")
    void get_valeurAvecEspacesInternes_conservee() {
        System.setProperty(PARAM, "deux mots");

        assertEquals("deux mots", AppConfiguration.get(PARAM, "defaut"));
    }
}
