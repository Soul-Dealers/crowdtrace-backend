package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.OtpService;
import com.souldealers.crowdtracebackend.modules.identity.internal.config.OtpProperties;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.OtpType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

    private static final int OTP_LENGTH = 6;
    private static final int EXPIRY_MINUTES = 5;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRepository repository;
    private final OtpProperties otpProperties;

    @Override
    @Transactional
    public String generateOtp(String email, OtpType type) {
        String plainCode = generator();

        repository.save(Otp.builder()
                .code(keyedDigest(email, type, plainCode))
                .type(type)
                .expiredAt(LocalDateTime.now().plusMinutes(EXPIRY_MINUTES))
                .email(email)
                .build());

        return plainCode;
    }

    @Override
    @Transactional
    public boolean consumeOtp(String otpCode, String email, OtpType type) {
        if (otpCode == null) {
            return false;
        }

        String candidate = keyedDigest(email, type, otpCode);

        return repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(email, type)
                .filter(otp -> constantTimeEquals(otp.getCode(), candidate))
                .filter(otp -> !isOtpExpired(otp))
                .map(otp -> {
                    otp.setExpiredAt(LocalDateTime.now());
                    repository.save(otp);
                    return true;
                })
                .orElse(false);
    }

    String keyedDigest(String email, OtpType type, String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(otpProperties.hmacKey(), HMAC_ALGORITHM));

            String message = email + "|" + type.name() + "|" + code;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("OTP keying is unavailable", exception);
        }
    }

    private boolean constantTimeEquals(String stored, String candidate) {
        if (stored == null) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isOtpExpired(Otp otp) {
        LocalDateTime expiredAt = otp.getExpiredAt();
        return expiredAt == null || !LocalDateTime.now().isBefore(expiredAt);
    }

    private String generator() {
        StringBuilder otp = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(RANDOM.nextInt(10));
        }
        return otp.toString();
    }
}
