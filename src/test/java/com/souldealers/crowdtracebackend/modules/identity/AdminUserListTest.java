package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminUserListTest extends AdminUserPostgresTest {

    private static final String USERS = "/api/v1/admin/users";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void listsNewestFirstAcrossPagesWithoutGapsOrRepeats() throws Exception {
        String prefix = "walk" + System.nanoTime();
        LocalDateTime shared = LocalDateTime.of(2030, 1, 1, 12, 0);
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(saveUser(prefix + "-s" + i + "@example.com", "Shared", UserRoles.REGISTERED_USER,
                    UserStatus.ACTIVE, shared).getId());
        }
        expected.sort((a, b) -> Long.compare(b, a));
        expected.add(saveUser(prefix + "-o1@example.com", "Older", UserRoles.REGISTERED_USER,
                UserStatus.ACTIVE, shared.minusDays(1)).getId());
        expected.add(saveUser(prefix + "-o2@example.com", "Oldest", UserRoles.REGISTERED_USER,
                UserStatus.ACTIVE, shared.minusDays(2)).getId());

        String admin = token(UserRoles.SUPER_ADMIN);
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        JsonNode page;
        do {
            MockHttpServletRequestBuilder request = get(USERS).header("Authorization", admin)
                    .param("q", prefix).param("size", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            page = data(request);
            page.get("content").forEach(row -> seen.add(row.get("id").asLong()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (page.get("hasNext").asBoolean());

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void filtersByRoleAndStatus() throws Exception {
        String prefix = "filter" + System.nanoTime();
        User moderator = saveUser(prefix + "-m@example.com", "Mod", UserRoles.MODERATOR, UserStatus.ACTIVE, null);
        saveUser(prefix + "-u@example.com", "User", UserRoles.REGISTERED_USER, UserStatus.ACTIVE, null);
        User gone = saveUser(prefix + "-d@example.com", "Gone", UserRoles.REGISTERED_USER,
                UserStatus.DEACTIVATED, null);
        String admin = token(UserRoles.SUPER_ADMIN);

        assertThat(ids(get(USERS).header("Authorization", admin).param("q", prefix).param("role", "MODERATOR")))
                .containsExactly(moderator.getId());
        assertThat(ids(get(USERS).header("Authorization", admin).param("q", prefix).param("status", "DEACTIVATED")))
                .containsExactly(gone.getId());
    }

    @Test
    void searchesEmailOrNamePrefixIgnoringCase() throws Exception {
        String tag = "x" + System.nanoTime();
        User alice = saveUser("alice" + tag + "@example.com", "Zed " + tag, UserRoles.REGISTERED_USER,
                UserStatus.ACTIVE, null);
        User alison = saveUser("z" + tag + "@example.com", "Alison" + tag, UserRoles.REGISTERED_USER,
                UserStatus.ACTIVE, null);
        saveUser("malia" + tag + "@example.com", "Malia" + tag, UserRoles.REGISTERED_USER, UserStatus.ACTIVE, null);
        String admin = token(UserRoles.SUPER_ADMIN);

        List<Long> found = ids(get(USERS).header("Authorization", admin).param("q", "ALI").param("size", "50"));

        assertThat(found).contains(alice.getId(), alison.getId());
        assertThat(found).allSatisfy(id -> {
            User user = userRepository.findById(id).orElseThrow();
            assertThat(user.getEmail().startsWith("ali") || user.getDisplayName().toLowerCase().startsWith("ali"))
                    .isTrue();
        });
    }

    @Test
    void treatsLikeWildcardsLiterally() throws Exception {
        String tag = "w" + System.nanoTime();
        saveUser(tag + "ab@example.com", "Plain", UserRoles.REGISTERED_USER, UserStatus.ACTIVE, null);
        User literal = saveUser(tag + "a_@example.com", "Literal", UserRoles.REGISTERED_USER,
                UserStatus.ACTIVE, null);
        String admin = token(UserRoles.SUPER_ADMIN);

        assertThat(ids(get(USERS).header("Authorization", admin).param("q", tag + "a_")))
                .containsExactly(literal.getId());
        assertThat(ids(get(USERS).header("Authorization", admin).param("q", tag + "%"))).isEmpty();
    }

    @Test
    void capsSizeAt50() throws Exception {
        for (int i = 0; i < 51; i++) {
            saveUser("cap" + System.nanoTime() + "@example.com", "Cap", UserRoles.REGISTERED_USER,
                    UserStatus.ACTIVE, null);
        }

        JsonNode page = data(get(USERS).header("Authorization", token(UserRoles.SUPER_ADMIN)).param("size", "500"));

        assertThat(page.get("content").size()).isEqualTo(50);
        assertThat(page.get("size").asInt()).isEqualTo(50);
    }

    @Test
    void rejectsATamperedCursor() throws Exception {
        mockMvc.perform(get(USERS).header("Authorization", token(UserRoles.SUPER_ADMIN)).param("cursor", "bm9wZQ"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlySuperAdminsCanList() throws Exception {
        mockMvc.perform(get(USERS).header("Authorization", token(UserRoles.MODERATOR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(USERS).header("Authorization", token(UserRoles.REGISTERED_USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private List<Long> ids(MockHttpServletRequestBuilder request) throws Exception {
        return StreamSupport.stream(data(request).get("content").spliterator(), false)
                .map(row -> row.get("id").asLong()).toList();
    }

    private String token(UserRoles role) {
        User user = saveUser(role.name().toLowerCase() + System.nanoTime() + "@example.com", "Actor", role,
                UserStatus.ACTIVE, null);
        return "Bearer " + jwtService.generateToken(new SecurityUser(user));
    }

    private User saveUser(String email, String displayName, UserRoles role, UserStatus status,
                          LocalDateTime createdAt) {
        User user = userRepository.saveAndFlush(User.builder()
                .email(email)
                .passwordHash("x")
                .displayName(displayName)
                .role(role)
                .accountStatus(status)
                .build());
        if (createdAt != null) {
            jdbc.update("update users set created_at = ? where id = ?", Timestamp.valueOf(createdAt), user.getId());
        }
        return user;
    }
}
