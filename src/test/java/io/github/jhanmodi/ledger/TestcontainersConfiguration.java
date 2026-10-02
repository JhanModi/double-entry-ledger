package io.github.jhanmodi.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real Postgres in Docker for integration tests. {@code @ServiceConnection} points the app's datasource at
 * the container, overriding the URL and credentials in application.yml. Spring caches the test context, so tests
 * that import this configuration share one container. Public so integration tests in module subpackages can import it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // Same major version as compose.yaml, so tests and local development run against the same database.
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
    }
}
