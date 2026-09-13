package com.souldealers.crowdtracebackend.modules.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsPrivateCredentialsOutOfTheUserProjection() throws Exception {
        User user = User.builder()
                .email("private@example.com")
                .passwordHash("private-password-hash")
                .displayName("Public Pseudonym")
                .role(UserRoles.REGISTERED_USER)
                .accountStatus(UserStatus.ACTIVE)
                .build();

        String userJson = objectMapper.writeValueAsString(user);
        String responseJson = objectMapper.writeValueAsString(new UserResponse(
                "Public Pseudonym",
                "private@example.com",
                UserRoles.REGISTERED_USER,
                UserStatus.ACTIVE,
                null));

        assertThat(userJson)
                .contains("\"displayName\":\"Public Pseudonym\"")
                .doesNotContain("private@example.com", "private-password-hash", "passwordHash");
        assertThat(responseJson)
                .contains("\"displayName\":\"Public Pseudonym\"")
                .contains("\"email\":\"private@example.com\"")
                .contains("\"role\":\"REGISTERED_USER\"")
                .doesNotContain("password", "verification", "passwordHash");
    }

    @Test
    void doesNotSerializeVerificationEvidence() throws Exception {
        VerificationRequest request = VerificationRequest.builder()
                .user(User.builder()
                        .email("private@example.com")
                        .passwordHash("private-password-hash")
                        .displayName("Public Pseudonym")
                        .role(UserRoles.REGISTERED_USER)
                        .accountStatus(UserStatus.ACTIVE)
                        .build())
                .verificationType(VerificationType.IDENTITY)
                .evidenceReference("private/evidence.pdf")
                .status(VerificationStatus.PENDING)
                .build();

        assertThat(objectMapper.writeValueAsString(request))
                .doesNotContain("private/evidence.pdf", "evidenceReference", "reviewNotes");
    }
}
