package fr.alp.confidentia.testsupport;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.EntityTransaction;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Utilitaire de test qui remplace
 * {@link fr.alp.confidentia.persistence.Jpa#transaction(java.util.function.Function)}
 * par une exécution directe du travail sur un {@link EntityManager} mocké.
 * <p>
 * Cela permet de tester la logique métier de {@code MarketplaceService}
 * (validations, règles d'accès,
 * calculs) sans base de données ni conteneur CDI.
 */
public final class MockJpa implements AutoCloseable {

    /** EntityManager mocké transmis à chaque appel de transaction. */
    public final EntityManager em;
    private final MockedStatic<fr.alp.confidentia.persistence.Jpa> jpaMock;

    /**
     * Installe le double de {@code Jpa.transaction} : le lambda métier est exécuté
     * réellement, sur un EntityManager mocké (sans vrai début/commit de transaction).
     */
    public MockJpa() {
        em = mock(EntityManager.class);
        // persist/find sont génériques : préparation via doAnswer (compatible Mockito).
        // Les identifiants sont générés comme le ferait la base.
        AtomicLong nextId = new AtomicLong(1000);
        doAnswer(invocation -> {
            setField(invocation.getArgument(0), "id", nextId.getAndIncrement());
            return null;
        }).when(em).persist(ArgumentMatchers.any());
        doReturn(null).when(em).find(ArgumentMatchers.any(), ArgumentMatchers.any());
        // Repli (priorité la plus basse) : une requête non déclarée renvoie un résultat vide.
        // Une requête déclarée par le test via query(...) écrase ce repli (doReturn est prioritaire).
        lenient().when(em.createQuery(ArgumentMatchers.anyString(), ArgumentMatchers.<Class<?>>any()))
                .thenAnswer(invocation -> emptyQuery());
        // Surcharge String seule : il faut préciser la méthode visée, sinon Mockito cible
        // createQuery(String, Class) et le repli ne s'applique jamais aux requêtes scalaires.
        lenient().when(em.createQuery(ArgumentMatchers.anyString(), ArgumentMatchers.<Class<?>>isNull()))
                .thenAnswer(invocation -> emptyQuery());

        EntityTransaction transaction = mock(EntityTransaction.class);
        lenient().when(transaction.isActive()).thenReturn(true);
        doReturn(transaction).when(em).getTransaction();

        // Reproduit la sémantique réelle de Jpa.transaction : le travail s'exécute DANS un try,
        // un commit est tenté après, et une RuntimeException métier est propagée après rollback.
        // Sans ce try/catch, le « travail » s'exécuterait en dehors de tout try et Mockito
        // lèverait lui-même les exceptions (mockStatic les capture hors du code d'essai).
        jpaMock = Mockito.mockStatic(fr.alp.confidentia.persistence.Jpa.class);
        // Le type d'argument est fixé explicitement : sans type, l'inférence de Mockito peut viser
        // une autre surcharge et le travail métier ne serait jamais exécuté.
        jpaMock.when(() -> fr.alp.confidentia.persistence.Jpa.transaction(
                        ArgumentMatchers.<java.util.function.Function<EntityManager, Object>>any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Function<EntityManager, Object> work = invocation.getArgument(0);
                    try {
                        transaction.begin();
                        Object result = work.apply(em);
                        transaction.commit();
                        return result;
                    } catch (RuntimeException exception) {
                        if (transaction.isActive()) {
                            transaction.rollback();
                        }
                        throw exception;
                    }
                });
    }

    @Override
    public void close() {
        jpaMock.close();
    }



    /**
     * Requête typée vide : chaînage neutralisé. {@code getResultList()} reflète le dernier
     * {@code setMaxResults} (0 si la requête pagine à zéro ligne), {@code getSingleResult()}
     * renvoie 0 (utile aux COUNT non déclarés) et {@code executeUpdate()} 0 lien.
     */
    @SuppressWarnings("unchecked")
    private static <T> jakarta.persistence.TypedQuery<T> emptyQuery() {
        jakarta.persistence.TypedQuery<T> query = mock(jakarta.persistence.TypedQuery.class);
        List<T> results = new ArrayList<>();
        lenient().when(query.setParameter(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(query);
        lenient().when(query.setMaxResults(ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    int max = invocation.getArgument(0);
                    lenient().when(query.getResultList()).thenReturn(max <= 0 ? new ArrayList<>() : results);
                    return query;
                });
        lenient().when(query.getResultList()).thenReturn(results);
        lenient().when(query.getSingleResult()).thenReturn((T) Long.valueOf(0L));
        lenient().when(query.executeUpdate()).thenReturn(0);
        return query;
    }

    /**
     * Enregistre une requête JPQL typée et fixe ce qu'elle retournera.
     *
     * @param em      l'EntityManager mocké
     * @param jpql    la requête exacte attendue
     * @param results résultat de {@code getResultList()} (et premier élément de {@code getSingleResult()})
     * @return la requête mockée, pour des vérifications complémentaires
     */
    @SuppressWarnings("unchecked")
    public static <T> jakarta.persistence.TypedQuery<T> query(EntityManager em, String jpql, List<T> results) {
        jakarta.persistence.TypedQuery<T> query = mock(jakarta.persistence.TypedQuery.class);
        lenient().when(query.setParameter(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(query);
        // setMaxResults réel : un max de 0 ne doit jamais laisser passer d'élément (COUNT/lecture vide).
        lenient().when(query.setMaxResults(ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    int max = invocation.getArgument(0);
                    lenient().when(query.getResultList()).thenReturn(max <= 0 ? new ArrayList<>() : results);
                    return query;
                });
        lenient().when(query.getResultList()).thenReturn(results);
        lenient().when(query.getSingleResult()).thenReturn(results.isEmpty() ? null : results.get(0));
        // Surcharge explicite (String, Class) : un stub « String seule » serait distinct dans Mockito.
        doReturn(query).when(em).createQuery(eq(jpql), ArgumentMatchers.<Class<T>>any());
        return query;
    }

    /** Enregistre une mise à jour JPQL (update/delete) et fixe le nombre de lignes modifiées. */
    public static void update(EntityManager em, String jpql, int rows) {
        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        lenient().when(query.setParameter(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(query);
        lenient().when(query.executeUpdate()).thenReturn(rows);
        doReturn(query).when(em).createQuery(eq(jpql));
    }

    /**
     * Enregistre une mise à jour JPQL non typée via la surcharge {@code createQuery(String)}.
     * Préparer un mock EntityManager par défaut ne suffit pas : le repli de createQuery(String)
     * cible une requête typée, d'où ce stub explicite.
     */
    public static void updateUntyped(EntityManager em, String jpql) {
        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        lenient().when(query.setParameter(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(query);
        lenient().when(query.executeUpdate()).thenReturn(1);
        doReturn(query).when(em).createQuery(eq(jpql));
    }

    /** Enregistre une requête native (SQL) et fixe ce qu'elle retournera. */
    public static jakarta.persistence.Query nativeQuery(EntityManager em, String sql, List<?> results) {
        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        lenient().when(query.setParameter(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(query);
        lenient().when(query.setMaxResults(ArgumentMatchers.anyInt())).thenReturn(query);
        lenient().when(query.getResultList()).thenReturn(results);
        lenient().when(query.executeUpdate()).thenReturn(1);
        doReturn(query).when(em).createNativeQuery(sql);
        return query;
    }

    // ---------------------------------------------------------------------------------------------
    // Fabriques d'entités
    // ---------------------------------------------------------------------------------------------

    /**
     * Crée une instance fraîche d'une entité JPA sans passer par un EntityManager.
     * Les entités du projet ont un constructeur par défaut implicite (public).
     */
    public static <T> T newEntity(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Impossible de créer " + type.getName(), exception);
        }
    }

    /**
     * Injecte une valeur dans un champ public (les entités exposent des champs
     * publics, dont l'id généré).
     * Utile pour fixer les identifiants retournés par un {@code persist()} réel.
     */
    public static <T> List<T> empty() {
        return new ArrayList<>();
    }

    /**
     * Fabrique une {@code CryptoService.UnreadableCiphertextException} (constructeur privé)
     * pour simuler un déchiffrement réalisé avec une ancienne clé.
     */
    public static RuntimeException unreadableCiphertext() {
        try {
            var inner = Class.forName("fr.alp.confidentia.security.CryptoService$UnreadableCiphertextException");
            var constructor = inner.getDeclaredConstructor(javax.crypto.AEADBadTagException.class);
            constructor.setAccessible(true);
            return (RuntimeException) constructor.newInstance(new javax.crypto.AEADBadTagException("clé"));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Instanciation impossible de UnreadableCiphertextException", exception);
        }
    }

    /** Affecte une valeur à un champ public (ids simulés, horodatages, etc.). */
    public static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getField(fieldName);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Champ introuvable : " + fieldName, exception);
        }
    }

    /**
     * Simule la génération d'identifiant : {@code em.persist(entity)} affecte l'id
     * fourni.
     */
    public static <T> void persistAssignsId(EntityManager em, Class<T> type, Long generatedId) {
        Mockito.doAnswer(invocation -> {
            Object entity = invocation.getArgument(0);
            if (type.isInstance(entity)) {
                setField(entity, "id", generatedId);
            }
            return null;
        }).when(em).persist(ArgumentMatchers.any());
    }

    /**
     * Mock "deep stubs" pour les objets chaînés (Query -> setParameter ->
     * setMaxResults -> getResultList).
     * Utilisé par les tests qui n'ont besoin que de contrôler la valeur finale de
     * la chaîne.
     */
    public static jakarta.persistence.Query deepQuery() {
        return mock(jakarta.persistence.Query.class, org.mockito.Answers.RETURNS_SELF);
    }

    /**
     * Fabrique une EntityTransaction « inerte » (isActive = true) pour un
     * EntityManager mocké.
     */
    public static void withTransaction(EntityManager em) {
        EntityTransaction transaction = mock(EntityTransaction.class);
        lenient().when(transaction.isActive()).thenReturn(true);
        lenient().when(em.getTransaction()).thenReturn(transaction);
    }

    /**
     * Crée (sans l'installer) un EntityManagerFactory mocké pour les tests de Jpa.
     */
    public static EntityManagerFactory mockFactory(boolean open) {
        EntityManagerFactory factory = mock(EntityManagerFactory.class);
        lenient().when(factory.isOpen()).thenReturn(open);
        return factory;
    }
}
