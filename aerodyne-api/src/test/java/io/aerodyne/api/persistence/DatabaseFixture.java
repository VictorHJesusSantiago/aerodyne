package io.aerodyne.api.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Where the integration tests get a PostGIS from.
 *
 * <p>Two ways, because there are two situations. Ordinarily Testcontainers starts one, which
 * needs nothing configured and cleans up after itself. But Testcontainers talks to the Docker
 * API directly, and there are setups where the CLI works and that API does not — a hardened
 * Docker Desktop, a rootless daemon behind a proxy, a CI runner with a database already up as a
 * service. Setting {@code AERODYNE_TEST_DB_URL} points the tests at a database that is already
 * running and skips the container entirely.
 *
 * <p>The decision is made once, in a holder of its own, so that asking <em>whether</em> a
 * database is available does not start one. A test class that asked the question by touching the
 * container field would start it as a side effect of deciding whether to skip.
 */
public final class DatabaseFixture {

    /** Point the tests at an already-running database instead of starting a container. */
    static final String URL_VARIABLE = "AERODYNE_TEST_DB_URL";
    static final String USER_VARIABLE = "AERODYNE_TEST_DB_USER";
    static final String PASSWORD_VARIABLE = "AERODYNE_TEST_DB_PASSWORD";

    /** A PostGIS build, not plain PostgreSQL: the first migration creates the extension. */
    private static final DockerImageName IMAGE = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer<?> container;

    private DatabaseFixture() {
    }

    /** True when the tests can reach a database at all, without starting one to find out. */
    public static boolean isAvailable() {
        return externalUrl() != null || dockerIsUsable();
    }

    /** Points Spring at whichever database this run has, starting a container if it needs to. */
    public static synchronized void configure(DynamicPropertyRegistry registry) {
        String external = externalUrl();
        if (external != null) {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username",
                    () -> valueOr(USER_VARIABLE, "aerodyne"));
            registry.add("spring.datasource.password",
                    () -> valueOr(PASSWORD_VARIABLE, "aerodyne"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("aerodyne")
                    .withUsername("aerodyne")
                    .withPassword("aerodyne");
            container.start();
            // Started once for the whole run and left to the JVM to reap: Testcontainers'
            // own reaper removes it, and stopping it between classes would cost a restart.
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    /** How this run reached its database, for the skip message and for the log. */
    public static String describe() {
        return externalUrl() != null
                ? "an external database at " + externalUrl()
                : "a Testcontainers PostGIS";
    }

    private static String externalUrl() {
        String url = System.getenv(URL_VARIABLE);
        return url == null || url.isBlank() ? null : url;
    }

    private static String valueOr(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean dockerIsUsable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unreachable) {
            return false;
        }
    }
}
