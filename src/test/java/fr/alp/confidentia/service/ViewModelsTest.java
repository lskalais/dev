package fr.alp.confidentia.service;

import fr.alp.confidentia.service.ViewModels.DocumentView;
import fr.alp.confidentia.service.ViewModels.ListingView;
import fr.alp.confidentia.service.ViewModels.MessageView;
import fr.alp.confidentia.service.ViewModels.UserView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires des DTO (records) de {@link ViewModels}.
 * Ces objets transitent dans la session HTTP : leur sérialisation doit
 * fonctionner pour les
 * records qui l'implémentent, et leur égalité de valeur est utilisée dans les
 * comparaisons de vues.
 */
@DisplayName("ViewModels - DTO")
class ViewModelsTest {

    @Test
    @DisplayName("UserView : deux instances de mêmes valeurs sont égales")
    void userView_memesValeurs_egales() {
        UserView first = new UserView(1L, "a@alp.fr", "Alice", "ALP", "BUYER", false);
        UserView second = new UserView(1L, "a@alp.fr", "Alice", "ALP", "BUYER", false);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    @DisplayName("UserView : une différence sur passwordChangeRequired rend les instances inégales")
    void userView_passwordChangeDifferents_inegales() {
        UserView active = new UserView(1L, "a@alp.fr", "Alice", "ALP", "BUYER", true);
        UserView inactive = new UserView(1L, "a@alp.fr", "Alice", "ALP", "BUYER", false);

        assertNotEquals(active, inactive, "L'indicateur de changement obligatoire fait partie de l'identité");
    }

    @Test
    @DisplayName("UserView : sérialisable (stocké en session HTTP)")
    void userView_serialisable() throws Exception {
        UserView original = new UserView(7L, "b@alp.fr", "Bob", null, "SELLER", true);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        Object restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = in.readObject();
        }

        assertInstanceOf(UserView.class, restored);
        assertEquals(original, restored);
    }

    @Test
    @DisplayName("ListingView : les champs de l'annonce sont transmis tels quels")
    void listingView_champsPresents() {
        ListingView view = new ListingView(3L, "Agro", "Cultures vivrières", 2010,
                "Abidjan", "Desc",
                new BigDecimal("1000.00"), new BigDecimal("150.00"), new BigDecimal("900.00"),
                42L, 66, "Société X", "Rue Y", "PUBLISHED");

        assertEquals("Cultures vivrières", view.secondarySector());
        assertEquals(2010, view.foundedYear());
        assertEquals(42L, view.views());
        assertEquals(66, view.matchPercent());
        assertEquals("PUBLISHED", view.status());
        assertEquals(new BigDecimal("900.00"), view.askingPrice());
    }

    @Test
    @DisplayName("MessageView : Instant est conservé")
    void messageView_instantConserve() {
        Instant now = Instant.parse("2026-10-31T10:15:30Z");

        MessageView view = new MessageView("Alice", "Bonjour", now);

        assertEquals(now, view.sentAt());
        assertEquals("Alice", view.sender());
    }

    @Test
    @DisplayName("DocumentView : champ texte oui/non (encryptedNameUnavailable) positionné correctement")
    void documentView_indicateurDisponibilite() {
        DocumentView view = new DocumentView(1L, 2L, null, "Doc introuvable", "application/pdf",
                1024L, Instant.now(), true);

        assertEquals(true, view.encryptedNameUnavailable());
        assertEquals(null, view.requestId());
    }

    @Test
    @DisplayName("DocumentDownload : le tableau d'octets est partagé (pas de copie défensive)")
    void documentDownload_tableauPartage() {
        byte[] content = { 1, 2, 3 };

        ViewModels.DocumentDownload download = new ViewModels.DocumentDownload("f.pdf", "application/pdf", content);

        assertArrayEquals(content, download.bytes());
        assertEquals("f.pdf", download.name());
    }

    @Test
    @DisplayName("ViewModels : la classe utilitaire n'a pas de constructeur visible")
    void viewModels_sansConstructeurPublic_nonInstanciable() throws NoSuchMethodException {
        assertEquals(0, ViewModels.class.getConstructors().length,
                "Aucune instance de ViewModels ne doit être créable depuis du code applicatif");
        // Le constructeur existe mais doit rester privé : il n'est donc pas visible sans réflexion.
        var privateConstructor = ViewModels.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(privateConstructor.getModifiers()),
                "Le constructeur doit rester privé (anti-instanciation)");
    }
}
