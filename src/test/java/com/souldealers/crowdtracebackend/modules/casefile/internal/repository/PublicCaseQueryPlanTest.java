package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSearchQuery;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicCaseQueryPlanTest extends CasePostgresTestSupport {
    private static final String PUBLIC_FILTERS = "review_status = 'APPROVED' "
            + "AND case_status IN ('MISSING', 'FOUND_SAFE', 'FOUND_DECEASED', 'CLOSED')";
    private static final List<String> NAMES = List.of(
            "Kofi Mensah", "Ama Boateng", "Kwame Asante", "Abena Osei", "Yaw Addo",
            "Akosua Owusu", "Kojo Antwi", "Adwoa Appiah", "Kwaku Agyeman", "Efua Darko",
            "Kwabena Mensima", "Esi Amankwah", "Nana Agyapong", "Afia Bediako", "Kwadwo Frimpong",
            "Yaa Acheampong", "Osei Tutu", "Mawuli Dzifa", "Ato Quansah", "Akua Sarpong");

    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseQueryService caseQueryService;
    private long reporter;

    @BeforeEach
    void seedCases() {
        reporter = user("public-plan-" + System.nanoTime() + "@example.com");
        for (int i = 0; i < 60; i++) {
            ReviewStatus review = ReviewStatus.values()[i % ReviewStatus.values().length];
            CaseStatus outcome = CaseStatus.values()[i % CaseStatus.values().length];
            // Six approved Mensah rows make the literal trigram predicate selective; other names are
            // assigned independently from the review-state cycle to avoid fixture correlation.
            String name = review == ReviewStatus.APPROVED && i / ReviewStatus.values().length < 6
                    ? NAMES.get(0) : NAMES.get(1 + (i * 7 % (NAMES.size() - 1)));
            var builder = aCase(reporter).fullName(name).age(10 + i % 31)
                    .region(GhanaRegion.values()[i % GhanaRegion.values().length])
                    .lastSeenDate(LocalDate.of(2026, 8, 1).plusDays(i % 60))
                    .reviewStatus(review).submittedAt(at(i % 24));

            if (review == ReviewStatus.APPROVED || review == ReviewStatus.TAKEN_DOWN) {
                builder.caseStatus(outcome).approvedAt(at(i % 24));
                if (outcome != CaseStatus.MISSING) builder.resolvedAt(at(i % 24));
                if (outcome == CaseStatus.CLOSED) builder.closedAt(at(i % 24));
                if (review == ReviewStatus.TAKEN_DOWN) {
                    builder.takedownReason("Duplicate report confirmed").takenDownBy(reporter).takenDownAt(at(i % 24));
                }
            } else {
                builder.caseStatus(null);
            }
            cases.save(builder.build());
        }
        cases.flush();
        // Cost-based plans on only 60 rows fit on one heap page and do not represent the competing
        // index costs. Keep those 60 lifecycle fixtures, then add one representative bulk population.
        jdbc.update("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region,
                    last_seen_location, physical_description, clothing, circumstances, public_contact_number,
                    review_status, case_status, submitted_at, approved_at, resolved_at, closed_at)
                SELECT ?,
                    (ARRAY['Ama','Kwame','Abena','Yaw','Akosua','Kojo','Adwoa','Kwaku','Efua','Kwabena',
                           'Esi','Nana','Afia','Kwadwo','Yaa','Osei','Mawuli','Ato','Akua','Kofi',
                           'Nii','Mansa','Kweku','Serwaa','Fiifi','Ebo','Dzifa','Ekua','Nana','Yvonne']::text[])[(g % 30) + 1]
                    || ' ' || (ARRAY['Boateng','Asante','Osei','Addo','Owusu','Antwi','Appiah','Agyeman','Darko','Mensima',
                           'Amankwah','Agyapong','Bediako','Frimpong','Acheampong','Tutu','Dzifa','Quansah','Sarpong','Arthur',
                           'Bonsu','Nyarko','Donkor','Annan','Mensima','Adu','Koomson','Obeng','Sowah','Amoah',
                           'Oppong','Ababio','Baffour','Adjei','Kyeremeh','Poku','Asare','Badu','Tetteh','Appau']::text[])[(g % 40) + 1]
                    || ' ' || g,
                    (g % 91)::smallint,
                    (ARRAY['MALE','FEMALE','UNKNOWN']::text[])[(g % 3) + 1],
                    DATE '2026-10-01' - (g % 365),
                    (ARRAY['AHAFO','ASHANTI','BONO','BONO_EAST','CENTRAL','EASTERN','GREATER_ACCRA','NORTH_EAST',
                           'NORTHERN','OTI','SAVANNAH','UPPER_EAST','UPPER_WEST','VOLTA','WESTERN','WESTERN_NORTH']::text[])[(g % 16) + 1],
                    'Accra', 'Average build', 'Casual clothes', 'Background search fixture', '+233200000000',
                    CASE WHEN g % 10 < 7 THEN 'APPROVED'
                         WHEN g % 10 < 9 THEN 'SUBMITTED' ELSE 'REJECTED' END,
                    CASE WHEN g % 10 < 7 THEN
                        (ARRAY['MISSING','FOUND_SAFE','FOUND_DECEASED','CLOSED']::text[])[(g % 4) + 1]
                        ELSE NULL END,
                    TIMESTAMP '2026-10-01 00:00:00' - g * INTERVAL '1 minute',
                    CASE WHEN g % 10 < 7
                         THEN TIMESTAMP '2026-10-01 00:00:00' - g * INTERVAL '1 minute' ELSE NULL END,
                    CASE WHEN g % 10 < 7 AND g % 4 <> 0
                         THEN TIMESTAMP '2026-10-01 00:00:00' - g * INTERVAL '1 minute' ELSE NULL END,
                    CASE WHEN g % 10 < 7 AND g % 4 = 3
                         THEN TIMESTAMP '2026-10-01 00:00:00' - g * INTERVAL '1 minute' ELSE NULL END
                FROM generate_series(1, 10000) AS generated(g)
                """, reporter);
        jdbc.execute("SELECT gin_clean_pending_list('idx_cases_public_name_trgm'::regclass)");
        jdbc.execute("ANALYZE cases");
        jdbc.execute("SET LOCAL enable_seqscan = off");
    }

    static Stream<FilterShape> publicFilterShapes() {
        return Stream.of(
                new FilterShape("none", "", SetIndexes.of("idx_cases_public_recent"),
                        SetIndexes.prefix("idx_cases_public_"), true),
                new FilterShape("region", "AND region = 'ASHANTI'", SetIndexes.of("idx_cases_public_region"),
                        SetIndexes.of("idx_cases_public_region"), true),
                new FilterShape("outcome", "AND case_status IN ('MISSING')", SetIndexes.of("idx_cases_public_outcome"),
                        SetIndexes.of("idx_cases_public_outcome"), true),
                new FilterShape("age", "AND age BETWEEN 10 AND 17",
                        SetIndexes.of("idx_cases_public_age", "idx_cases_public_recent"),
                        SetIndexes.of("idx_cases_public_age", "idx_cases_public_recent"), true),
                new FilterShape("name", "AND lower(full_name) LIKE '%mensah%' ESCAPE '\\'",
                        SetIndexes.of("idx_cases_public_name_trgm"), SetIndexes.of("idx_cases_public_name_trgm"), false),
                new FilterShape("last seen", "AND last_seen_date BETWEEN DATE '2026-08-10' AND DATE '2026-08-20'",
                        SetIndexes.of("idx_cases_last_seen", "idx_cases_public_recent", "idx_cases_public_region",
                                "idx_cases_public_outcome", "idx_cases_public_age"),
                        SetIndexes.of("idx_cases_last_seen", "idx_cases_public_recent", "idx_cases_public_region",
                                "idx_cases_public_outcome", "idx_cases_public_age"), true, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicFilterShapes")
    void publicPageAndCountPlansRemainEligibleForIndexes(FilterShape shape) {
        String where = "WHERE " + PUBLIC_FILTERS + " " + shape.whereFragment();
        String page = explain("SELECT * FROM cases " + where
                + " ORDER BY approved_at DESC, id DESC LIMIT 20");
        String count = explain("SELECT count(id) FROM cases " + where);

        assertPlan("page " + shape.name(), page, shape.pageIndexes(), shape.checkStatusCondition(),
                shape.conditionOnlyForPublicBtree());
        assertPlan("count " + shape.name(), count, shape.countIndexes(), shape.checkStatusCondition(),
                shape.conditionOnlyForPublicBtree());
    }

    @Test
    void genericPlansKeepRegionAndNameIndexesEligible() {
        jdbc.execute("SET LOCAL plan_cache_mode = force_generic_plan");

        jdbc.execute("PREPARE public_region_plan(text, text) AS SELECT id FROM cases "
                + "WHERE review_status = $1 AND region = $2 ORDER BY approved_at DESC, id DESC LIMIT 20");
        String region = explain("EXECUTE public_region_plan('APPROVED', 'ASHANTI')");
        assertThat(region).as(region).doesNotContain("Seq Scan").contains("idx_cases_public_region");
        assertThat(region).containsPattern("(?is)Index Cond:.*review_status");

        jdbc.execute("PREPARE public_name_plan(text, text) AS SELECT id FROM cases "
                + "WHERE review_status = $1 AND case_status IN ('MISSING', 'FOUND_SAFE', 'FOUND_DECEASED', 'CLOSED') "
                + "AND lower(full_name) LIKE $2 ESCAPE '\\' ORDER BY approved_at DESC, id DESC LIMIT 20");
        String name = explain("EXECUTE public_name_plan('APPROVED', '%mensah%')");
        assertThat(name).as(name).doesNotContain("Seq Scan").contains("idx_cases_public_name_trgm");
    }

    @Test
    void hibernateEmitsLowercaseEscapedNameLikePredicate() {
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level previousLevel = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(Level.DEBUG);
        try {
            caseQueryService.searchPublicCases(
                    new PublicCaseSearchQuery("mensah", null, null, null, null, null, null, null, null, null),
                    PageRequest.of(0, 20));
        } finally {
            sqlLogger.detachAppender(appender);
            sqlLogger.setLevel(previousLevel);
            appender.stop();
        }

        String sql = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.toLowerCase(Locale.ROOT).contains("lower(")
                        && message.toLowerCase(Locale.ROOT).contains(" like "))
                .findFirst().orElse("");
        assertThat(sql).as("Hibernate SQL captured from org.hibernate.SQL").isNotEmpty();
        assertThat(sql.toLowerCase(Locale.ROOT)).contains("lower(", " like ", "escape");
    }

    private String explain(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
    }

    private static void assertPlan(String label, String plan, SetIndexes acceptable,
                                   boolean checkStatusCondition, boolean conditionOnlyForPublicBtree) {
        assertThat(plan).as(label + " plan\n" + plan).doesNotContain("Seq Scan");
        assertThat(acceptable.matches(plan)).as(label + " acceptable indexes\n" + plan).isTrue();
        boolean selectedPublicBtree = plan.contains("idx_cases_public_recent")
                || plan.contains("idx_cases_public_region") || plan.contains("idx_cases_public_outcome")
                || plan.contains("idx_cases_public_age");
        if (checkStatusCondition && (!conditionOnlyForPublicBtree || selectedPublicBtree)) {
            assertThat(plan).as(label + " review_status index condition\n" + plan)
                    .containsPattern("(?is)(Index Cond|Recheck Cond):[^\\n]*review_status");
        }
    }

    record FilterShape(String name, String whereFragment, SetIndexes pageIndexes,
                       SetIndexes countIndexes, boolean checkStatusCondition,
                       boolean conditionOnlyForPublicBtree) {
        FilterShape(String name, String whereFragment, SetIndexes pageIndexes,
                    SetIndexes countIndexes, boolean checkStatusCondition) {
            this(name, whereFragment, pageIndexes, countIndexes, checkStatusCondition, false);
        }
        @Override public String toString() { return name; }
    }

    record SetIndexes(List<String> names, String prefix) {
        static SetIndexes of(String... names) { return new SetIndexes(List.of(names), null); }
        static SetIndexes prefix(String prefix) { return new SetIndexes(List.of(), prefix); }
        boolean matches(String plan) {
            return names.stream().anyMatch(plan::contains)
                    || (prefix != null && plan.contains(prefix));
        }
    }
}
