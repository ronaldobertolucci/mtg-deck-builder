package io.github.ronaldobertolucci.mtgdeckbuilder.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EmailVerificationMigrationTest {
    @Test
    void migrationPreservesAccessAndNeverEnablesAmbiguousLegacyAccounts() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        verifyMigration(url, "sa", "");
    }

    protected void verifyMigration(String url, String username, String password) throws Exception {
        Flyway.configure().dataSource(url, username, password).target("10").load().migrate();
        try (var connection = DriverManager.getConnection(url, username, password); var sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO users (id, first_name, last_name, email, date_of_birth, password, enabled) VALUES
                    (101, 'Test', 'User', 'active@example.com', DATE '1990-01-01', 'hash', true),
                    (102, 'Test', 'User', 'confirmed-disabled@example.com', DATE '1990-01-01', 'hash', false),
                    (103, 'Test', 'User', 'pending@example.com', DATE '1990-01-01', 'hash', false),
                    (104, 'Test', 'User', 'unknown@example.com', DATE '1990-01-01', 'hash', false)
                    """);
            sql.executeUpdate("""
                    INSERT INTO email_verification_tokens (token, user_id, expiry_date, used) VALUES
                    ('used', 102, TIMESTAMP '2020-01-01 00:00:00', true),
                    ('pending', 103, TIMESTAMP '2030-01-01 00:00:00', false)
                    """);
            Flyway.configure().dataSource(url, username, password).load().migrate();
            try (var rows = sql.executeQuery("SELECT id, enabled, email_verified FROM users ORDER BY id")) {
                int count = 0;
                while (rows.next()) {
                    int id = rows.getInt("id");
                    assertEquals(id == 101, rows.getBoolean("enabled"));
                    assertEquals(id == 101 || id == 102, rows.getBoolean("email_verified"));
                    count++;
                }
                assertEquals(4, count);
            }
            sql.executeUpdate("""
                    INSERT INTO users (first_name, last_name, email, date_of_birth, password)
                    VALUES ('New', 'User', 'new@example.com', DATE '1990-01-01', 'hash')
                    """);
            try (var row = sql.executeQuery("SELECT enabled, email_verified FROM users WHERE email='new@example.com'")) {
                assertTrue(row.next());
                assertTrue(row.getBoolean("enabled"));
                assertFalse(row.getBoolean("email_verified"));
            }
        }
    }
}
