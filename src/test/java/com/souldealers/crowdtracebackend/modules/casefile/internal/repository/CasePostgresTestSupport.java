package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.TestPostgres;

import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.shared.EmailService;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "cors.allowed-origins=http://localhost"})
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class CasePostgresTestSupport {
    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        // Runs once per context, so every subclass (each closes its context) gets its own empty database.
        TestPostgres.Database db = TestPostgres.newDatabase();
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        registry.add("spring.datasource.driver-class-name", db::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired protected JdbcTemplate jdbc;
    @MockitoBean protected NotificationService notificationService;
    @MockitoBean protected EmailService emailService;

    protected long user(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Reporter', 'REGISTERED_USER', 'ACTIVE') RETURNING id
                """, Long.class, email);
    }

    protected CaseRecord.CaseRecordBuilder aCase(long reporterId) {
        return CaseRecord.builder().reporterId(reporterId).fullName("Kofi Mensah").age(30)
                .gender(Gender.MALE).lastSeenDate(LocalDate.of(2026, 9, 30)).region(GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Madina").physicalDescription("Slim").clothing("Blue shirt")
                .circumstances("Did not return").publicContactNumber("+233200000000")
                .reviewStatus(ReviewStatus.SUBMITTED);
    }

    protected static LocalDateTime at(int hour) { return LocalDateTime.of(2026, 10, 1, hour, 0); }
}
