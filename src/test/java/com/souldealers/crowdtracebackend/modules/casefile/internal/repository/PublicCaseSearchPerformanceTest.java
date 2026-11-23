package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseCursor;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSearchQuery;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The pilot target: each representative public search, thumbnail lookup included, answers in under 50 ms on a
 * pilot-sized population with the normal planner. Times the service in-process: real SQL and binds, no HTTP.
 */
class PublicCaseSearchPerformanceTest extends CasePostgresTestSupport {
    private static final Logger log = LoggerFactory.getLogger(PublicCaseSearchPerformanceTest.class);
    private static final double LIMIT_MS = 50;
    private static final int RUNS = 5;
    private static final int PAGE = 50;

    @Autowired private CaseQueryService caseQueryService;
    @Autowired private EntityManager em;

    @Test
    void publicSearchesMeetThePilotTarget() {
        long reporter = user("public-perf-" + System.nanoTime() + "@example.com");
        PublicCaseSeed.insertBulk(jdbc, reporter);
        jdbc.update("""
                INSERT INTO case_files (case_id, uploaded_by, purpose, visibility, storage_key,
                    content_type, size_bytes, checksum_sha256, attached_at)
                SELECT id, ?, 'PHOTO', 'PUBLIC', 'cases/' || id || '/photo.jpg', 'image/jpeg', 1024, repeat('a', 64),
                    approved_at
                FROM cases WHERE review_status = 'APPROVED' AND id % 3 = 0""", reporter);
        jdbc.execute("SELECT gin_clean_pending_list('idx_cases_public_name_trgm'::regclass)");
        jdbc.execute("ANALYZE cases");
        jdbc.execute("ANALYZE case_files");

        Map<String, Search> searches = new LinkedHashMap<>();
        searches.put("none", new Search(PublicCaseSearchQuery.all(), null));
        searches.put("name substring", new Search(query("ensim", null, null, null, null, null, null), null));
        searches.put("region", new Search(query(null, GhanaRegion.ASHANTI, null, null, null, null, null), null));
        searches.put("status + region", new Search(
                query(null, GhanaRegion.CENTRAL, Set.of(CaseStatus.MISSING), null, null, null, null), null));
        searches.put("age range", new Search(query(null, null, null, 10, 17, null, null), null));
        searches.put("last-seen range", new Search(query(null, null, null, null, null,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20)), null));
        searches.put("deep cursor", new Search(PublicCaseSearchQuery.all(),
                new PublicCaseCursor(LocalDateTime.of(2026, 9, 25, 0, 0), 3000).encode()));

        SoftAssertions softly = new SoftAssertions();
        searches.forEach((name, search) -> {
            double median = medianMillis(search);
            log.info("public search '{}' median {} ms", name, String.format("%.2f", median));
            softly.assertThat(median).as("%s median %.2f ms", name, median).isLessThan(LIMIT_MS);
        });
        softly.assertAll();
    }

    private double medianMillis(Search search) {
        run(search); // warm-up
        double[] millis = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            em.clear(); // each request starts with an empty persistence context
            long start = System.nanoTime();
            run(search);
            millis[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(millis);
        return millis[RUNS / 2];
    }

    private void run(Search search) {
        var page = caseQueryService.searchPublicCases(search.query(), search.cursor(), PAGE);
        if (page.content().isEmpty()) {
            throw new AssertionError("search returned nothing, so it measured nothing: " + search);
        }
    }

    private static PublicCaseSearchQuery query(String name, GhanaRegion region, Set<CaseStatus> statuses,
                                               Integer ageMin, Integer ageMax, LocalDate from, LocalDate to) {
        return new PublicCaseSearchQuery(name, region, null, statuses, ageMin, ageMax, from, to, null, null);
    }

    private record Search(PublicCaseSearchQuery query, String cursor) {
    }
}
