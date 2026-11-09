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
        migrateListingClassification(host, port, database);
        migrateBuyerProfileDetails(host, port, database);
        migrateListingFinancialDiagnosis(host, port, database);
        migrateListingSaleProcesses(host, port, database);
        migratePartnerExperts(host, port, database);
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

    private static void migrateListingClassification(String host, String port, String database) {
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8";
        try (Connection connection = DriverManager.getConnection(url, env("DB_USER", "root"),
                env("DB_PASSWORD", ""))) {
            ensureMigrationTable(connection);
            boolean listingsTableExists;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'listings'
                    """);
                 ResultSet result = statement.executeQuery()) {
                listingsTableExists = result.next();
            }
            if (!listingsTableExists) {
                return;
            }

            addColumnIfMissing(connection, "listings", "sectorCategory", "VARCHAR(30) NULL");
            addColumnIfMissing(connection, "listings", "activityDomains", "VARCHAR(1000) NULL");
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO app_migrations (version) VALUES (?)")) {
                statement.setString(1, "2026-11-listing-classification");
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Impossible d'appliquer la migration des secteurs et domaines d'activité.", exception);
        }
    }

    private static void migrateBuyerProfileDetails(String host, String port, String database) {
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8";
        try (Connection connection = DriverManager.getConnection(url, env("DB_USER", "root"),
                env("DB_PASSWORD", ""))) {
            ensureMigrationTable(connection);
            boolean buyerProfilesTableExists;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'buyer_profiles'
                    """);
                 ResultSet result = statement.executeQuery()) {
                buyerProfilesTableExists = result.next();
            }
            if (!buyerProfilesTableExists) {
                return;
            }

            addColumnIfMissing(connection, "buyer_profiles", "buyerType", "VARCHAR(20) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "phone", "VARCHAR(40) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "nationality", "VARCHAR(100) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "residence", "VARCHAR(120) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "profession", "VARCHAR(160) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "indicativeFinancialCapacity", "DECIMAL(16,2) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "legalForm", "VARCHAR(100) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "country", "VARCHAR(100) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "registrationNumber", "VARCHAR(120) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "taxIdentifier", "VARCHAR(120) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "headOffice", "VARCHAR(240) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "representativeFunction", "VARCHAR(120) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "countryCode", "CHAR(2) NULL");
            addColumnIfMissing(connection, "buyer_profiles", "financialCapacityCurrencyCode",
                    "CHAR(3) NOT NULL DEFAULT 'XOF'");
            addColumnIfMissing(connection, "buyer_profiles", "investmentCurrencyCode",
                    "CHAR(3) NOT NULL DEFAULT 'EUR'");
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO app_migrations (version) VALUES (?)")) {
                statement.setString(1, "2026-11-buyer-profile-details");
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Impossible d'appliquer la migration des profils acquéreurs.", exception);
        }
    }

    private static void migrateListingFinancialDiagnosis(String host, String port, String database) {
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8";
        try (Connection connection = DriverManager.getConnection(url, env("DB_USER", "root"),
                env("DB_PASSWORD", ""))) {
            ensureMigrationTable(connection);
            boolean listingsTableExists;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'listings'
                    """);
                 ResultSet result = statement.executeQuery()) {
                listingsTableExists = result.next();
            }
            if (!listingsTableExists) {
                return;
            }

            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS listing_financial_diagnoses (
                            id BIGINT NOT NULL AUTO_INCREMENT,
                            listing_id BIGINT NOT NULL,
                            countryCode CHAR(2) NULL,
                            currencyCode CHAR(3) NOT NULL DEFAULT 'EUR',
                            netIncome DECIMAL(16,2) NULL,
                            totalAssets DECIMAL(16,2) NULL,
                            totalDebts DECIMAL(16,2) NULL,
                            fixedAssets DECIMAL(16,2) NULL,
                            financialOutlook TEXT NULL,
                            valuationMultiple DECIMAL(5,2) NULL,
                            valuationValidatedBy BIGINT NULL,
                            valuationValidatedAt DATETIME(6) NULL,
                            PRIMARY KEY (id),
                            UNIQUE KEY uk_listing_financial_diagnosis_listing (listing_id)
                        ) ENGINE=InnoDB
                        """);
            }
            addColumnIfMissing(connection, "listing_financial_diagnoses", "countryCode", "CHAR(2) NULL");
            addColumnIfMissing(connection, "listing_financial_diagnoses", "currencyCode",
                    "CHAR(3) NOT NULL DEFAULT 'EUR'");
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT IGNORE INTO listing_financial_diagnoses (listing_id, currencyCode)
                        SELECT id, 'EUR' FROM listings
                        """);
            }
            migrateExistingListingFinancialColumns(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO app_migrations (version) VALUES (?)")) {
                statement.setString(1, "2026-11-listing-financial-diagnosis");
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Impossible d'appliquer la migration du diagnostic financier des annonces.", exception);
        }
    }

    private static void migrateExistingListingFinancialColumns(Connection connection) throws SQLException {
        var financialColumns = java.util.List.of("netIncome", "totalAssets", "totalDebts", "fixedAssets",
                "financialOutlook", "valuationMultiple", "valuationValidatedBy", "valuationValidatedAt");
        var existingColumns = new java.util.HashSet<String>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'listings'
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                existingColumns.add(result.getString(1));
            }
        }
        var availableColumns = financialColumns.stream().filter(existingColumns::contains).toList();
        if (availableColumns.isEmpty()) {
            return;
        }
        String columns = String.join(", ", availableColumns);
        String selectedColumns = availableColumns.stream().map(column -> "l." + column)
                .collect(java.util.stream.Collectors.joining(", "));
        String populatedColumnCheck = availableColumns.stream()
                .map(column -> "l." + column + " IS NOT NULL")
                .collect(java.util.stream.Collectors.joining(" OR "));
        String sql = "INSERT INTO listing_financial_diagnoses (listing_id, " + columns + ") "
                + "SELECT l.id, " + selectedColumns + " FROM listings l "
                + "LEFT JOIN listing_financial_diagnoses d ON d.listing_id = l.id "
                + "WHERE d.id IS NULL AND (" + populatedColumnCheck + ")";
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void migrateListingSaleProcesses(String host, String port, String database) {
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8";
        try (Connection connection = DriverManager.getConnection(url, env("DB_USER", "root"),
                env("DB_PASSWORD", ""))) {
            ensureMigrationTable(connection);
            boolean listingsTableExists;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'listings'
                    """);
                 ResultSet result = statement.executeQuery()) {
                listingsTableExists = result.next();
            }
            if (!listingsTableExists) {
                return;
            }

            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS listing_sale_processes (
                            id BIGINT NOT NULL AUTO_INCREMENT,
                            listing_id BIGINT NOT NULL,
                            stage VARCHAR(40) NOT NULL,
                            updatedBy BIGINT NULL,
                            updatedAt DATETIME(6) NOT NULL,
                            PRIMARY KEY (id),
                            UNIQUE KEY uk_listing_sale_process_listing (listing_id)
                        ) ENGINE=InnoDB
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS listing_sale_process_history (
                            id BIGINT NOT NULL AUTO_INCREMENT,
                            listing_id BIGINT NOT NULL,
                            from_stage VARCHAR(40) NULL,
                            to_stage VARCHAR(40) NOT NULL,
                            changedBy BIGINT NULL,
                            changedAt DATETIME(6) NOT NULL,
                            PRIMARY KEY (id),
                            KEY idx_listing_sale_process_history (listing_id, changedAt)
                        ) ENGINE=InnoDB
                        """);
                statement.executeUpdate("""
                        INSERT IGNORE INTO listing_sale_processes (listing_id, stage, updatedBy, updatedAt)
                        SELECT id, 'SELLER_REGISTRATION', NULL, COALESCE(createdAt, CURRENT_TIMESTAMP(6))
                        FROM listings
                        """);
                statement.executeUpdate("""
                        INSERT INTO listing_sale_process_history
                            (listing_id, from_stage, to_stage, changedBy, changedAt)
                        SELECT p.listing_id, NULL, p.stage, NULL, p.updatedAt
                        FROM listing_sale_processes p
                        WHERE NOT EXISTS (
                            SELECT 1 FROM listing_sale_process_history h
                            WHERE h.listing_id = p.listing_id
                        )
                        """);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO app_migrations (version) VALUES (?)")) {
                statement.setString(1, "2026-11-listing-sale-process");
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Impossible d'appliquer la migration du suivi des cessions.", exception);
        }
    }

    private static void migratePartnerExperts(String host, String port, String database) {
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8";
        try (Connection connection = DriverManager.getConnection(url, env("DB_USER", "root"),
                env("DB_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS partner_experts (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        name VARCHAR(180) NOT NULL,
                        category VARCHAR(32) NOT NULL,
                        country VARCHAR(100) NOT NULL,
                        location VARCHAR(160) NULL,
                        summary TEXT NULL,
                        contactEmail VARCHAR(254) NULL,
                        phone VARCHAR(40) NULL,
                        website VARCHAR(300) NULL,
                        active BOOLEAN NOT NULL DEFAULT TRUE,
                        createdAt DATETIME(6) NOT NULL,
                        updatedAt DATETIME(6) NOT NULL,
                        PRIMARY KEY (id),
                        KEY idx_partner_experts_category_active (category, active)
                    ) ENGINE=InnoDB
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible d'appliquer la migration des partenaires et experts.", exception);
        }
    }

    private static void addColumnIfMissing(Connection connection, String tableName, String columnName,
                                           String definition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
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
