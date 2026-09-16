package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.SignUpRequest;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.AuthService;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.GenericMessageResponse;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.*;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    @Override
    public GenericMessageResponse signUp(SignUpRequest request) {

        String email = normalizeEmail(request.email());

        Optional<User> existingUser = userRepository.findByEmail(email);

        if (existingUser.isPresent()) {

            User user = existingUser.get();

            if (user.getAccountStatus() == UserStatus.PENDING_VERIFICATION) {
//                sendVerificationOtp(user);
            }

        } else {
            User user = User.builder()
                    .email(request.email())
                    .displayName(request.displayName())
                    .role(UserRoles.REGISTERED_USER)
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .accountStatus(UserStatus.PENDING_VERIFICATION)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            userRepository.save(user);
        }

        return new GenericMessageResponse(TOKEN_SENT_MSG);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private User findUserByEmail(String email){

        if (email == null || email.isBlank())
            throw new ValidationException(EMAIL_NOT_NULL_MSG);

        return userRepository.findByEmail(email)
                .orElseThrow(()-> new NotFoundException(USER_NOT_FOUND_MSG));
    }
}
