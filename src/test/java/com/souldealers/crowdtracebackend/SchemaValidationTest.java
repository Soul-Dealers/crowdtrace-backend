package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots with the Flyway-migrated schema and ddl-auto: validate, the way dev and
 * prod run. Any future entity field without a matching migration fails here
 * instead of at deploy time.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
class SchemaValidationTest {

    @Test
    void entityMappingsMatchTheMigratedSchema() {
        // Success is the context starting. Hibernate validation runs at startup
        // and fails the context if any mapped column is missing.
    }
}
