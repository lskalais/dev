package fr.alp.confidentia.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link Jpa} : gestion des transactions et cycle de vie de
 * la fabrique.
 * <p>
 * La fabrique statique est injectée par réflexion pour éviter toute connexion
 * MariaDB.
 */
@DisplayName("Jpa - transactions et cycle de vie")
class JpaTest {

    private EntityManagerFactory factory;
    private EntityManager entityManager;
    private EntityTransaction transaction;

    @BeforeEach
    void setUp() throws Exception {
        factory = mock(EntityManagerFactory.class);
        entityManager = mock(EntityManager.class);
        transaction = mock(EntityTransaction.class);
        when(factory.isOpen()).thenReturn(true);
        when(factory.createEntityManager()).thenReturn(entityManager);
        when(entityManager.getTransaction()).thenReturn(transaction);

        var field = Jpa.class.getDeclaredField("factory");
        field.setAccessible(true);
        field.set(null, factory);
    }

    @AfterEach
    void tearDown() throws Exception {
        var field = Jpa.class.getDeclaredField("factory");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    @DisplayName("transaction : le travail est exécuté et la transaction est validée")
    void transaction_travailReussi_commitEtRetourneLeResultat() {
        String result = Jpa.transaction(em -> "resultat");

        assertEquals("resultat", result);
        verify(transaction).begin();
        verify(transaction).commit();
        verify(transaction, never()).rollback();
        verify(entityManager).close();
    }

    @Test
    @DisplayName("transaction : l'EntityManager transmis est celui de la fabrique")
    void transaction_entityManagerTransmis() {
        AtomicReference<EntityManager> received = new AtomicReference<>();

        Jpa.transaction(em -> {
            received.set(em);
            return null;
        });

        assertSame(entityManager, received.get());
    }

    @Test
    @DisplayName("transaction : une RuntimeException provoque un rollback puis est propagée")
    void transaction_exceptionMetier_rollbackEtPropagation() {
        when(transaction.isActive()).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("échec métier");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Jpa.transaction(em -> {
                    throw failure;
                }));

        assertSame(failure, thrown, "L'exception d'origine doit être propagée telle quelle");
        verify(transaction).rollback();
        verify(transaction, never()).commit();
        verify(entityManager).close();
    }

    @Test
    @DisplayName("transaction : aucun rollback si la transaction n'est plus active")
    void transaction_exceptionEtTransactionInactive_pasDeRollback() {
        when(transaction.isActive()).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> Jpa.transaction(em -> {
                    throw new IllegalArgumentException("boom");
                }));

        verify(transaction, never()).rollback();
        verify(entityManager).close();
    }

    @Test
    @DisplayName("transaction : l'EntityManager est fermé même en cas d'erreur de commit")
    void transaction_echecDuCommit_entityManagerFerme() {
        doThrow(new IllegalStateException("commit impossible")).when(transaction).commit();
        when(transaction.isActive()).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> Jpa.transaction(em -> "ok"));
        verify(entityManager).close();
    }

    @Test
    @DisplayName("transaction : sans fabrique initialisée, IllegalStateException")
    void transaction_sansInitialisation_leveIllegalStateException() throws Exception {
        var field = Jpa.class.getDeclaredField("factory");
        field.setAccessible(true);
        field.set(null, null);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> Jpa.transaction(em -> "ok"));

        assertTrue(exception.getMessage().contains("JPA"));
    }

    @Test
    @DisplayName("transaction : une fabrique fermée est refusée")
    void transaction_fabriqueFermee_leveIllegalStateException() {
        when(factory.isOpen()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> Jpa.transaction(em -> "ok"));
        verify(factory, never()).createEntityManager();
    }

    @Test
    @DisplayName("transaction : plusieurs appels créent chacun un EntityManager")
    void transaction_appelsMultiples_nouvelEntityManagerChaqueFois() {
        Jpa.transaction(em -> null);
        Jpa.transaction(em -> null);

        verify(factory, times(2)).createEntityManager();
        verify(entityManager, times(2)).close();
    }

    @Test
    @DisplayName("close : ferme la fabrique lorsqu'elle est ouverte")
    void close_fabriqueOuverte_fermeLaFabrique() {
        Jpa.close();

        verify(factory).close();
    }

    @Test
    @DisplayName("close : ne fait rien si la fabrique est déjà fermée")
    void close_fabriqueDejaFermee_aucuneAction() {
        when(factory.isOpen()).thenReturn(false);

        Jpa.close();

        verify(factory, never()).close();
    }

    @Test
    @DisplayName("close : sans fabrique, aucun NullPointerException")
    void close_sansFabrique_neLeveRien() throws Exception {
        var field = Jpa.class.getDeclaredField("factory");
        field.setAccessible(true);
        field.set(null, null);

        Jpa.close(); // aucun effet attendu
    }

    @Test
    @DisplayName("initialize : ne réinitialise pas une fabrique déjà existante")
    void initialize_fabriqueExistante_conserveLaFabrique() {
        // Guard clause : initialize() retourne immédiatement si factory != null, sans
        // toucher au réseau.
        Jpa.initialize();

        assertSame(factory, currentFactory(), "La fabrique existante doit être conservée");
        verify(factory, never()).createEntityManager();
    }



    // ---------------------------------------------------------------------------------------------

    private static EntityManagerFactory currentFactory() {
        try {
            var field = Jpa.class.getDeclaredField("factory");
            field.setAccessible(true);
            return (EntityManagerFactory) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
