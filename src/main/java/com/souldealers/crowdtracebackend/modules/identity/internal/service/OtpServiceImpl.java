package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.OtpService;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import lombok.AllArgsConstructor;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@AllArgsConstructor
public class OtpServiceImpl implements OtpService {

    private final OtpRepository repository;
    private static final int OTP_LENGTH = 6;
    private static final String VERIFICATION_FAILED_MESSAGE = "Could not verify this OTP";

    @Override
    public Otp generateOtp(String email, OtpType type) {
        String code = generator();
        LocalDateTime expirationTime = LocalDateTime.now().plusMinutes(5);

        Otp otp = Otp.builder()
                .code(code)
                .type(type)
                .expiredAt(expirationTime)
                .email(email)
                .build();

        repository.save(otp);

        return otp;
    }

    /**
     * Consumes an OTP (One-Time Password) by validating its code, checking its expiration status,
     * and marking it as expired if valid.
     *
     * @param otpCode the OTP code provided for verification
     * @param email the email address associated with the OTP
     * @param type the type of the OTP (e.g., CREATE, RESET)
     * @return {@code true} if the OTP is successfully consumed; {@code false} otherwise
     */
    @Override
    public boolean consumeOtp(String otpCode, String email, OtpType type) {
        return repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(email, type)
                .filter(otp -> Objects.equals(otp.getCode(), otpCode))
                .filter(otp -> !isOtpExpired(otp))
                .map(otp -> {
                    otp.setExpiredAt(LocalDateTime.now());
                    repository.save(otp);
                    return true;
                })
                .orElse(false);
    }


    @Override
    public boolean isOtpValid(String otpCode, String email, OtpType type) {
        return repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(email, type)
                .filter(otp -> Objects.equals(otp.getCode(), otpCode))
                .map(otp -> !isOtpExpired(otp))
                .orElse(false);
    }


    @Override
    public void invalidateOtp(String otpCode, String email, OtpType type) {
        Otp otp = repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(email, type)
                .orElseThrow(() -> new ConflictException(VERIFICATION_FAILED_MESSAGE));
        if (!Objects.equals(otp.getCode(), otpCode)) {
            throw new ConflictException(VERIFICATION_FAILED_MESSAGE);
        }
        otp.setExpiredAt(LocalDateTime.now());
        repository.save(otp);
    }

    private boolean isOtpExpired(Otp otp) {
        LocalDateTime currentTime = LocalDateTime.now();
        LocalDateTime expiredAt = otp.getExpiredAt();

        return expiredAt == null || !currentTime.isBefore(expiredAt);
    }


    private String generator() {
        SecureRandom random = new SecureRandom();

        StringBuilder otp = new StringBuilder();

        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(random.nextInt(10));
        }

        return otp.toString();
    }
}
