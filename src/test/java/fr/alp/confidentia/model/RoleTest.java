package fr.alp.confidentia.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests unitaires de l'énumération {@link Role}.
 * Les rôles sont persistés en base sous forme de chaîne : la stabilité de
 * {@code name()} est donc un contrat.
 */
@DisplayName("Role - énumération des profils")
class RoleTest {

    @Test
    @DisplayName("values : contient exactement les quatre profils, dans l'ordre attendu")
    void values_enumeration_complete() {
        assertArrayEquals(new Role[] { Role.SELLER, Role.BUYER, Role.BROKER, Role.SUPER }, Role.values(),
                "La liste des rôles est un contrat de persistance (EnumType.STRING)");
    }

    @Test
    @DisplayName("valueOf : la conversion depuis la chaîne exacte fonctionne")
    void valueOf_chaineValide_retourneLeRole() {
        assertSame(Role.BROKER, Role.valueOf("BROKER"));
    }

    @Test
    @DisplayName("valueOf : la casse est sensible (les rôles sont stockés en majuscules)")
    void valueOf_casseDifferente_leveIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Role.valueOf("buyer"));
    }

    @Test
    @DisplayName("valueOf : valeur inconnue -> IllegalArgumentException")
    void valueOf_valeurInconnue_leveIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Role.valueOf("ADMIN"));
    }

    @Test
    @DisplayName("valueOf(null) : NullPointerException")
    void valueOf_null_leveNullPointerException() {
        assertThrows(NullPointerException.class, () -> Role.valueOf(null));
    }

    @Test
    @DisplayName("name : les libellés correspondent aux valeurs persistées")
    void name_libellesAttendus() {
        assertEquals("SELLER", Role.SELLER.name());
        assertEquals("BUYER", Role.BUYER.name());
        assertEquals("BROKER", Role.BROKER.name());
        assertEquals("SUPER", Role.SUPER.name());
    }
}
