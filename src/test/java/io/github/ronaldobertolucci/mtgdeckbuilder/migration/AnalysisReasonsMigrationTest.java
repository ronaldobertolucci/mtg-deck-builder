package io.github.ronaldobertolucci.mtgdeckbuilder.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AnalysisReasonsMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Test
    void upgradesExistingMessagesWithoutGuessingCodesOrSeverity() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("11").load().migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = connection.createStatement()) {
            insertHistoricalMessages(sql);
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .load().migrate();
            assertHistoricalMessages(sql);
        }
    }

    @Test
    void upgradesExistingMessagesOnH2WithoutPostgresMode() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
             var sql = connection.createStatement()) {
            // Exercise the analysis schema and migration in H2's default SQL mode.
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V6__create_deck_tables.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V8__add_deck_analysis.sql"));
            insertHistoricalMessages(sql);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V12__structure_deck_analysis_reasons.sql"));
            assertHistoricalMessages(sql);
        }
    }

    private void insertHistoricalMessages(Statement sql) throws Exception {
        sql.executeUpdate("""
                INSERT INTO decks (id, user_id, name, format, status, analyzed_at)
                VALUES ('11111111-1111-4111-8111-111111111111', 42, 'Test', 'MODERN', 'IRREGULAR', CURRENT_TIMESTAMP)
                """);
        sql.executeUpdate("""
                INSERT INTO deck_analysis_messages (deck_id, position, message) VALUES
                ('11111111-1111-4111-8111-111111111111', 0, 'Confirmed violation'),
                ('11111111-1111-4111-8111-111111111111', 1, 'Uncertainty')
                """);
    }

    private void assertHistoricalMessages(Statement sql) throws Exception {
        try (var rows = sql.executeQuery("SELECT code, severity, message, parameters FROM deck_analysis_messages ORDER BY position")) {
            for (String message : new String[] {"Confirmed violation", "Uncertainty"}) {
                assertTrue(rows.next());
                assertEquals(message, rows.getString("message"));
                assertEquals("LEGACY_MESSAGE", rows.getString("code"));
                assertEquals("LEGACY", rows.getString("severity"));
                assertEquals("{}", rows.getString("parameters"));
            }
            assertFalse(rows.next());
        }
        try (var row = sql.executeQuery("SELECT status, analyzed_at FROM decks")) {
            assertTrue(row.next());
            assertEquals("IRREGULAR", row.getString("status"));
            assertNotNull(row.getTimestamp("analyzed_at"));
        }
    }
}
