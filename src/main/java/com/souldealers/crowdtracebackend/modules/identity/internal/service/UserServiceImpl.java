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

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<Long, VerificationType> badges = resolveBadgeTypes(
                userPage.getContent().stream().map(User::getId).toList());
        return PagedResponse.from(userPage.map(user -> buildUserResponse(user, badges.get(user.getId()))));

    }

    @Override
    public UserResponse getCurrentUser(String email) {
        User user = findUser(email);
        return buildUserResponse(user, resolveBadgeType(user.getId()));
    }

    @Override
    public UserResponse updateProfile(String email, UpdateUserProfileRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new ValidationException("Display name is required");
        }

        User user = findUser(email);
        user.setDisplayName(request.displayName().trim());
        User saved = userRepository.save(user);
        return buildUserResponse(saved, resolveBadgeType(saved.getId()));
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
     * a POLICE badge and separately apply as an NGO, so taking the single
     * newest decision across all types would let a rejected second application strip a
     * badge an administrator never revoked. Pending applications are not decisions and
     * are filtered out by the query.
     */
    private VerificationType resolveBadgeType(Long userId) {
        return badgeFrom(verificationRequestRepository.findDecidedRequestsNewestFirst(userId));
    }

    /**
     * The same rule for a page of users, in one query instead of one per row.
     *
     * <p>Reads the owning id straight off the lazy proxy, which needs no extra select:
     * the foreign key is already on the request row.
     */
    private Map<Long, VerificationType> resolveBadgeTypes(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<VerificationRequest>> byUser = new LinkedHashMap<>();
        for (VerificationRequest decision
                : verificationRequestRepository.findDecidedRequestsNewestFirst(userIds)) {
            byUser.computeIfAbsent(decision.getUser().getId(), id -> new ArrayList<>()).add(decision);
        }
        Map<Long, VerificationType> badges = new HashMap<>();
        byUser.forEach((userId, decisions) -> {
            VerificationType badge = badgeFrom(decisions);
            if (badge != null) {
                badges.put(userId, badge);
            }
        });
        return badges;
    }

    private static VerificationType badgeFrom(List<VerificationRequest> decisionsNewestFirst) {
        Set<VerificationType> settled = EnumSet.noneOf(VerificationType.class);
        for (VerificationRequest decision : decisionsNewestFirst) {
            if (!settled.add(decision.getVerificationType())) {
                continue; // superseded by a later decision for the same type
            }
            if (decision.getStatus() == VerificationStatus.APPROVED) {
                return decision.getVerificationType();
            }
        }
        return null;
    }

    private UserResponse buildUserResponse(User user, VerificationType badgeType) {
        return UserResponse.builder()
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .accountStatus(user.getAccountStatus())
                .createdAt(user.getCreatedAt())
                .role(user.getRole())
                .verified(badgeType != null)
                .badgeType(badgeType)
                .build();
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND_MSG));
    }
}
