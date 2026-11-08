package fr.alp.confidentia.persistence;

import fr.alp.confidentia.config.AppConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class Jpa {
    private static EntityManagerFactory factory;

    private Jpa() {
    }

    public static synchronized void initialize() {
        if (factory != null) {
            return;
        }

        Map<String, Object> properties = new HashMap<>();
        String host = env("DB_HOST", "localhost");
        String port = env("DB_PORT", "3306");
        String database = env("DB_NAME", "confidentia");
        loadMariaDbDriver();
        migratePasswordChangeRequired(host, port, database);
        properties.put("jakarta.persistence.jdbc.url",
                "jdbc:mariadb://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8");
        properties.put("jakarta.persistence.jdbc.user", env("DB_USER", "root"));
        properties.put("jakarta.persistence.jdbc.password", env("DB_PASSWORD", ""));
        properties.put("jakarta.persistence.jdbc.driver", "org.mariadb.jdbc.Driver");
        properties.put("hibernate.hbm2ddl.auto", env("DB_SCHEMA_MODE", "update"));
        properties.put("hibernate.show_sql", "false");
        properties.put("hibernate.format_sql", "false");
        factory = Persistence.createEntityManagerFactory("confidentia", properties);
        migrateLegacyListings(host, port, database);
    }

    public static <T> T transaction(Function<EntityManager, T> work) {
        EntityManagerFactory current = requireFactory();
        EntityManager entityManager = current.createEntityManager();
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            T result = work.apply(entityManager);
            transaction.commit();
            return result;
        } catch (RuntimeException exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw exception;
        } finally {
            entityManager.close();
        }
    }

    public static synchronized void close() {
        if (factory != null && factory.isOpen()) {
            factory.close();
            factory = null;
        }
    }

    private static synchronized EntityManagerFactory requireFactory() {
        if (factory == null || !factory.isOpen()) {
            throw new IllegalStateException("La connexion JPA n'est pas initialisée.");
        }
        return factory;
    }

    private static String env(String name, String fallback) {
        return AppConfiguration.get(name, fallback);
    }

    private static void loadMariaDbDriver() {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Le pilote JDBC MariaDB est introuvable.", exception);
        }
    }

    private static void migrateLegacyListings(String host, String port, String database) {
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8";
        try (Connection connection = DriverManager.getConnection(url, env("DB_USER", "root"),
                env("DB_PASSWORD", ""))) {
            ensureMigrationTable(connection);
            connection.setAutoCommit(false);
            try {
                boolean applied;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT version FROM app_migrations WHERE version = ?")) {
                    statement.setString(1, "2026-10-broker-approval");
                    try (ResultSet result = statement.executeQuery()) {
                        applied = result.next();
                    }
                }
                if (!applied) {
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("""
                                UPDATE listings
                                SET status = 'PENDING_BROKER_APPROVAL'
                                WHERE status = 'PUBLISHED'
                                """);
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO app_migrations (version) VALUES (?)")) {
                        statement.setString(1, "2026-10-broker-approval");
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible d'appliquer la migration de validation des annonces.", exception);
        }
    }

    private static void migratePasswordChangeRequired(String host, String port, String database) {
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8";
        try (Connection connection = DriverManager.getConnection(url, env("DB_USER", "root"),
                env("DB_PASSWORD", ""))) {
            ensureMigrationTable(connection);

            boolean usersTableExists;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'users'
                    """);
                 ResultSet result = statement.executeQuery()) {
                usersTableExists = result.next();
            }
            if (!usersTableExists) {
                return;
            }

            boolean columnExists;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'users'
                      AND column_name = 'passwordChangeRequired'
                    """);
                 ResultSet result = statement.executeQuery()) {
                columnExists = result.next();
            }
            if (!columnExists) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            ALTER TABLE users
                            ADD COLUMN passwordChangeRequired BOOLEAN NOT NULL DEFAULT FALSE
                            """);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO app_migrations (version) VALUES (?)")) {
                statement.setString(1, "2026-10-user-password-change-required");
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Impossible d'appliquer la migration du changement obligatoire de mot de passe.", exception);
        }
    }

    private static void ensureMigrationTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS app_migrations (
                        version VARCHAR(100) PRIMARY KEY,
                        applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }
}
