package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtpKeyingTest {

    @Autowired
    private OtpServiceImpl otpService;

    @Autowired
    private OtpRepository otpRepository;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void generateOtpReturnsPlaintextButPersistsADigest() {
        String email = "keyed@example.com";

        String plainCode = otpService.generateOtp(email, OtpType.CREATE);

        assertThat(plainCode).matches("[0-9]{6}");

        Otp stored = latest(email, OtpType.CREATE);
        assertThat(stored.getCode())
                .isNotEqualTo(plainCode)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void storedDigestIsNotAnUnkeyedHashOfTheCode() {
        String email = "keyed-not-plain@example.com";
        String plainCode = otpService.generateOtp(email, OtpType.CREATE);

        assertThat(latest(email, OtpType.CREATE).getCode())
                .isNotEqualTo(sha256Hex(plainCode))
                .isNotEqualTo(sha256Hex(email + "|CREATE|" + plainCode));
    }

    @Test
    void theSameCodeIsSeparatedByEmailAndPurpose() {
        String fixedCode = "123456";
        String createForA = otpService.keyedDigest(
                "domain-a@example.com", OtpType.CREATE, fixedCode);

        assertThat(otpService.keyedDigest(
                "domain-b@example.com", OtpType.CREATE, fixedCode))
                .isNotEqualTo(createForA);
        assertThat(otpService.keyedDigest(
                "domain-a@example.com", OtpType.RESET, fixedCode))
                .isNotEqualTo(createForA);
    }

    @Test
    void consumeOtpAcceptsThePlaintextCodeExactlyOnce() {
        String email = "keyed-consume@example.com";
        String plainCode = otpService.generateOtp(email, OtpType.CREATE);

        assertThat(otpService.consumeOtp(plainCode, email, OtpType.CREATE)).isTrue();
        assertThat(otpService.consumeOtp(plainCode, email, OtpType.CREATE))
                .as("a consumed code must not work twice")
                .isFalse();
    }

    @Test
    void consumeOtpRejectsTheStoredDigestSubmittedAsACode() {
        String email = "keyed-replay@example.com";
        otpService.generateOtp(email, OtpType.CREATE);

        String storedDigest = latest(email, OtpType.CREATE).getCode();

        assertThat(otpService.consumeOtp(storedDigest, email, OtpType.CREATE)).isFalse();
    }

    @Test
    void otpMintedForCreateIsRejectedForReset() {
        String email = "keyed-wrong-type@example.com";
        String plainCode = otpService.generateOtp(email, OtpType.CREATE);

        assertThat(otpService.consumeOtp(plainCode, email, OtpType.RESET)).isFalse();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Otp latest(String email, OtpType type) {
        return otpRepository.findAll().stream()
                .filter(otp -> email.equals(otp.getEmail()))
                .filter(otp -> otp.getType() == type)
                .max(Comparator.comparing(Otp::getCreatedAt))
                .orElseThrow();
    }
}
