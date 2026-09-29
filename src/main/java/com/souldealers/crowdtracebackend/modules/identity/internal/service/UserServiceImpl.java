package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.UserService;
import com.souldealers.crowdtracebackend.modules.identity.PublicUserResponse;
import com.souldealers.crowdtracebackend.modules.identity.UserResponse;
import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationType;
import com.souldealers.crowdtracebackend.modules.identity.UpdateUserProfileRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.VerificationRequestRepository;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.USER_NOT_FOUND_MSG;

@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final VerificationRequestRepository verificationRequestRepository;

    @Override
    public PagedResponse<UserResponse> getAllUsers(Pageable pageable) {
        Page<User> userPage = userRepository.findAll(pageable);
        return PagedResponse.from(userPage.map(this::buildUserResponse));

    }

    @Override
    public UserResponse getCurrentUser(String email) {
        return buildUserResponse(findUser(email));
    }

    @Override
    public UserResponse updateProfile(String email, UpdateUserProfileRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new ValidationException("Display name is required");
        }

        User user = findUser(email);
        user.setDisplayName(request.displayName().trim());
        return buildUserResponse(userRepository.save(user));
    }

    @Override
    public PublicUserResponse getPublicProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND_MSG));

        VerificationType badgeType = resolveBadgeType(userId);
        return new PublicUserResponse(user.getDisplayName(), badgeType != null, badgeType);
    }

    /**
     * The badge a user currently holds, or null if none.
     *
     * <p>Resolved per verification type: for each type the most recent decision wins,
     * and the badge shown is the newest type still sitting at APPROVED. A user may hold
     * an IDENTITY badge and separately apply as an ORGANIZATION, so taking the single
     * newest decision across all types would let a rejected second application strip a
     * badge an administrator never revoked. Pending applications are not decisions and
     * are filtered out by the query.
     */
    private VerificationType resolveBadgeType(Long userId) {
        List<VerificationRequest> decisions =
                verificationRequestRepository.findDecidedRequestsNewestFirst(userId);

        Set<VerificationType> settled = EnumSet.noneOf(VerificationType.class);
        for (VerificationRequest decision : decisions) {
            if (!settled.add(decision.getVerificationType())) {
                continue; // superseded by a later decision for the same type
            }
            if (decision.getStatus() == VerificationStatus.APPROVED) {
                return decision.getVerificationType();
            }
        }
        return null;
    }

    private UserResponse buildUserResponse(User user){
        return UserResponse.builder()
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .accountStatus(user.getAccountStatus())
                .createdAt(user.getCreatedAt())
                .role(user.getRole())
                .build();
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND_MSG));
    }
}
