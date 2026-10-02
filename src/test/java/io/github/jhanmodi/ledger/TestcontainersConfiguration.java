package io.github.jhanmodi.ledger;

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real Postgres in Docker for integration tests, with the same two connections as a real deployment
 * (ADR-0015): the application connects as the restricted {@code ledger_service} login, and Flyway connects as the
 * owner. Spring caches the test context, so tests that import this configuration share one container. Public so
 * integration tests in module subpackages can import it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Created by src/test/resources/db/testcontainers-roles.sql. */
    static final String APP_USER = "ledger_service";

    static final String APP_PASSWORD = "test-only-not-a-secret";

    @Bean
    PostgreSQLContainer postgresContainer() {
        // Same major version as compose.yaml, so tests and local development run against the same database.
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
                .withInitScript("db/testcontainers-roles.sql");
    }

    /** The application's connection: the restricted login, exactly as locally and in production. */
    @Bean
    JdbcConnectionDetails appConnectionDetails(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return APP_USER;
            }

            @Override
            public String getPassword() {
                return APP_PASSWORD;
            }
        };
    }

    /** Flyway's connection: the container's owner, which owns every table it creates. */
    @Bean
    FlywayConnectionDetails flywayConnectionDetails(PostgreSQLContainer postgres) {
        return new FlywayConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }
        };
    }

    @Bean
    OwnerDatabase ownerDatabase(PostgreSQLContainer postgres) {
        return new OwnerDatabase(postgres);
    }
}
