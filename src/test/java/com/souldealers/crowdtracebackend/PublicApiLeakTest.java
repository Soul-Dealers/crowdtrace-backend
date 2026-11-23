package com.souldealers.crowdtracebackend;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Every public GET, success or error, may only expose the keys listed here, at every depth. */
@AutoConfigureMockMvc
class PublicApiLeakTest extends CasePostgresTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> ENVELOPE = Set.of("success", "message", "data", "errors");
    private static final Set<String> PAGE = Set.of("content", "size", "hasNext", "nextCursor");
    private static final Set<String> CASE_SUMMARY = Set.of("id", "fullName", "age", "gender", "region",
            "lastSeenDate", "caseStatus", "presentation", "approvedAt", "resolvedAt", "thumbnailPhotoId");
    private static final Set<String> CASE_DETAIL = Set.of("id", "fullName", "age", "gender", "lastSeenDate",
            "region", "lastSeenLocation", "physicalDescription", "clothing", "circumstances", "caseStatus",
            "closingStatement", "publicContactNumber", "approvedAt", "resolvedAt", "presentation", "photoIds");
    private static final Set<String> COMMENT = Set.of("id", "parentCommentId", "author", "body", "createdAt",
            "replyCount", "deleted", "removed");
    private static final Set<String> AUTHOR = Set.of("displayName", "avatarSeed", "verified", "badgeType");
    private static final Set<String> COUNT = Set.of("topLevel");
    private static final Set<String> PROBLEM = Set.of("type", "title", "status", "detail", "instance", "code",
            "correlationId", "errors");

    private static final Map<String, Set<String>> CASE_PAGE =
            Map.of("$", ENVELOPE, "$.data", PAGE, "$.data.content[*]", CASE_SUMMARY);
    private static final Map<String, Set<String>> CASE =
            Map.of("$", ENVELOPE, "$.data", CASE_DETAIL);
    private static final Map<String, Set<String>> COMMENT_PAGE = Map.of("$", ENVELOPE, "$.data", PAGE,
            "$.data.content[*]", COMMENT, "$.data.content[*].author", AUTHOR);
    private static final Map<String, Set<String>> COMMENT_COUNT = Map.of("$", ENVELOPE, "$.data", COUNT);
    private static final Map<String, Set<String>> ERROR = Map.of("$", PROBLEM); // no public GET returns an errors map

    @Autowired private MockMvc mvc;
    @Autowired private CaseRecordRepository cases;

    private long approved;
    private long submitted;
    private long root;
    private String authorEmail;

    @BeforeEach
    void seed() {
        authorEmail = "leak-" + UUID.randomUUID() + "@example.com";
        long author = user(authorEmail);
        jdbc.update("UPDATE users SET display_name = 'Ama_Helps', badge_type = 'NGO' WHERE id = ?", author);
        approved = cases.saveAndFlush(aCase(author).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(at(9)).submittedAt(at(8)).createdAt(at(8))
                .updatedAt(at(8)).build()).getId();
        submitted = cases.saveAndFlush(aCase(author).submittedAt(at(8)).createdAt(at(8)).updatedAt(at(8))
                .build()).getId();
        root = jdbc.queryForObject("""
                INSERT INTO comments (case_id, author_id, body, created_at) VALUES (?, ?, 'Seen at Madina', ?)
                RETURNING id""", Long.class, approved, author, at(10));
        jdbc.update("""
                INSERT INTO comments (case_id, author_id, body, parent_comment_id, created_at)
                VALUES (?, ?, 'Where exactly?', ?, ?)""", approved, author, root, at(11));
    }

    @Test
    void publicSuccessBodiesExposeOnlyThePublicProjection() throws Exception {
        String comments = "/api/public/cases/" + approved + "/comments";
        List<String> violations = new ArrayList<>();
        violations.addAll(check("/api/public/cases", 200, CASE_PAGE, "$.data.content[*]"));
        violations.addAll(check("/api/public/cases?name=Mensah", 200, CASE_PAGE, "$.data.content[*]"));
        violations.addAll(check("/api/public/cases/" + approved, 200, CASE, "$.data"));
        violations.addAll(check(comments, 200, COMMENT_PAGE, "$.data.content[*].author"));
        violations.addAll(check(comments + "?parentCommentId=" + root, 200, COMMENT_PAGE,
                "$.data.content[*].author"));
        violations.addAll(check(comments + "/count", 200, COMMENT_COUNT, "$.data"));
        assertThat(violations).isEmpty();
    }

    @Test
    void publicErrorBodiesExposeOnlyProblemFields() throws Exception {
        List<String> violations = new ArrayList<>();
        violations.addAll(check("/api/public/cases/" + submitted, 404, ERROR, "$"));
        violations.addAll(check("/api/public/cases?size=0", 400, ERROR, "$"));
        violations.addAll(check("/api/public/cases?ageMin=abc", 400, ERROR, "$"));
        violations.addAll(check("/api/public/cases/" + approved + "/comments?cursor=x", 400, ERROR, "$"));
        assertThat(violations).isEmpty();
    }

    @Test
    void anErrorsMapOnAPublicProblemIsAViolation() {
        List<String> violations = new ArrayList<>();
        walk(JSON.readTree("{\"status\":400,\"errors\":{\"reporterId\":[\"invalid\"]}}"), "$", "$", ERROR,
                violations, new java.util.HashSet<>());
        assertThat(violations).containsExactly("$.errors is an object no rule allows");
    }

    /** Fetches as a guest, walks the body, and requires {@code mustReach} to be visited so a rule can't go stale. */
    private List<String> check(String url, int status, Map<String, Set<String>> rules, String mustReach)
            throws Exception {
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertThat(response.getStatus()).as(url).isEqualTo(status);
        String body = response.getContentAsString();
        List<String> violations = new ArrayList<>();
        if (body.contains(authorEmail)) {
            violations.add("body contains the author's email");
        }
        Set<String> reached = new java.util.HashSet<>();
        walk(JSON.readTree(body), "$", "$", rules, violations, reached);
        assertThat(reached).as(url + " never reached " + mustReach).contains(mustReach);
        return violations.stream().map(v -> url + ": " + v).toList();
    }

    private static void walk(JsonNode node, String path, String rule, Map<String, Set<String>> rules,
                             List<String> violations, Set<String> reached) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", rule + "[*]", rules, violations, reached);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Set<String> allowed = rules.get(rule);
        if (allowed == null) {
            violations.add(path + " is an object no rule allows");
            return;
        }
        reached.add(rule);
        for (String key : node.propertyNames()) {
            JsonNode value = node.get(key);
            if (!allowed.contains(key)) {
                violations.add(path + " has unexpected key '" + key + "'");
            } else {
                walk(value, path + "." + key, rule + "." + key, rules, violations, reached);
            }
        }
    }
}
