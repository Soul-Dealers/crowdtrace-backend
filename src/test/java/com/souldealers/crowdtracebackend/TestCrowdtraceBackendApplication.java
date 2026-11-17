package com.souldealers.crowdtracebackend;

/**
 * Runs the real backend for E2E tests and local verification, without Docker:
 * {@code ./mvnw spring-boot:test-run}. Embedded PostgreSQL with the dev seed (users in db/dev-seed, password
 * Password123!), files kept in memory, emails sent nowhere. Data is gone when it stops.
 */
public class TestCrowdtraceBackendApplication {

    public static void main(String[] args) {
        TestPostgres.Database db = TestPostgres.newDatabase();
        System.setProperty("spring.datasource.url", db.getJdbcUrl());
        System.setProperty("spring.datasource.username", db.getUsername());
        System.setProperty("spring.datasource.password", db.getPassword());
        System.setProperty("spring.flyway.locations", "classpath:db/migration,classpath:db/dev-seed");
        System.setProperty("spring.docker.compose.enabled", "false");
        System.setProperty("resend.url", "http://127.0.0.1:9/emails");
        System.setProperty("resend.api-key", "e2e");
        System.setProperty("resend.from-email", "e2e@example.com");
        CrowdtraceBackendApplication.main(args);
    }
}
