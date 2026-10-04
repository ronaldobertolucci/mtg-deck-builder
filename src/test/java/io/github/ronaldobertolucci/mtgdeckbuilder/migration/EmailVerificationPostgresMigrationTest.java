package io.github.ronaldobertolucci.mtgdeckbuilder.migration;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class EmailVerificationPostgresMigrationTest extends EmailVerificationMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Override
    @Test
    void migrationPreservesAccessAndNeverEnablesAmbiguousLegacyAccounts() throws Exception {
        verifyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
