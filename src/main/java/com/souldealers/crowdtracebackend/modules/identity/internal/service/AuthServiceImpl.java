package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.*;
import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.GenericResponseMessage;
import com.souldealers.crowdtracebackend.shared.JwtService;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.*;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final NotificationService notificationService;

    @Override
    public GenericResponseMessage signUp(SignUpRequest request) {

        String email = normalizeEmail(request.email());

        Optional<User> existingUser = userRepository.findByEmail(email);

        if (existingUser.isPresent()) {

            User user = existingUser.get();

            if (user.getAccountStatus() == UserStatus.PENDING_VERIFICATION) {
                generateAndSendOtp(user, OtpType.CREATE);
            }

        } else {
            User user = User.builder()
                    .email(email)
                    .displayName(request.displayName())
                    .role(UserRoles.REGISTERED_USER)
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .accountStatus(UserStatus.PENDING_VERIFICATION)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            userRepository.save(user);

            generateAndSendOtp(user, OtpType.CREATE);
        }

        return new GenericResponseMessage(TOKEN_SENT_MSG);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        String email = normalizeEmail(request.email());
        User user = findUserByEmail(email);
        String token  = jwtService.generateToken(new SecurityUser(user));

        return LoginResponse.builder()
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .token(token)
                .build();
    }

    @Override
    @Transactional
    public GenericResponseMessage verifyOtp (VerifyOtpDto request){
        if (request == null) {
            throw new ValidationException(EMAIL_NOT_NULL_MSG);
        }

        String email = normalizeEmail(request.email());
        User user = findUserByEmailForUpdate(email);

        if (user.getAccountStatus() != UserStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException(EXISTING_EMAIL);
        }

        if (!otpService.consumeOtp(request.code(), email, OtpType.CREATE)) {
            throw new ValidationException("Could not verify this OTP");
        }

        user.setAccountStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        sendWelcomeEmailAfterCommit(user.getEmail(), user.getDisplayName());

        return new GenericResponseMessage(VERIFICATION_SUCCESS_MSG);
    }

    @Override
    public GenericResponseMessage resendOtp(ResendOtpRequest request) {
        User user = userRepository.findByEmail(request.email()).orElseThrow(()-> new NotFoundException(USER_NOT_FOUND_MSG));

        generateAndSendOtp(user, OtpType.CREATE);

        return new GenericResponseMessage(TOKEN_SENT_MSG);
    }

    @Override
    public GenericResponseMessage resetPasswordRequest(PasswordResetRequest request) {
        String email = normalizeEmail(request.email());
        Optional<User> byEmail = userRepository.findByEmail(email);

        byEmail.ifPresent(user -> generateAndSendOtp(user, OtpType.RESET));
        return new GenericResponseMessage(TOKEN_SENT_MSG);
    }

    @Override
    @Transactional
    public GenericResponseMessage resetPassword(PasswordReset request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new ConflictException(PASSWORD_MISMATCH);
        }

        String email = normalizeEmail(request.email());
        User user = findUserByEmailForUpdate(email);

        if (!otpService.consumeOtp(request.code(), email, OtpType.RESET)) {
            throw new ValidationException(OTP_VERIFICATION_FAILED_MSG);
        }

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        return new GenericResponseMessage(RESET_PASSWORD_SUCC);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ValidationException(EMAIL_NOT_NULL_MSG);
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private User findUserByEmail(String email){

        if (email == null || email.isBlank())
            throw new ValidationException(EMAIL_NOT_NULL_MSG);

        return userRepository.findByEmail(email)
                .orElseThrow(()-> new NotFoundException(USER_NOT_FOUND_MSG));
    }

    private User findUserByEmailForUpdate(String email){

        if (email == null || email.isBlank())
            throw new ValidationException(EMAIL_NOT_NULL_MSG);

        return userRepository.findByEmailForUpdate(email)
                .orElseThrow(()-> new NotFoundException(USER_NOT_FOUND_MSG));
    }

    private void generateAndSendOtp(User user, OtpType type){
        var otp = otpService.generateOtp(user.getEmail(), type);
        notificationService.sendOtpEmail(user.getEmail(), otp.getCode(), user.getDisplayName(), type);
    }

    private void sendWelcomeEmailAfterCommit(String email, String displayName) {
        Runnable sendWelcomeEmail = () -> notificationService.sendWelcomeEmail(email, displayName);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendWelcomeEmail.run();
                }
            });
        } else {
            sendWelcomeEmail.run();
        }
    }
}
